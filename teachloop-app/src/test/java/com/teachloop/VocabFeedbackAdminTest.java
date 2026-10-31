package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.admin.AdminService;
import com.teachloop.identity.domain.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 2026-10-31「管理员查看单词反馈」验收：`/api/admin/vocab/feedback`。
 *
 * <p>来由：查词页的「反馈」一直写 `vocab_feedback`（`VocabularyWordDetailEndpoint:188`），
 * 但全仓没有读取方——学生提交后没人看得到。这次开放给管理员，这里锁住四件事：
 * <ol>
 *   <li>ADMIN 列表能看见**真实提交**的那条（走真实学生端点提交，不是直接插表）；</li>
 *   <li>缺省筛选是 OPEN，改状态后从 OPEN 消失、在 RESOLVED / ALL 里出现；</li>
 *   <li>非法状态 400、不存在的反馈 404；</li>
 *   <li>教师与学生都是 403（读和写都验）。</li>
 * </ol>
 *
 * <p>断言全用真实数字/真实 id：`submitterId` 与学生的 `/api/auth/me` 相等、`wordForm` 与
 * 加入时的词形相等、`total` 与列表长度对得上——不写「大致正确」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false"
        })
@Testcontainers
class VocabFeedbackAdminTest {

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
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_EMAIL = "feedback-admin@teachloop.local";
    private static final String ADMIN_PASSWORD = "feedback-admin-pass-123";

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdminService adminService;

    private final HttpClient client = HttpClient.newHttpClient();
    private String adminCookie;

    @Test
    @DisplayName("ADMIN：看得到学生刚提交的反馈（OPEN 缺省）、按时间倒序、改 RESOLVED 生效、非法 400 / 不存在 404")
    void adminListsAndResolvesFeedback() throws Exception {
        String base = base();
        String student = login(base, "student@teachloop.local");
        UUID studentId = me(base, student);
        String admin = admin(base);

        // ① 走真实端点提交两条：先 A 后 B（created_at 相同也按 id 倒序，顺序是确定的）。
        String formA = "fdbk" + UUID.randomUUID().toString().substring(0, 8);
        String formB = formA + "b";
        UUID wordA = addWord(base, student, formA);
        UUID wordB = addWord(base, student, formB);
        long feedbackA = submitFeedback(base, student, wordA, "释义把词性写错了");
        long feedbackB = submitFeedback(base, student, wordB, "例句里的翻译和释义对不上");

        // ② 缺省 = OPEN：两条都在，且按 created_at desc / id desc 排在前面
        JsonNode open = get(base, admin, "/api/admin/vocab/feedback?size=200");
        assertThat(open.get("total").asLong()).isGreaterThanOrEqualTo(2);
        JsonNode itemB = item(open, feedbackB);
        assertThat(itemB.get("wordForm").asString()).isEqualTo(formB);
        assertThat(itemB.get("wordLanguage").asString()).isEqualTo("en");
        assertThat(itemB.get("note").asString()).isEqualTo("例句里的翻译和释义对不上");
        assertThat(itemB.get("status").asString()).isEqualTo("OPEN");
        // 跨模块纪律：提交人只回 user id，与学生的 /api/auth/me 一致
        assertThat(itemB.get("submitterId").asString()).isEqualTo(studentId.toString());
        assertThat(itemB.get("createdAt").isNull()).isFalse();
        // 排序：后提交的在前面
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : open.get("items")) {
            long id = node.get("id").asLong();
            if (id == feedbackA || id == feedbackB) {
                ids.add(id);
            }
        }
        assertThat(ids).containsExactly(feedbackB, feedbackA);

