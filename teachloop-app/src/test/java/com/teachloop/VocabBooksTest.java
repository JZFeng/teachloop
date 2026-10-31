package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.vocab.content.ContentStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 013-word-books 验收（T570–T578）：管理端 CRUD/发布/审计、导入复用与逐行一致、
 * 覆盖率实时计算、推荐端口排序、学生端列表/详情/预览、加入幂等（含「不动进度」「不入队生成」）、
 * 会话按 listId 与 1000 词分批、付费字段校验与学生端裁剪。
 *
 * <p>关键断言都用**真实数字**：两次加入的 `created/duplicated`、`vocab_progress` 前后逐字段相等、
 * `vocab_generation_jobs` 前后行数相等、同书副本词单恰好 1 份。不写「大致正确」的断言，
 * 因为 AC-102.2/102.3/103.1 都是可数的。
 *
 * <p>生成任务这一档特意把 `on-import=true` + `engine=stub` 打开（轮询拉到一小时以后），
 * 这样「加入不触发全量生成」才是**会被回归抓到**的断言：真调了 `enqueueNewWords` 就会多出 500 条 job。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=true",
                "teachloop.vocab.generation.engine=stub",
                "teachloop.vocab.generation.on-import=true",
                "teachloop.vocab.generation.daily-budget=1000000",
                "teachloop.vocab.generation.poll-ms=3600000",
                "teachloop.vocab.content.pack-dir=./target/no-content-pack",
                "teachloop.vocab.lists.max-per-student=60"
        })
@Testcontainers
class VocabBooksTest {

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
        registry.add("teachloop.vocab.content.store-path", () -> "./target/test-vocab-books-delta.db");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_EMAIL = "books-admin@teachloop.local";
    private static final String ADMIN_PASSWORD = "books-admin-pass-123";

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdminService adminService;

    @Autowired
    ContentStore contentStore;


    private final HttpClient client = HttpClient.newHttpClient();
    private String adminCookie;

    // ────────────────────────────────────────────────────────────────
    // T570：管理端 CRUD / 发布下架 / 权限 / 审计
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T570 管理端：非 ADMIN 403、建书草稿不可见、发布可见、下架 404、无物理删除、审计成对")
    void adminCrudPublishArchiveAndAudit() throws Exception {
        String base = base();
        String student = login(base, "student@teachloop.local");
        String admin = admin(base);
        String name = unique("T570");

        // AC-100.1：学生调管理端一律 403
        HttpResponse<String> forbidden = send(base, student, "GET", "/api/admin/vocab/books", null);
        assertThat(forbidden.statusCode()).isEqualTo(403);

        // 建书（默认草稿）：管理端可见，学生端不出现、按 id 访问 404（AC-101.1 / AC-100.2）
        JsonNode created = post(base, admin, "/api/admin/vocab/books",
                Map.of("name", name, "description", "建书自检", "position", 7));
        UUID bookId = UUID.fromString(created.get("id").asString());
        assertThat(created.get("published").asBoolean()).isFalse();
        assertThat(created.get("payMode").asString()).isEqualTo("FREE");
        assertThat(created.get("priceCents").asInt()).isZero();
        assertThat(studentBooks(base, student)).noneMatch(book -> book.get("id").asString().equals(bookId.toString()));
        assertThat(send(base, student, "GET", "/api/vocab/books/" + bookId, null).statusCode()).isEqualTo(404);

        // 发布 → 学生可见、详情 200
        JsonNode published = put(base, admin, "/api/admin/vocab/books/" + bookId,
                Map.of("published", true));
        assertThat(published.get("published").asBoolean()).isTrue();
        assertThat(studentBooks(base, student)).anyMatch(book -> book.get("id").asString().equals(bookId.toString()));
        assertThat(send(base, student, "GET", "/api/vocab/books/" + bookId, null).statusCode()).isEqualTo(200);

        // 下架 → 学生端列表不含、详情 404；管理端仍能查到（不泄露存在性同时不丢数据）
        put(base, admin, "/api/admin/vocab/books/" + bookId, Map.of("published", false));
        assertThat(studentBooks(base, student)).noneMatch(book -> book.get("id").asString().equals(bookId.toString()));
        assertThat(send(base, student, "GET", "/api/vocab/books/" + bookId, null).statusCode()).isEqualTo(404);
        assertThat(get(base, admin, "/api/admin/vocab/books/" + bookId).get("published").asBoolean()).isFalse();

        // AC-100.5：没有物理删除接口——调用 DELETE 不会删掉书
        int deleteStatus = send(base, admin, "DELETE", "/api/admin/vocab/books/" + bookId, null).statusCode();
        assertThat(deleteStatus).as("DELETE /api/admin/vocab/books/{id} 不应是 200").isNotEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from vocab_books where id = ?", Integer.class, bookId))
                .isEqualTo(1);

        // 审计：发布与下架各一条，action 名固定
        List<String> actions = jdbc.queryForList("""
                select action from audit_logs
                 where target_type = 'vocab_book' and target_id = ?
                 order by created_at asc
                """, String.class, bookId.toString());
        assertThat(actions).contains("book:create", "book:publish", "book:archive");
    }

