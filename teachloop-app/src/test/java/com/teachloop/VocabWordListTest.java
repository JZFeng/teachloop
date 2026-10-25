package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
 * T449 / T450 验收：学生词单（US-69）。
 *
 * <p>覆盖：词单 CRUD、重名 409（大小写不敏感）、上限 409、他人词单 404、重复加入幂等、
 * 删单不动词条与进度、软删词后引用保留（恢复后仍在原词单）、移出只删引用；
 * 以及 {@code GET /api/vocab/words?list=} 与「一个词进 3 份词单、复习一轮后进度仍 1 行」。
 *
 * <p>上限配置压到 3（`teachloop.vocab.lists.max-per-student=3`）才能在测试里真撞线；
 * 每个测试方法开头先清空该学生已有词单，避免方法间顺序耦合。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "teachloop.vocab.enabled=true",
                "teachloop.vocab.lexicon.enabled=false",
                "teachloop.vocab.generation.enabled=false",
                "teachloop.vocab.lists.max-per-student=3"
        })
@Testcontainers
class VocabWordListTest {

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

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("T449 词单 CRUD：重名 409 / 上限 409 / 他人 404 / 重复加入幂等 / 删单与移出不动词条与进度")
    void listsCrudAndMembership() throws Exception {
        String base = base();
        String student = login(base, "student@teachloop.local");
        String teacher = login(base, "teacher@teachloop.local");
        wipeLists(base, student);

        // ---- 创建：名字/描述/排序位；空词单 wordCount = 0 ----
        JsonNode story = post(base, student, "/api/vocab/lists",
                Map.of("name", "Story Words", "description", "小说里反复出现的生词"));
        String storyId = story.get("id").asString();
        assertThat(story.get("name").asString()).isEqualTo("Story Words");
        assertThat(story.get("description").asString()).isEqualTo("小说里反复出现的生词");
        assertThat(story.get("wordCount").asInt()).isZero();

        String examId = post(base, student, "/api/vocab/lists", Map.of("name", "考研核心词"))
                .get("id").asString();
        String thirdId = post(base, student, "/api/vocab/lists", Map.of("name", "阅读生词"))
                .get("id").asString();
        assertThat(thirdId).isNotBlank();

        // ---- 重名 409（大小写不敏感，AC-69.1）----
        HttpResponse<String> duplicateName = send(base, student, "POST", "/api/vocab/lists",
                Map.of("name", "story words"));
        assertThat(duplicateName.statusCode()).as("大小写不同也算重名").isEqualTo(409);

        // ---- 上限 409（AC-69.4：已有词单不受影响）----
        HttpResponse<String> overLimit = send(base, student, "POST", "/api/vocab/lists",
                Map.of("name", "第四份词单"));
        assertThat(overLimit.statusCode()).isEqualTo(409);
        assertThat(overLimit.body()).contains("limit");
        assertThat(get(base, student, "/api/vocab/lists")).hasSize(3);

        // ---- 加词（forms 先入库再引用）----
        JsonNode added = post(base, student, "/api/vocab/lists/" + storyId + "/words",
                Map.of("forms", List.of("listalpha", "listbeta")));
        assertThat(added.get("added").asInt()).isEqualTo(2);
        assertThat(added.get("duplicated").asInt()).isZero();
        assertThat(count("select count(*) from vocab_words where normalized_form in ('listalpha','listbeta')"
                + " and deleted = false")).isEqualTo(2);
        assertThat(count("select count(*) from vocab_progress p join vocab_words w on w.id = p.word_id"
                + " where w.normalized_form in ('listalpha','listbeta')")).isEqualTo(2);

        String alphaId = wordId("listalpha");
        String betaId = wordId("listbeta");
        assertThat(alphaId).isNotBlank();
        assertThat(betaId).isNotBlank();

        // ---- 重复加入幂等（AC-69.2）：不报错、不重复计数、行数不变 ----
        JsonNode again = post(base, student, "/api/vocab/lists/" + storyId + "/words",
                Map.of("forms", List.of("listalpha", "listbeta")));
        assertThat(again.get("added").asInt()).isZero();
        assertThat(again.get("duplicated").asInt()).isEqualTo(2);
        JsonNode againById = post(base, student, "/api/vocab/lists/" + storyId + "/words",
                Map.of("wordIds", List.of(UUID.fromString(alphaId))));
        assertThat(againById.get("added").asInt()).isZero();
        assertThat(againById.get("duplicated").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from vocab_list_items where list_id = '"
                + storyId + "'::uuid")).isEqualTo(2);

        // ---- 加别人的词 404（不泄漏存在性）----
        HttpResponse<String> foreignWord = send(base, student, "POST",
                "/api/vocab/lists/" + storyId + "/words",
                Map.of("wordIds", List.of(UUID.randomUUID())));
        assertThat(foreignWord.statusCode()).isEqualTo(404);

