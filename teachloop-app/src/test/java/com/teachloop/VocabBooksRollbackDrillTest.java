package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teachloop.identity.admin.AdminService;
import com.teachloop.identity.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * T580 回滚演练：在**真实迁移过的库**上执行 `docs/reports/rollback-013/013-rollback.sql`，
 * 验证四件事：
 *
 * <ol>
 *   <li>BOOK 来源行先被改成 IMPORT（信息不丢），旧的三选一 check 能建起来；</li>
 *   <li>三张书表与两个 `book_id` 列被删干净，`vocab_words` / `vocab_progress` / `vocab_list_items`
 *       行数一行不少（学生已加入的词与进度必须留下）；</li>
 *   <li>脚本**可重复执行**（第二次不报错、结果不变）；</li>
 *   <li>回滚后 `kind='BOOK'` 的写入被旧约束拒绝——这正是「回退代码前必须先处理 BOOK 行」的证据。</li>
 * </ol>
 *
 * <p>本次在一次性 Postgres 16 容器（同一个应用跑 Flyway 迁移得到的库）上演练，**没有碰 qa 库**：
 * 本 session 禁止部署，qa 环境是跑快照产物的、改源码不影响它。命令与在 qa 库执行完全一致，
 * 只差 `psql` 的连接串。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.content.pack-dir=./target/no-content-pack"
        })
@Testcontainers
class VocabBooksRollbackDrillTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("teachloop.grading.poll-ms", () -> "3600000");
        registry.add("teachloop.grading.dispatch-ms", () -> "3600000");
        registry.add("teachloop.grading.sweep-ms", () -> "3600000");
        registry.add("teachloop.vocab.content.store-path", () -> "./target/test-vocab-rollback-delta.db");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_EMAIL = "rollback-admin@teachloop.local";
    private static final String ADMIN_PASSWORD = "rollback-admin-pass-123";

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdminService adminService;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("T580 回滚演练：BOOK 行转 IMPORT → 删表删列 → 学生词与进度不丢 → 脚本可重跑 → BOOK 写入被旧约束拒绝")
    void rollbackDrill() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local", "teachloop123");

        // 造数据：一本 4 词的书，学生加入 → 4 条 BOOK 来源 + 1 份副本词单 + 4 条引用
        String bookName = "回滚演练-" + UUID.randomUUID().toString().substring(0, 8);
        String bookId = createBook(base, admin, bookName);
        post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries",
                Map.of("text", "rollword1\nrollword2\nrollword3\nrollword4"));
        post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries",
                Map.of("text", "rollword5"));
        put(base, admin, "/api/admin/vocab/books/" + bookId, Map.of("published", true));
        post(base, student, "/api/vocab/books/" + bookId + "/join", null);

        long bookSources = count("select count(*) from vocab_word_sources where kind = 'BOOK'");
        assertThat(bookSources).isEqualTo(5);
        long wordsBefore = count("select count(*) from vocab_words");
        long progressBefore = count("select count(*) from vocab_progress");
        long itemsBefore = count("select count(*) from vocab_list_items");
        UUID anyWord = jdbc.queryForObject("select word_id from vocab_word_sources where kind = 'BOOK' limit 1",
                UUID.class);

        List<String> statements = statements(Path.of("../docs/reports/rollback-013/013-rollback.sql"));

        long started = System.nanoTime();
        for (String statement : statements) {
            jdbc.execute(statement);
        }
        long firstRunMs = (System.nanoTime() - started) / 1_000_000;

        // 1) BOOK 行变成 IMPORT，且带着同一个 batch_id（= 书 id），来源信息没丢
        assertThat(count("select count(*) from vocab_word_sources where kind = 'BOOK'")).isZero();
        assertThat(count("select count(*) from vocab_word_sources where kind = 'IMPORT' and batch_id = '"
                + bookId + "'::uuid")).isEqualTo(bookSources);

        // 2) 表与列都删了
        assertThat(count("""
                select count(*) from information_schema.tables
                 where table_schema = 'public'
                   and table_name in ('vocab_books', 'vocab_book_entries', 'vocab_book_joins')
                """)).isZero();
        assertThat(count("""
                select count(*) from information_schema.columns
                 where table_schema = 'public' and column_name = 'book_id'
                   and table_name in ('vocab_lists', 'vocab_word_sources')
                """)).isZero();
        // 唯一索引也删了（不然下次迁移会撞名）
        assertThat(count("select count(*) from pg_indexes where indexname = 'uk_vocab_lists_user_book'"))
                .isZero();

        // 3) 学生数据一行不少：词、进度、词单引用
        assertThat(count("select count(*) from vocab_words")).isEqualTo(wordsBefore);
        assertThat(count("select count(*) from vocab_progress")).isEqualTo(progressBefore);
        assertThat(count("select count(*) from vocab_list_items")).isEqualTo(itemsBefore);

        // 4) 脚本可重复执行：第二次不报错，结果不变
        long secondStarted = System.nanoTime();
        for (String statement : statements) {
            jdbc.execute(statement);
        }
        long secondRunMs = (System.nanoTime() - secondStarted) / 1_000_000;
        assertThat(count("select count(*) from vocab_word_sources where kind = 'BOOK'")).isZero();
        assertThat(count("select count(*) from vocab_words")).isEqualTo(wordsBefore);

        // 5) 旧约束真的回来了：回滚后再写 BOOK 会被库拒绝（所以必须「先处理 BOOK 行，再回退代码」）
        assertThatThrownBy(() -> jdbc.update(
                "insert into vocab_word_sources (word_id, kind, created_at) values (?, 'BOOK', now())",
                anyWord)).isInstanceOf(DataAccessException.class);

        System.out.println("[013] 回滚演练：语句数=" + statements.size()
                + " 首跑=" + firstRunMs + "ms 重跑=" + secondRunMs + "ms"
                + " BOOK行=" + bookSources + "→0 词=" + wordsBefore + " 进度=" + progressBefore
                + " 词单引用=" + itemsBefore);
    }

    // ── helpers ────────────────────────────────────────────────────

    /** 去掉注释行后按 `;` 切语句（脚本里没有存储过程/函数，切分是安全的）。 */
    private static List<String> statements(Path path) throws Exception {
        String text = Files.readString(path);
        StringBuilder sql = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            sql.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String chunk : sql.toString().split(";")) {
            if (!chunk.strip().isEmpty()) {
                statements.add(chunk.strip());
            }
        }
        return statements;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String createBook(String base, String admin, String name) throws Exception {
        return post(base, admin, "/api/admin/vocab/books",
                Map.of("name", name, "position", 1)).get("id").asString();
    }

    private String admin(String base) throws Exception {
        if (count("select count(*) from users where email = '" + ADMIN_EMAIL + "'") == 0) {
            adminService.createUser(ADMIN_EMAIL, UserRole.ADMIN, "回滚管理员", ADMIN_PASSWORD, true);
        }
        return login(base, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private tools.jackson.databind.JsonNode post(String base, String cookie, String path, Object body)
            throws Exception {
        return read(send(base, cookie, "POST", path, body), path);
    }

    private tools.jackson.databind.JsonNode put(String base, String cookie, String path, Object body)
            throws Exception {
        return read(send(base, cookie, "PUT", path, body), path);
    }

    private tools.jackson.databind.JsonNode read(HttpResponse<String> response, String path) throws Exception {
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Language", "zh-CN");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String base, String email, String password) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", password));
        assertThat(response.statusCode()).as("login " + email + " -> " + response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