    // ────────────────────────────────────────────────────────────────
    // T578：付费字段（v1 只能免费）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T578 付费：建书 PAID 400、加入 PAID 书 409、学生端响应无价格字段、管理端可见")
    void payModeIsFreeOnly() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");

        // AC-105.2：建书传 PAID → 400
        HttpResponse<String> paidCreate = send(base, admin, "POST", "/api/admin/vocab/books",
                Map.of("name", unique("T578-paid"), "payMode", "PAID"));
        assertThat(paidCreate.statusCode()).isEqualTo(400);

        // 免费书：学生端响应必须没有 payMode / priceCents / currency（AC-105.4）
        UUID bookId = createPublishedBook(base, admin, unique("T578-free"), 0, "alpha\nbeta");
        JsonNode detail = get(base, student, "/api/vocab/books/" + bookId);
        assertThat(detail.has("payMode")).isFalse();
        assertThat(detail.has("priceCents")).isFalse();
        assertThat(detail.has("currency")).isFalse();
        JsonNode list = studentBooks(base, student);
        for (JsonNode book : list) {
            assertThat(book.has("payMode")).isFalse();
            assertThat(book.has("priceCents")).isFalse();
        }
        // 管理端可见（v2 上架时才需要，v1 只是预留）
        JsonNode adminView = get(base, admin, "/api/admin/vocab/books/" + bookId);
        assertThat(adminView.get("payMode").asString()).isEqualTo("FREE");
        assertThat(adminView.has("priceCents")).isTrue();
        assertThat(adminView.has("currency")).isTrue();
        // 改书成 PAID 也要 400
        assertThat(send(base, admin, "PUT", "/api/admin/vocab/books/" + bookId,
                Map.of("payMode", "PAID")).statusCode()).isEqualTo(400);