        // ---- 列表与详情 ----
        JsonNode lists = get(base, student, "/api/vocab/lists");
        assertThat(lists).hasSize(3);
        assertThat(lists.get(0).get("name").asString()).as("顺序 = 创建顺序").isEqualTo("Story Words");
        assertThat(lists.get(0).get("wordCount").asInt()).isEqualTo(2);

        JsonNode words = get(base, student, "/api/vocab/lists/" + storyId + "/words?page=1&size=20");
        assertThat(words.get("total").asInt()).isEqualTo(2);
        assertThat(words.get("items")).hasSize(2);
        assertThat(words.get("items").get(0).get("form").asString())
                .as("顺序 = 加入顺序").isEqualTo("listalpha");
        assertThat(words.get("items").get(1).get("form").asString()).isEqualTo("listbeta");
        assertThat(words.get("items").get(0).get("state").asString()).isEqualTo("NEW");
        assertThat(words.get("items").get(0).get("deleted").asBoolean()).isFalse();

        // ---- 改名：与他人词单重名 409；改自己的成功 ----
        HttpResponse<String> renameConflict = send(base, student, "PUT", "/api/vocab/lists/" + storyId,
                Map.of("name", "考研核心词"));
        assertThat(renameConflict.statusCode()).isEqualTo(409);
        JsonNode renamed = put(base, student, "/api/vocab/lists/" + storyId,
                Map.of("name", "小说生词本", "description", "2026 秋读的小说"));
        assertThat(renamed.get("name").asString()).isEqualTo("小说生词本");
        assertThat(renamed.get("description").asString()).isEqualTo("2026 秋读的小说");

        // ---- 他人词单一律 404（AC-69.9）----
        assertThat(send(base, teacher, "GET", "/api/vocab/lists/" + storyId, null).statusCode())
                .isEqualTo(404);
        assertThat(send(base, teacher, "PUT", "/api/vocab/lists/" + storyId,
                Map.of("name", "偷改")).statusCode()).isEqualTo(404);
        assertThat(send(base, teacher, "DELETE", "/api/vocab/lists/" + storyId, null).statusCode())
                .isEqualTo(404);
        assertThat(send(base, teacher, "GET", "/api/vocab/lists/" + storyId + "/words", null)
                .statusCode()).isEqualTo(404);
        assertThat(send(base, teacher, "GET", "/api/vocab/lists/" + storyId + "/review-words", null)
                .statusCode()).isEqualTo(404);
        assertThat(send(base, teacher, "POST", "/api/vocab/lists/" + storyId + "/words",
                Map.of("forms", List.of("stolen"))).statusCode()).isEqualTo(404);
        assertThat(send(base, teacher, "DELETE",
                "/api/vocab/lists/" + storyId + "/words/" + alphaId, null).statusCode()).isEqualTo(404);

        // ---- 软删词单：只删容器，词条 / 进度 / 引用行都在（AC-69.3）----
        post(base, student, "/api/vocab/lists/" + examId + "/words",
                Map.of("forms", List.of("listgamma")));
        int wordsBefore = count("select count(*) from vocab_words where deleted = false");
        int progressBefore = count("select count(*) from vocab_progress");
        String wordsContentBefore = jdbc.queryForObject(
                "select string_agg(form, ',' order by form) from vocab_words where deleted = false",
                String.class);
        int itemsBefore = count("select count(*) from vocab_list_items where list_id = '"
                + examId + "'::uuid");

        JsonNode deleted = delete(base, student, "/api/vocab/lists/" + examId);
        assertThat(deleted.get("affected").asInt()).isEqualTo(1);
        assertThat(get(base, student, "/api/vocab/lists")).hasSize(2);
        assertThat(count("select count(*) from vocab_lists where id = '" + examId
                + "'::uuid and deleted = true")).isEqualTo(1);
        assertThat(count("select count(*) from vocab_words where deleted = false")).isEqualTo(wordsBefore);
        assertThat(count("select count(*) from vocab_progress")).isEqualTo(progressBefore);
        assertThat(jdbc.queryForObject(
                "select string_agg(form, ',' order by form) from vocab_words where deleted = false",
                String.class)).isEqualTo(wordsContentBefore);
        assertThat(count("select count(*) from vocab_list_items where list_id = '"
                + examId + "'::uuid")).as("软删不动引用行").isEqualTo(itemsBefore);
        // 软删后查看 / 加词都按 404 处理
        assertThat(send(base, student, "GET", "/api/vocab/lists/" + examId, null).statusCode())
                .isEqualTo(404);