        // ③ 改状态：RETURNED 的那一行就是落库后的真值
        JsonNode resolved = postJson(base, admin,
                "/api/admin/vocab/feedback/" + feedbackB + "/status", Map.of("status", "RESOLVED"));
        assertThat(resolved.get("id").asLong()).isEqualTo(feedbackB);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select status from vocab_feedback where id = ?",
                String.class, feedbackB)).isEqualTo("RESOLVED");

        // ④ 筛选跟着走：OPEN 里没了、RESOLVED 里有、ALL 里也有
        assertThat(allIds(get(base, admin, "/api/admin/vocab/feedback?size=200"))).doesNotContain(feedbackB);
        assertThat(allIds(get(base, admin, "/api/admin/vocab/feedback?status=OPEN&size=200")))
                .doesNotContain(feedbackB);
        assertThat(allIds(get(base, admin, "/api/admin/vocab/feedback?status=RESOLVED&size=200")))
                .contains(feedbackB);
        assertThat(allIds(get(base, admin, "/api/admin/vocab/feedback?status=ALL&size=200")))
                .contains(feedbackB, feedbackA);

        // ⑤ 重开：回到 OPEN
        JsonNode reopened = postJson(base, admin,
                "/api/admin/vocab/feedback/" + feedbackB + "/status", Map.of("status", "OPEN"));
        assertThat(reopened.get("status").asString()).isEqualTo("OPEN");

        // ⑥ 非法状态 400；不存在的反馈 404
        assertThat(post(base, admin, "/api/admin/vocab/feedback/" + feedbackB + "/status",
                Map.of("status", "DONE")).statusCode()).isEqualTo(400);
        assertThat(post(base, admin, "/api/admin/vocab/feedback/" + feedbackB + "/status",
                Map.of("status", "ALL")).statusCode()).isEqualTo(400);
        assertThat(send(base, admin, "GET", "/api/admin/vocab/feedback?status=NOPE", null).statusCode())
                .isEqualTo(400);
        assertThat(post(base, admin, "/api/admin/vocab/feedback/999999999/status",
                Map.of("status", "RESOLVED")).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("非 ADMIN（教师 / 学生）：列表与改状态一律 403")
    void nonAdminGets403() throws Exception {
        String base = base();
        String student = login(base, "student@teachloop.local");
        String teacher = login(base, "teacher@teachloop.local");
        String admin = admin(base);

        // 先造一条真实反馈，确保 403 不是因为「没有数据」
        String form = "fdbk" + UUID.randomUUID().toString().substring(0, 8);
        UUID wordId = addWord(base, student, form);
        long feedbackId = submitFeedback(base, student, wordId, "学生对这条很有意见");

        for (String cookie : List.of(student, teacher)) {
            assertThat(send(base, cookie, "GET", "/api/admin/vocab/feedback", null).statusCode())
                    .isEqualTo(403);
            assertThat(post(base, cookie, "/api/admin/vocab/feedback/" + feedbackId + "/status",
                    Map.of("status", "RESOLVED")).statusCode()).isEqualTo(403);
        }

        // 403 之后数据没被改：管理员看还是 OPEN
        assertThat(item(get(base, admin, "/api/admin/vocab/feedback?size=200"), feedbackId)
                .get("status").asString()).isEqualTo("OPEN");
    }

    // ────────────────────────────────────────────────────────────────
    // 真实端点 / 登录 / 发送
    // ────────────────────────────────────────────────────────────────

    private UUID addWord(String base, String cookie, String form) throws Exception {
        JsonNode added = MAPPER.readTree(post(base, cookie, "/api/vocab/words",
                Map.of("form", form, "language", "en")).body());
        assertThat(added.get("created").asInt()).isEqualTo(1);
        return UUID.fromString(added.get("id").asString());
    }

    private long submitFeedback(String base, String cookie, UUID wordId, String note) throws Exception {
        JsonNode feedback = MAPPER.readTree(post(base, cookie,
                "/api/vocab/words/" + wordId + "/feedback", Map.of("note", note)).body());
        assertThat(feedback.get("id").asLong()).isPositive();
        return feedback.get("id").asLong();
    }

    /** 在列表里按 id 找那一行（列表只回当前页，测试用 size=200 一次取完）。 */
    private static JsonNode item(JsonNode page, long id) {
        for (JsonNode node : page.get("items")) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        throw new AssertionError("列表里没有 id=" + id + " 的反馈：" + page);
    }

    private static List<Long> allIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : page.get("items")) {
            ids.add(node.get("id").asLong());
        }
        return ids;
    }

    /** 管理员号在容器里不存在时用 adminService 建一个（带密码登录，与 VocabBooksTest 同口径）。 */
    private String admin(String base) throws Exception {
        if (adminCookie != null) {
            return adminCookie;
        }
        if (jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, ADMIN_EMAIL) == 0) {
            adminService.createUser(ADMIN_EMAIL, UserRole.ADMIN, "反馈测试管理员", ADMIN_PASSWORD, true);
        }
        adminCookie = login(base, ADMIN_EMAIL, ADMIN_PASSWORD);
        return adminCookie;
    }

    private UUID me(String base, String cookie) throws Exception {
        return UUID.fromString(MAPPER.readTree(send(base, cookie, "GET", "/api/auth/me", null).body())
                .get("id").asString());
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
    }

    private JsonNode get(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "GET", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> post(String base, String cookie, String path, Object body) throws Exception {
        return send(base, cookie, "POST", path, body);
    }

    /** 期望 200 的 POST：直接解析成 JSON，失败时把响应体打进断言消息。 */
    private JsonNode postJson(String base, String cookie, String path, Object body) throws Exception {
        HttpResponse<String> response = post(base, cookie, path, body);
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