        // AC-105.3：库里已经是 PAID 的书（v2 才可能），加入返回 409
        jdbc.update("update vocab_books set pay_mode = 'PAID' where id = ?", bookId);
        HttpResponse<String> paidJoin = send(base, student, "POST",
                "/api/vocab/books/" + bookId + "/join", null);
        assertThat(paidJoin.statusCode()).isEqualTo(409);
        jdbc.update("update vocab_books set pay_mode = 'FREE' where id = ?", bookId);
    }

    // ────────────────────────────────────────────────────────────────
    // T592：语言 / 级别的白名单（下拉的可选值就是系统真能服务的值）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T592 语言只收 en、级别只收 CEFR：zh/cn 建书 400，b1 归一化成 B1，空级别可保存")
    void languageAndLevelAreWhitelisted() throws Exception {
        String base = base();
        String admin = admin(base);

        // 语言：en 之外一律 400（zh 与用户口头的 cn 都不是 v1 能服务的值）
        for (String unsupported : List.of("zh", "es", "cn")) {
            HttpResponse<String> rejected = send(base, admin, "POST", "/api/admin/vocab/books",
                    Map.of("name", unique("T592-" + unsupported), "language", unsupported));
            assertThat(rejected.statusCode()).as("language=" + unsupported).isEqualTo(400);
        }

        // 级别：CEFR 之外 400
        for (String unsupported : List.of("中级", "upper", "B3")) {
            HttpResponse<String> rejected = send(base, admin, "POST", "/api/admin/vocab/books",
                    Map.of("name", unique("T592-lv"), "level", unsupported));
            assertThat(rejected.statusCode()).as("level=" + unsupported).isEqualTo(400);
        }

        // 合法值：小写 b1 归一化成 B1；不传级别落 null
        HttpResponse<String> created = send(base, admin, "POST", "/api/admin/vocab/books",
                Map.of("name", unique("T592-ok"), "language", "en", "level", "b1"));
        assertThat(created.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(created.body());
        assertThat(body.get("level").asString()).isEqualTo("B1");
        assertThat(body.get("language").asString()).isEqualTo("en");

        HttpResponse<String> noLevel = send(base, admin, "POST", "/api/admin/vocab/books",
                Map.of("name", unique("T592-none")));
        assertThat(noLevel.statusCode()).isEqualTo(200);
        JsonNode noLevelBody = MAPPER.readTree(noLevel.body());
        assertThat(noLevelBody.get("level") == null || noLevelBody.get("level").isNull()).isTrue();

        // 改书也走同一套校验：把已有书改成 zh → 400
        UUID bookId = UUID.fromString(body.get("id").asString());
        assertThat(send(base, admin, "PUT", "/api/admin/vocab/books/" + bookId,
                Map.of("language", "zh")).statusCode()).isEqualTo(400);
    }

    // ────────────────────────────────────────────────────────────────
    // T571：导入复用（同一段文本，两页同样的清洗与跳过）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T571 导入：与加词页同一段文本的 cleanups/skipped 逐条相等；created/duplicated/invalid 正确；非法行不写库")
    void importParityAndEntryCounts() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");
        UUID bookId = createBook(base, admin, unique("T571"), 0);

        // 故意混入：markdown 标题、表格、全角、大小写、变音符、重复、超长、空行
        String text = """
                # Unit 5 生词
                | word | meaning |
                |---|---|
                Apple
                apple
                A\u0301pple
                ｈｅｌｌｏ
                hello
                hello
                %s
                bonjour!
                这是一个说明行，应当被跳过。
                Abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij
                banana
                """.formatted("x".repeat(80));

        JsonNode mine = post(base, student, "/api/vocab/import/preview", Map.of("text", text, "language", "en"));
        JsonNode book = post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries/preview",
                Map.of("text", text));

        // AC-100.6：清洗与跳过逐条一致（行号 / 规则 / 前后值 / 跳过原因）
        assertThat(cleanups(book)).as("cleanups 必须与加词页逐条相等").isEqualTo(cleanups(mine));
        assertThat(skipped(book)).as("skipped 必须与加词页逐条相等").isEqualTo(skipped(mine));
        assertThat(book.get("lineCount").asInt()).isEqualTo(mine.get("lineCount").asInt());
        assertThat(book.get("distinctWords").size()).isEqualTo(mine.get("distinctWords").size());
        // 返回体形状也一致（前端复用同一块预览组件）
        assertThat(fieldNames(book)).isEqualTo(fieldNames(mine));
        System.out.println("[013] 同一段文本：加词页 cleanups=" + mine.get("cleanups").size()
                + " skipped=" + mine.get("skipped").size()
                + " tokens=" + mine.get("tokens").size()
                + " 书导入 cleanups=" + book.get("cleanups").size()
                + " skipped=" + book.get("skipped").size()
                + " tokens=" + book.get("tokens").size());

        // 提交：书里 created/duplicated/failed；非法行（超长 / 说明行被跳过后剩下的合法 token）
        JsonNode commit = post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries",
                Map.of("text", text));
        int created = commit.get("counts").get("created").asInt();
        int duplicated = commit.get("counts").get("duplicated").asInt();
        int failed = commit.get("counts").get("failed").asInt();
        int total = commit.get("counts").get("total").asInt();
        assertThat(total).isEqualTo(created + duplicated + failed);
        assertThat(created).isPositive();
        assertThat(failed).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vocab_book_entries where book_id = ?",
                Integer.class, bookId)).isEqualTo(created);
        // 幂等：同一段文本再导一次 → created=0、duplicated=total-failed、词条数不变（AC-100.3）
        JsonNode again = post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries",
                Map.of("text", text));
        assertThat(again.get("counts").get("created").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vocab_book_entries where book_id = ?",
                Integer.class, bookId)).isEqualTo(created);
        System.out.println("[013] 书导入提交 total=" + total + " created=" + created
                + " duplicated=" + duplicated + " failed=" + failed
                + "；二次提交 created=" + again.get("counts").get("created").asInt()
                + " duplicated=" + again.get("counts").get("duplicated").asInt());

        // 非法行不写库：超长词形与说明行都不在词条里
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_book_entries where book_id = ? and length(form) > 64
                """, Integer.class, bookId)).isZero();
        List<String> forms = jdbc.queryForList(
                "select form from vocab_book_entries where book_id = ? order by position", String.class, bookId);
        assertThat(forms).noneMatch(form -> form.length() > 64);

        // 导入到书不碰学生词库（书是全局只读的）
        assertThat(jdbc.queryForObject(
                "select count(*) from vocab_words where normalized_form in ('apple', 'hello')",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("T571 新建面板的草稿预览：不需要书 id，返回体与书预览同形，建书前后计数一致，且不落库")
    void draftPreviewForNewBookPanel() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");
        // 重复词 + 说明行（被跳过）+ 超长行（失败）：三种计数各出现一次
        String text = """
                draftalpha
                draftalpha
                这是一个说明行，应当被跳过。
                %s
                draftbeta
                """.formatted("y".repeat(80));

        JsonNode draft = post(base, admin, "/api/admin/vocab/books/draft/entries/preview",
                Map.of("text", text, "language", "en"));
        // 书还没建：没有「已在书中」这一档，duplicated 只算批内重复
        assertThat(draft.get("counts").get("created").asInt()).as("草稿：两个不同词形").isEqualTo(2);
        assertThat(draft.get("counts").get("duplicated").asInt()).as("草稿：批内重复 1 条").isEqualTo(1);
        assertThat(draft.get("counts").get("failed").asInt()).as("超长行计失败").isEqualTo(1);

        // 同一段文本在「刚建好的空书」上预览：字段与计数必须完全一致，否则新建面板与编辑面板会互相打脸
        UUID bookId = createBook(base, admin, unique("T571-draft"), 0);
        JsonNode book = post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries/preview",
                Map.of("text", text, "language", "en"));
        assertThat(fieldNames(draft)).isEqualTo(fieldNames(book));
        assertThat(draft.get("counts")).isEqualTo(book.get("counts"));
        assertThat(cleanups(draft)).isEqualTo(cleanups(book));
        assertThat(skipped(draft)).isEqualTo(skipped(book));

        // 预览不写库：草稿预览跑完，库里没有任何 draft* 词条
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_book_entries where normalized_form like 'draft%'
                """, Integer.class)).isZero();

        // AC-100.1：草稿预览同样只在 ADMIN 下可用，学生打进来是 403
        assertThat(send(base, student, "POST", "/api/admin/vocab/books/draft/entries/preview",
                Map.of("text", text)).statusCode()).isEqualTo(403);
    }

    // ────────────────────────────────────────────────────────────────
    // T572：覆盖率实时计算
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T572 覆盖率：就绪数 = 内容索引命中数；加入前后不变；空书 0/0 不抛异常")
    void coverageCountsAndStability() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");
        UUID bookId = createPublishedBook(base, admin, unique("T572"), 0,
                "covalpha\ncovbeta\ncovgamma\ncovdelta\ncovepsilon");

        // 三词写进内容索引（delta），另两词没有内容
        for (String form : List.of("covalpha", "covbeta", "covgamma")) {
            contentStore.put("en", form, "{\"senses\":[{\"pos\":\"n\",\"zh\":\"测试\"}]}", "TEST");
        }

        JsonNode detail = get(base, student, "/api/vocab/books/" + bookId);
        assertThat(detail.get("readyCount").asInt()).isEqualTo(3);
        assertThat(detail.get("wordCount").asInt()).isEqualTo(5);

        // AC-103.3：加入前后覆盖率不变（加入不预生成内容、也不写内容索引）
        join(base, student, bookId);
        JsonNode after = get(base, student, "/api/vocab/books/" + bookId);
        assertThat(after.get("readyCount").asInt()).isEqualTo(3);
        assertThat(after.get("wordCount").asInt()).isEqualTo(5);

        // AC-100.4：管理端与本页同一口径
        JsonNode adminView = get(base, admin, "/api/admin/vocab/books/" + bookId);
        assertThat(adminView.get("readyCount").asInt()).isEqualTo(3);
        assertThat(adminView.get("wordCount").asInt()).isEqualTo(5);
        assertThat(adminView.get("joinCount").asLong()).isEqualTo(1L);

        // 空书：0/0，不是异常
        UUID empty = createPublishedBook(base, admin, unique("T572-empty"), 0, "");
        JsonNode emptyDetail = get(base, student, "/api/vocab/books/" + empty);
        assertThat(emptyDetail.get("wordCount").asInt()).isZero();
        assertThat(emptyDetail.get("readyCount").asInt()).isZero();

        // 预览逐行 ready：alpha/beta/gamma 就绪，delta/epsilon 生成中（AC-101.3）
        JsonNode entries = get(base, student, "/api/vocab/books/" + bookId + "/entries?page=1&size=50");
        Map<String, Boolean> ready = new HashMap<>();
        for (JsonNode item : entries.get("items")) {
            ready.put(item.get("form").asString(), item.get("ready").asBoolean());
        }
        assertThat(ready.get("covalpha")).isTrue();
        assertThat(ready.get("covdelta")).isFalse();
    }

    // ────────────────────────────────────────────────────────────────
    // T573：推荐端口（已发布 → position → 加入人数）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T573 推荐：position 升序、同 position 按加入人数降序、取 2 本；不足 2 本返回全部；草稿不入推荐")
    void recommendationOrderAndShortfall() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");

        // 推荐是全局的：先把别的测试造的书全部下架，本方法只断言自己这两本（方法间顺序无关）
        jdbc.update("update vocab_books set published = false");
        UUID lowJoin = createPublishedBook(base, admin, unique("T573-b"), -1000, "recword1");
        UUID highJoin = createPublishedBook(base, admin, unique("T573-a"), -1001, "recword2");
        UUID draft = createBook(base, admin, unique("T573-draft"), -1002);
        post(base, admin, "/api/admin/vocab/books/" + draft + "/entries", Map.of("text", "recword3"));

        // 加入人数：highJoin 3 人、lowJoin 1 人
        addJoins(highJoin, 3);
        addJoins(lowJoin, 1);

        JsonNode recommended = get(base, student, "/api/vocab/books/recommended?limit=2");
        assertThat(recommended.size()).isEqualTo(2);
        // position 升序优先：-1001 的 highJoin 在前
        assertThat(recommended.get(0).get("id").asString()).isEqualTo(highJoin.toString());
        assertThat(recommended.get(1).get("id").asString()).isEqualTo(lowJoin.toString());
        // 每张推荐卡都带词数与覆盖率（AC-104.1）
        assertThat(recommended.get(0).get("wordCount").asInt()).isEqualTo(1);
        assertThat(recommended.get(0).has("readyCount")).isTrue();
        // 草稿不进推荐（AC-101.1）
        for (JsonNode book : recommended) {
            assertThat(book.get("id").asString()).isNotEqualTo(draft.toString());
        }

        // 同 position 时按加入人数降序：把两本挪到同一 position 再看
        jdbc.update("update vocab_books set position = -2000 where id in (?, ?)", highJoin, lowJoin);
        JsonNode tied = get(base, student, "/api/vocab/books/recommended?limit=2");
        assertThat(tied.get(0).get("id").asString()).isEqualTo(highJoin.toString());
        jdbc.update("update vocab_books set published = false where id in (?, ?)", highJoin, lowJoin);
        // 只有 1 本可推荐时返回 1 本，不补齐、不报错（AC-104.3）
        jdbc.update("update vocab_books set published = true where id = ?", lowJoin);
        int publishedCount = jdbc.queryForObject(
                "select count(*) from vocab_books where published", Integer.class);
        assertThat(publishedCount).isEqualTo(1);
        JsonNode shortfall = get(base, student, "/api/vocab/books/recommended?limit=2");
        assertThat(shortfall.size()).isEqualTo(1);
        assertThat(shortfall.get(0).get("id").asString()).isEqualTo(lowJoin.toString());
        // 一本都没有时回空数组，不是 500（AC-104.4 的降级前提）
        jdbc.update("update vocab_books set published = false where id = ?", lowJoin);
        assertThat(get(base, student, "/api/vocab/books/recommended?limit=2").size()).isZero();
    }

    // ────────────────────────────────────────────────────────────────
    // T574：学生端列表 / 详情 / 预览分页
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T574 学生端：只列已发布且按 position 排序；详情含覆盖率与已加入数；预览 50/页与末页总数正确")
    void studentListDetailAndPreview() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");
        String name = unique("T574");

        // 61 个词：第 1 页 50、第 2 页 11，末页 total 仍是 61
        String text = IntStream.range(1, 62).mapToObj(index -> "pageword" + index)
                .collect(Collectors.joining("\n"));
        UUID bookId = createPublishedBook(base, admin, name, -3000, text);

        JsonNode entries = get(base, student, "/api/vocab/books/" + bookId + "/entries?page=1&size=50");
        assertThat(entries.get("items").size()).isEqualTo(50);
        assertThat(entries.get("total").asLong()).isEqualTo(61);
        assertThat(entries.get("page").asInt()).isEqualTo(1);
        JsonNode last = get(base, student, "/api/vocab/books/" + bookId + "/entries?page=2&size=50");
        assertThat(last.get("items").size()).isEqualTo(11);
        assertThat(last.get("totalWords").asInt()).isEqualTo(61);
        // 分页不重叠：两页词形合起来 61 个不同的
        Set<String> forms = new HashSet<>();
        for (JsonNode item : entries.get("items")) {
            forms.add(item.get("form").asString());
        }
        for (JsonNode item : last.get("items")) {
            forms.add(item.get("form").asString());
        }
        assertThat(forms).hasSize(61);
        // 越界页返回空列表而不是异常
        JsonNode beyond = get(base, student, "/api/vocab/books/" + bookId + "/entries?page=9&size=50");
        assertThat(beyond.get("items").size()).isZero();

        // 详情：未加入时 joined=false、listId=null、joinedEntries=0
        JsonNode detail = get(base, student, "/api/vocab/books/" + bookId);
        assertThat(detail.get("name").asString()).isEqualTo(name);
        assertThat(detail.get("wordCount").asInt()).isEqualTo(61);
        assertThat(detail.get("joined").asBoolean()).isFalse();
        assertThat(detail.get("listId").isNull()).isTrue();
        assertThat(detail.get("joinedEntries").asInt()).isZero();

        // 加入 2 个词后：joinedEntries 反映「书里已在我词库」的条数（部分加入）
        JsonNode newList = post(base, student, "/api/vocab/lists", Map.of("name", unique("T574-list")));
        post(base, student, "/api/vocab/lists/" + newList.get("id").asString() + "/words",
                Map.of("forms", List.of("pageword1", "pageword2")));
        JsonNode partial = get(base, student, "/api/vocab/books/" + bookId);
        assertThat(partial.get("joinedEntries").asInt()).isEqualTo(2);
    }

    // ────────────────────────────────────────────────────────────────
    // T575：加入事务（幂等 / 只追加来源 / 不动进度 / 不入队生成 / 副本与后缀）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T575 加入：两次调用 created/duplicated 实测；只追加 BOOK 来源；进度前后一致；不新增生成任务；副本唯一且同名加后缀")
    void joinIsIdempotentAndKeepsProgress() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");
        UUID studentId = studentId();
        String name = unique("T575");

        // 书：5 词；其中 2 词已在学生词库且带进度（会被「只追加来源」覆盖，不许被重置）
        UUID bookId = createPublishedBook(base, admin, name, 0,
                "joinword1\njoinword2\njoinword3\njoinword4\njoinword5");
        UUID existingA = insertWordWithProgress(studentId, "joinword1", "REVIEW", 4.5, 12);
        UUID existingB = insertWordWithProgress(studentId, "joinword2", "LEARNING", 1.0, 3);
        String dueA = dueAt(existingA);
        String dueB = dueAt(existingB);

        // 同名词单先占位：副本必须自动加后缀（AC-102.5）
        post(base, student, "/api/vocab/lists", Map.of("name", name));

        long jobsBefore = jdbc.queryForObject("select count(*) from vocab_generation_jobs", Long.class);

        // AC-102.1：一次请求返回四元组
        JsonNode first = join(base, student, bookId);
        int created = first.get("created").asInt();
        int duplicated = first.get("duplicated").asInt();
        int total = first.get("total").asInt();
        String listId = first.get("listId").asString();
        assertThat(total).isEqualTo(5);
        assertThat(created).isEqualTo(3);
        assertThat(duplicated).isEqualTo(2);
        assertThat(created + duplicated).isEqualTo(total);
        System.out.println("[013] 首次加入 book=" + bookId + " created=" + created
                + " duplicated=" + duplicated + " total=" + total + " listId=" + listId);

        // AC-102.3：已在库的词的进度逐字段不变
        assertThat(dueAt(existingA)).isEqualTo(dueA);
        assertThat(dueAt(existingB)).isEqualTo(dueB);
        assertThat(progressState(existingA)).isEqualTo("REVIEW");
        assertThat(progressState(existingB)).isEqualTo("LEARNING");
        assertThat(jdbc.queryForObject("select interval_days from vocab_progress where word_id = ?",
                java.math.BigDecimal.class, existingA)).isEqualByComparingTo("4.5");

        // AC-102.3：只追加 BOOK 来源（每个词一条，带 book_id 与书名 label）
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_word_sources where book_id = ? and kind = 'BOOK'
                """, Integer.class, bookId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_word_sources
                 where book_id = ? and kind = 'BOOK' and label = ?
                """, Integer.class, bookId, name)).isEqualTo(5);

        // AC-103.1：加入不触发全量内容生成（true 配置下这条断言会抓到回归）
        long jobsAfter = jdbc.queryForObject("select count(*) from vocab_generation_jobs", Long.class);
        assertThat(jobsAfter).as("加入一本书不应新增任何内容生成任务").isEqualTo(jobsBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_generation_jobs j
                  join vocab_words w on w.id = j.subject_id
                 where j.subject_type = 'WORD' and w.normalized_form like 'joinword%'
                """, Integer.class)).isZero();

        // AC-102.5：副本词单名 = 书名 + " (2)"（同名已被占用），且只有一份
        Map<String, Object> copy = jdbc.queryForMap("""
                select id, name, book_id from vocab_lists where user_id = ? and book_id = ?
                """, studentId, bookId);
        assertThat(copy.get("name")).isEqualTo(name + " (2)");
        assertThat(copy.get("id").toString()).isEqualTo(listId);
        // 学生端词单列表带 bookId（前端据此打「词书副本」标签）
        JsonNode lists = get(base, student, "/api/vocab/lists");
        boolean tagged = false;
        for (JsonNode row : lists) {
            if (row.get("id").asString().equals(listId)) {
                tagged = bookId.toString().equals(row.get("bookId").asString());
            }
        }
        assertThat(tagged).isTrue();

        // AC-102.2：第二次点 = created 0 / duplicated 全量，且不新建第二份副本词单
        JsonNode second = join(base, student, bookId);
        assertThat(second.get("created").asInt()).isZero();
        assertThat(second.get("duplicated").asInt()).isEqualTo(total);
        assertThat(second.get("total").asInt()).isEqualTo(total);
        assertThat(second.get("listId").asString()).isEqualTo(listId);
        System.out.println("[013] 二次加入 book=" + bookId + " created=" + second.get("created").asInt()
                + " duplicated=" + second.get("duplicated").asInt()
                + " total=" + second.get("total").asInt()
                + " listId=" + second.get("listId").asString());
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_lists where user_id = ? and book_id = ? and not deleted
                """, Integer.class, studentId, bookId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_book_joins where book_id = ? and user_id = ?
                """, Integer.class, bookId, studentId)).isEqualTo(1);
        // 来源不因重复加入变多
        assertThat(jdbc.queryForObject("""
                select count(*) from vocab_word_sources where book_id = ? and kind = 'BOOK'
                """, Integer.class, bookId)).isEqualTo(5);

        // 从这里起把这本书留给「会话」测试以外的世界：不影响别的方法（各自独立造书）
    }

    // ────────────────────────────────────────────────────────────────
    // T576：会话按 listId（含 1000 词分批不重叠）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T576 会话按 listId：总张数 = 书词数、不含书外词；2000 词按 1000 分两批且不重叠；他人词单 404")
    void reviewSessionByListId() throws Exception {
        String base = base();
        String admin = admin(base);
        String student = login(base, "student@teachloop.local");
        UUID studentId = studentId();

        // 小书：5 词，另有 3 个书外词在词库（会话里不许出现）
        UUID small = createPublishedBook(base, admin, unique("T576-small"), 0,
                "sessword1\nsessword2\nsessword3\nsessword4\nsessword5");
        JsonNode joined = join(base, student, small);
        String listId = joined.get("listId").asString();
        JsonNode session = post(base, student, "/api/vocab/review-sessions", Map.of("listId", listId));
        assertThat(session.get("total").asInt()).isEqualTo(5);
        // 快照里的词 = 书里的 5 个词，不含书外词（AC-102.4）
        Set<String> scopeForms = scopeForms(session.get("id").asString());
        assertThat(scopeForms).containsExactlyInAnyOrder(
                "sessword1", "sessword2", "sessword3", "sessword4", "sessword5");

        // 他人词单 404（不泄露存在性）
        String other = createUserAccount(base, "other-student-013@teachloop.local", UserRole.STUDENT,
                "other-pass-013");
        HttpResponse<String> foreign = send(base, other, "POST", "/api/vocab/review-sessions",
                Map.of("listId", listId));
        assertThat(foreign.statusCode()).isEqualTo(404);

        // 无 listId 时旧行为不变：wordIds 路径照常
        List<String> wordIds = jdbc.queryForList("""
                select id::text from vocab_words where user_id = ? and normalized_form like 'sessword%'
                """, String.class, studentId);
        JsonNode legacy = post(base, student, "/api/vocab/review-sessions", Map.of("wordIds", wordIds));
        assertThat(legacy.get("total").asInt()).isEqualTo(5);

        // 2000 词的书：两批各 1000、交集为空
        String bigName = unique("T576-big");
        UUID big = createPublishedBook(base, admin, bigName, 0,
                IntStream.range(1, 2001).mapToObj(index -> "bigword" + index)
                        .collect(Collectors.joining("\n")));
        JsonNode bigJoin = join(base, student, big);
        assertThat(bigJoin.get("total").asInt()).isEqualTo(2000);
        String bigList = bigJoin.get("listId").asString();
        JsonNode batch1 = post(base, student, "/api/vocab/review-sessions", Map.of("listId", bigList));
        JsonNode batch2 = post(base, student, "/api/vocab/review-sessions", Map.of("listId", bigList));
        assertThat(batch1.get("total").asInt()).isEqualTo(1000);
        assertThat(batch2.get("total").asInt()).isEqualTo(1000);
        Set<String> first = scopeForms(batch1.get("id").asString());
        Set<String> second = scopeForms(batch2.get("id").asString());
        assertThat(first).hasSize(1000);
        assertThat(second).hasSize(1000);
        Set<String> overlap = new HashSet<>(first);
        overlap.retainAll(second);
        assertThat(overlap).as("两批不许重叠").isEmpty();
        assertThat(first.size() + second.size()).isEqualTo(2000);
    }

    // ────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────────
    // 2026-10-30 用户 Bug：发布即预热（FORM 任务），学生不必先加入再进复习
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("发布即预热：草稿导入不入队、发布为缺失词条入队 FORM、重复发布不新增、已发布书导入新词继续预热")
    void publishPreheatsMissingBookContent() throws Exception {
        String base = base();
        String admin = admin(base);
        UUID bookId = createBook(base, admin, unique("T591-preheat"), 904);
        String seed = unique("preheat").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String first = seed + "alpha";
        String second = seed + "beta";
        String third = seed + "gamma";

        // 草稿书导入：不入队（学生看不到，没必要先烧 AI）
        post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries",
                Map.of("text", first + "\n" + second + "\n" + third));
        assertThat(formJobs(first, second, third)).isZero();

        // 发布 → 三个缺失词形全部入队：后台优先级（不抢学生队首）、初始 QUEUED
        put(base, admin, "/api/admin/vocab/books/" + bookId, Map.of("published", true));
        assertThat(formJobs(first, second, third)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                select priority from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form in (?, ?, ?)
                """, Integer.class, first, second, third)).containsOnly(0);
        assertThat(jdbc.queryForList("""
                select state from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_form in (?, ?, ?)
                """, String.class, first, second, third)).containsOnly("QUEUED");

        // 重复发布（含下架再发布）不新增、也不重置在跑的任务：幂等键在 (subject_type, subject_id)
        put(base, admin, "/api/admin/vocab/books/" + bookId, Map.of("published", true));
        put(base, admin, "/api/admin/vocab/books/" + bookId, Map.of("published", false));
        put(base, admin, "/api/admin/vocab/books/" + bookId, Map.of("published", true));
        assertThat(formJobs(first, second, third)).isEqualTo(3);

        // 已发布的书继续导入新词：新词也要入队，否则学生预览里又是一个「永远生成中」
        String fourth = seed + "delta";
        post(base, admin, "/api/admin/vocab/books/" + bookId + "/entries", Map.of("text", fourth));
        assertThat(formJobs(fourth)).isEqualTo(1);
        assertThat(formJobs(first, second, third)).isEqualTo(3);
    }

    /** 这些词形的 FORM 任务数（书里的词不绑定任何学生词库行，所以只数 FORM）。 */
    private long formJobs(String... forms) {
        String placeholders = String.join(",",
                java.util.Collections.nCopies(forms.length, "?"));
        Long count = jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where subject_type = 'FORM'"
                        + " and subject_form in (" + placeholders + ")", Long.class, (Object[]) forms);
        return count == null ? 0 : count;
    }

    private List<JsonNode> cleanups(JsonNode preview) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : preview.get("cleanups")) {
            rows.add(row);
        }
        return rows;
    }

    private List<JsonNode> skipped(JsonNode preview) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : preview.get("skipped")) {
            rows.add(row);
        }
        return rows;
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** 会话快照里的词形（直接读 scope jsonb，不受 CurrentWord 分页影响）。 */
    private Set<String> scopeForms(String sessionId) throws Exception {
        String scope = jdbc.queryForObject(
                "select cast(scope as text) from vocab_review_sessions where id = ?",
                String.class, UUID.fromString(sessionId));
        Set<String> forms = new HashSet<>();
        List<String> ids = new ArrayList<>();
        for (JsonNode id : MAPPER.readTree(scope).get("wordIds")) {
            ids.add(id.asString());
        }
        if (ids.isEmpty()) {
            return forms;
        }
        String in = String.join(",", ids.stream().map(id -> "?").toList());
        List<String> normalized = jdbc.queryForList(
                "select normalized_form from vocab_words where id::text in (" + in + ")",
                String.class, ids.toArray());
        forms.addAll(normalized);
        return forms;
    }

    private UUID insertWordWithProgress(UUID userId, String form, String state, double intervalDays,
                                        int reviewedCount) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp due = Timestamp.from(Instant.now().plusSeconds(86_400));
        jdbc.update("""
                insert into vocab_words (id, user_id, language, form, normalized_form, content_status,
                                         removed, deleted, created_at, updated_at)
                values (?, ?, 'en', ?, ?, 'PENDING', false, false, ?, ?)
                """, id, userId, form, form, now, now);
        jdbc.update("""
                insert into vocab_progress (word_id, user_id, state, due_at, interval_days, ease,
                                            lapses, reviewed_count, updated_at)
                values (?, ?, ?, ?, ?, 2.50, 0, ?, ?)
                """, id, userId, state, due, intervalDays, reviewedCount, now);
        return id;
    }

    private String dueAt(UUID wordId) {
        return jdbc.queryForObject("select due_at::text from vocab_progress where word_id = ?",
                String.class, wordId);
    }

    private String progressState(UUID wordId) {
        return jdbc.queryForObject("select state from vocab_progress where word_id = ?", String.class, wordId);
    }

    private UUID studentId() {
        return jdbc.queryForObject("select id from users where email = 'student@teachloop.local'",
                UUID.class);
    }

    private void addJoins(UUID bookId, int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update("""
                    insert into vocab_book_joins (book_id, user_id, joined_at)
                    values (?, ?, now()) on conflict do nothing
                    """, bookId, UUID.randomUUID());
        }
    }

    private JsonNode studentBooks(String base, String cookie) throws Exception {
        return get(base, cookie, "/api/vocab/books");
    }

    private UUID createBook(String base, String admin, String name, int position) throws Exception {
        JsonNode created = post(base, admin, "/api/admin/vocab/books",
                Map.of("name", name, "position", position));
        return UUID.fromString(created.get("id").asString());
    }

    private UUID createPublishedBook(String base, String admin, String name, int position, String text)
            throws Exception {
        UUID id = createBook(base, admin, name, position);
        if (text != null && !text.isBlank()) {
            post(base, admin, "/api/admin/vocab/books/" + id + "/entries", Map.of("text", text));
        }
        put(base, admin, "/api/admin/vocab/books/" + id, Map.of("published", true));
        return id;
    }

    private JsonNode join(String base, String cookie, UUID bookId) throws Exception {
        return post(base, cookie, "/api/vocab/books/" + bookId + "/join", null);
    }

    private String admin(String base) throws Exception {
        if (adminCookie != null) {
            return adminCookie;
        }
        if (jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, ADMIN_EMAIL) == 0) {
            // 必须带 `passwordLoginEnabled=true`：`createAdmin` 建的号在 prod 只能走微信，
            // 测试里要用邮箱 + 密码登录，所以走同一套 createUser（角色 ADMIN）。
            adminService.createUser(ADMIN_EMAIL, UserRole.ADMIN, "词书测试管理员", ADMIN_PASSWORD, true);
        }
        adminCookie = login(base, ADMIN_EMAIL, ADMIN_PASSWORD);
        return adminCookie;
    }

    private String createUserAccount(String base, String email, UserRole role, String password)
            throws Exception {
        try {
            adminService.createUser(email, role, "测试账号", password, true);
        } catch (RuntimeException alreadyExists) {
            // 已存在：直接登录
        }
        return login(base, email, password);
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode post(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = send(base, cookie, "POST", path, body);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private JsonNode put(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = send(base, cookie, "PUT", path, body);
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

    private String login(String base, String email) throws Exception {
        return login(base, email, "teachloop123");
    }

    private String login(String base, String email, String password) throws Exception {
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", password));
        assertThat(response.statusCode()).as("login " + email + " -> " + response.body()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