        // ---- 软删词：引用保留，恢复后仍在原词单（AC-69.3 / AC-55）----
        assertThat(delete(base, student, "/api/vocab/words/" + alphaId).get("affected").asInt())
                .isEqualTo(1);
        JsonNode wordsAfterDelete = get(base, student, "/api/vocab/lists/" + storyId + "/words");
        assertThat(wordsAfterDelete.get("total").asInt()).as("引用行保留").isEqualTo(2);
        assertThat(wordsAfterDelete.get("items").get(0).get("deleted").asBoolean()).isTrue();
        JsonNode scopeAfterDelete = get(base, student, "/api/vocab/lists/" + storyId + "/review-words");
        assertThat(scopeAfterDelete.get("reviewable").asInt()).isEqualTo(1);
        assertThat(scopeAfterDelete.get("deleted").asInt()).isEqualTo(1);
        assertThat(scopeAfterDelete.get("wordIds")).hasSize(1);
        assertThat(scopeAfterDelete.get("sessionLimit").asInt()).isEqualTo(1000);

        post(base, student, "/api/vocab/words", Map.of("form", "listalpha"));
        JsonNode scopeAfterRestore = get(base, student, "/api/vocab/lists/" + storyId + "/review-words");
        assertThat(scopeAfterRestore.get("reviewable").asInt()).as("恢复后回到复习范围").isEqualTo(2);
        assertThat(count("select count(*) from vocab_list_items where list_id = '"
                + storyId + "'::uuid")).isEqualTo(2);

        // ---- 移出单个词：只删引用，不动词条与进度（AC-69.6）----
        int wordsBeforeRemove = count("select count(*) from vocab_words where deleted = false");
        int progressBeforeRemove = count("select count(*) from vocab_progress");
        JsonNode removed = delete(base, student, "/api/vocab/lists/" + storyId + "/words/" + betaId);
        assertThat(removed.get("affected").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from vocab_words where deleted = false"))
                .isEqualTo(wordsBeforeRemove);
        assertThat(count("select count(*) from vocab_progress")).isEqualTo(progressBeforeRemove);
        assertThat(get(base, student, "/api/vocab/lists/" + storyId + "/words").get("total").asInt())
                .isEqualTo(1);
        assertThat(send(base, student, "DELETE",
                "/api/vocab/lists/" + storyId + "/words/" + betaId, null).statusCode())
                .as("已移出的词再移出 404").isEqualTo(404);
    }

