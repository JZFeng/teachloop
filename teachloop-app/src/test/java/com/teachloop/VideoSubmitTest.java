package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T062：短视频真实上传（ffprobe 通过）；非法扩展名拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class VideoSubmitTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("T062：1 秒 mp4 上传成功；.avi 被拒绝")
    void uploadsVideo() throws Exception {
        Path video = Files.createTempFile("teachloop-test-", ".mp4");
        Process ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i",
                "color=c=black:s=320x240:d=1", "-pix_fmt", "yuv420p", video.toString())
                .redirectErrorStream(true).start();
        assertThat(ffmpeg.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(ffmpeg.exitValue()).isZero();
        byte[] bytes = Files.readAllBytes(video);

        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String teacher = login(base);
        String student = loginAs(base, "student@teachloop.local");
        String paperId = post(base, teacher, "/api/papers", Map.of("title", "视频卷"), "id");
        String sectionId = post(base, teacher, "/api/papers/" + paperId + "/sections",
                Map.of("title", "写作", "pointsPerItem", 10), "id");
        post(base, teacher, "/api/papers/" + paperId + "/items", Map.of("number", 1,
                "questionType", "ESSAY", "stem", "口述作文", "points", 10, "paperSectionId", sectionId), "id");
        post(base, teacher, "/api/papers/" + paperId + "/publish", Map.of(), "id");
        String classId = post(base, teacher, "/api/classes", Map.of("name", "视频班"), "id");
        List<?> classes = Json.parseList(request(base, teacher, "GET", "/api/classes", null).body());
        String invite = ((Map<?, ?>) classes.get(0)).get("inviteCode").toString();
        request(base, student, "POST", "/api/classes/join", Map.of("inviteCode", invite));
        String offeringId = post(base, teacher, "/api/offerings", Map.of("paperId", paperId, "kind", "ASSIGNMENT",
                "timingMode", "INDIVIDUAL", "dueAt", "2026-12-31T23:59:59Z", "durationMinutes", 60,
                "classes", List.of(Map.of("classId", classId))), "id");
        post(base, teacher, "/api/offerings/" + offeringId + "/publish", Map.of(), "id");

        String boundary = "----teachloop" + UUID.randomUUID();
        byte[] body = multipart(boundary, "video.mp4", "video/mp4", bytes);
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(
                        base + "/api/offerings/" + offeringId + "/submissions/video"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", student)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).withFailMessage(res.body()).isEqualTo(200);
        assertThat(res.body()).contains("video.mp4");

        byte[] avi = multipart(boundary, "video.avi", "video/x-msvideo", bytes);
        HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(URI.create(
                        base + "/api/offerings/" + offeringId + "/submissions/video"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", student)
                .POST(HttpRequest.BodyPublishers.ofByteArray(avi)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(400);
        Files.deleteIfExists(video);
    }

    private byte[] multipart(String boundary, String name, String mime, byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String login(String base) throws Exception {
        return loginAs(base, "teacher@teachloop.local");
    }

    private String loginAs(String base, String email) throws Exception {
        HttpResponse<String> res = request(base, null, "POST", "/api/auth/login",
                Map.of("email", email, "password", "teachloop123"));
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().allValues("set-cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((a, b) -> b).orElseThrow();
    }

    private String post(String base, String cookie, String path, Object body, String field) throws Exception {
        Map<String, Object> parsed = Json.parse(request(base, cookie, "POST", path, body).body());
        return parsed.get(field).toString();
    }

    private HttpResponse<String> request(String base, String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (cookie != null) b.header("Cookie", cookie);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) { return MAPPER.writeValueAsString(o); }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) { return MAPPER.readValue(s, Map.class); }

        static List<?> parseList(String s) { return MAPPER.readValue(s, List.class); }
    }
}