    @Test
    @DisplayName("T450 词库 ?list= 筛选：与他人筛选取交集、他人词单 404；一个词进 3 份词单复习后进度仍 1 行")
    void queryByListAndSingleProgress() throws Exception {
        String base = base();
        String student = login(base, "student@teachloop.local");
        String teacher = login(base, "teacher@teachloop.local");
        wipeLists(base, student);
        wipeLists(base, teacher);

        String sharedWord = "listshared" + Instant.now().toEpochMilli();
        String onlyInFirst = "listonly" + Instant.now().toEpochMilli();

        List<String> listIds = new ArrayList<>();
        for (String name : List.of("词单一", "词单二", "词单三")) {
            listIds.add(post(base, student, "/api/vocab/lists", Map.of("name", name)).get("id").asString());
        }
        // 一个词进 3 份词单（AC-69.2）
        for (String listId : listIds) {
            JsonNode result = post(base, student, "/api/vocab/lists/" + listId + "/words",
                    Map.of("forms", List.of(sharedWord)));
            assertThat(result.get("added").asInt()).isEqualTo(1);
        }
        post(base, student, "/api/vocab/lists/" + listIds.get(0) + "/words",
                Map.of("forms", List.of(onlyInFirst)));
        assertThat(count("select count(*) from vocab_list_items i join vocab_words w on w.id = i.word_id"
                + " where w.normalized_form = '" + sharedWord + "'"))
                .as("同一个词有 3 条引用").isEqualTo(3);
        assertThat(count("select count(*) from vocab_words where normalized_form = '" + sharedWord + "'"))
                .as("词条仍只有一份").isEqualTo(1);

        // ---- ?list= 只返回该词单的词 ----
        JsonNode viaList = get(base, student, "/api/vocab/words?list=" + listIds.get(0) + "&size=100");
        assertThat(viaList.get("total").asInt()).isEqualTo(2);
        assertThat(formsOf(viaList)).containsExactlyInAnyOrder(sharedWord, onlyInFirst);

        // ---- ?list= 与其他筛选取交集（来源 / 状态 / 收藏 / 搜索）----
        JsonNode intersectState = get(base, student,
                "/api/vocab/words?list=" + listIds.get(0) + "&state=NEW&size=100");
        assertThat(intersectState.get("total").asInt()).isEqualTo(2);
        JsonNode intersectSearch = get(base, student,
                "/api/vocab/words?list=" + listIds.get(0) + "&q=" + onlyInFirst + "&size=100");
        assertThat(formsOf(intersectSearch)).containsExactly(onlyInFirst);
        JsonNode intersectSource = get(base, student,
                "/api/vocab/words?list=" + listIds.get(0) + "&source=SELF&size=100");
        assertThat(formsOf(intersectSource)).isEmpty();

        // ---- 可重复参数：多份词单取并集（?list=a&list=b）----
        JsonNode bothLists = get(base, student,
                "/api/vocab/words?list=" + listIds.get(0) + "&list=" + listIds.get(1) + "&size=100");
        assertThat(bothLists.get("total").asInt()).isEqualTo(2);

        // ---- 他人词单 404（不静默忽略）----
        String teacherListId = post(base, teacher, "/api/vocab/lists", Map.of("name", "老师的词单"))
                .get("id").asString();
        assertThat(send(base, student, "GET", "/api/vocab/words?list=" + teacherListId, null)
                .statusCode()).isEqualTo(404);
        assertThat(send(base, student, "GET",
                "/api/vocab/words?list=" + listIds.get(0) + "&list=" + teacherListId, null)
                .statusCode()).isEqualTo(404);
        // 不存在的词单同样 404
        assertThat(send(base, student, "GET", "/api/vocab/words?list=" + UUID.randomUUID(), null)
                .statusCode()).isEqualTo(404);

        // ---- 复习一轮：从一份词单取词 → 既有会话 → 评分；进度仍 1 行、due_at 只更新一次 ----
        JsonNode scope = get(base, student, "/api/vocab/lists/" + listIds.get(0) + "/review-words");
        assertThat(scope.get("reviewable").asInt()).isEqualTo(2);
        JsonNode session = post(base, student, "/api/vocab/review-sessions",
                Map.of("wordIds", List.of(UUID.fromString(wordId(sharedWord))), "fromStart", true));
        String sessionId = session.get("id").asString();
        String dueBefore = jdbc.queryForObject(
                "select due_at::text from vocab_progress where word_id = '" + wordId(sharedWord) + "'::uuid",
                String.class);
        JsonNode submitted = post(base, student, "/api/vocab/review",
                Map.of("wordId", wordId(sharedWord), "rating", "KNOWN", "sessionId", sessionId));
        assertThat(submitted.get("state").asString()).isEqualTo("REVIEW");

        assertThat(count("select count(*) from vocab_progress p join vocab_words w on w.id = p.word_id"
                + " where w.normalized_form = '" + sharedWord + "'"))
                .as("一个词进 3 份词单，进度仍只有 1 行").isEqualTo(1);
        assertThat(count("select count(*) from vocab_review_logs l join vocab_words w on w.id = l.word_id"
                + " where w.normalized_form = '" + sharedWord + "'"))
                .as("复习一轮只写 1 条日志").isEqualTo(1);
        assertThat(jdbc.queryForObject("select reviewed_count from vocab_progress where word_id = '"
                + wordId(sharedWord) + "'::uuid", Integer.class)).isEqualTo(1);
        String dueAfter = jdbc.queryForObject(
                "select due_at::text from vocab_progress where word_id = '" + wordId(sharedWord) + "'::uuid",
                String.class);
        assertThat(dueAfter).as("due_at 只更新一次").isNotEqualTo(dueBefore);
        Long dueEpochMillis = jdbc.queryForObject(
                "select (extract(epoch from due_at) * 1000)::bigint from vocab_progress"
                        + " where word_id = '" + wordId(sharedWord) + "'::uuid", Long.class);
        assertThat(dueEpochMillis)
                .as("库存的 due_at 就是这次评分算出来的那一个")
                .isEqualTo(Instant.parse(submitted.get("dueAt").asString()).toEpochMilli());
        // 引用行不因复习而变多或变少：3 份词单各 1 条
        assertThat(count("select count(*) from vocab_list_items i join vocab_words w on w.id = i.word_id"
                + " where w.normalized_form = '" + sharedWord + "'")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

    private String wordId(String normalizedForm) {
        return jdbc.queryForObject(
                "select id::text from vocab_words where normalized_form = ? order by created_at desc limit 1",
                String.class, normalizedForm);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private List<String> formsOf(JsonNode page) {
        List<String> forms = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            forms.add(item.get("form").asString());
        }
        return forms;
    }

    private void wipeLists(String base, String cookie) throws Exception {
        for (JsonNode row : get(base, cookie, "/api/vocab/lists")) {
            delete(base, cookie, "/api/vocab/lists/" + row.get("id").asString());
        }
    }

    private String base() {
        return "http://127.0.0.1:" + env.getProperty("local.server.port");
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

    private JsonNode delete(String base, String cookie, String path) throws Exception {
        HttpResponse<String> response = send(base, cookie, "DELETE", path, null);
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> send(String base, String cookie, String method, String path,
                                      Object body) throws Exception {
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
        HttpResponse<String> response = send(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
