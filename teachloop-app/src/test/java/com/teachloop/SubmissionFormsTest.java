package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T060 / T061 / T066 / T067 验收：富文本白名单消毒 + 纯文本提取、文件上传解析
 * （txt/md/pdf/docx）、.doc 拒绝、unreadable 不评分、附件下载权限。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SubmissionFormsTest {

    private static final String PASSWORD = "teachloop123";
    private static final String OTHER_STUDENT = "forms-other@teachloop.local";

    private static final Path STORAGE_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("teachloop-uploads-");
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("teachloop.storage.local-dir", STORAGE_DIR::toString);
        // 后台作业与本测试无关，调远避免噪声
        r.add("teachloop.grading.poll-ms", () -> "3600000");
        r.add("teachloop.grading.dispatch-ms", () -> "3600000");
        r.add("teachloop.grading.sweep-ms", () -> "3600000");
        r.add("teachloop.exam.scan-ms", () -> "3600000");
    }

    @Autowired
    Environment env;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    private final HttpClient client = HttpClient.newHttpClient();

    private String base;
    private String teacher;
    private String student;

    @BeforeEach
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        teacher = login("teacher@teachloop.local");
        student = login("student@teachloop.local");
    }

    // ================= T060：富文本 =================

    @Test
    @DisplayName("T060：富文本脚本/属性被剥离、允许格式保留、content_text 提取；自动保存与交卷都生效")
    void richTextIsSanitizedAndPlainTextExtracted() throws Exception {
        String classId = createClass("富文本班");
        String invite = classInvite(classId);
        join(student, invite);
        String offeringId = createOffering(classId);
        String submissionId = send(student, "POST", "/api/offerings/" + offeringId + "/start", Map.of())
                .get("submissionId").toString();

        String dangerous = "<h2>Title</h2>"
                + "<p>Hello <strong>bold</strong> <em>italic</em> <u>under</u></p>"
                + "<script>alert('xss')</script>"
                + "<p onclick=\"steal()\">Second</p>"
                + "<ul><li>one</li><li>two</li></ul>"
                + "<a href=\"https://evil.example\">link text</a>";
        send(student, "PUT", "/api/offerings/" + offeringId + "/answers",
                Map.of("contentHtml", dangerous, "answers", List.of()));

        Map<String, Object> saved = jdbc.queryForMap(
                "select content_html as html, content_text as text, input_kind as kind "
                        + "from submissions where id = ?",
                UUID.fromString(submissionId));
        String html = (String) saved.get("html");
        String text = (String) saved.get("text");
        assertThat(saved.get("kind")).as("富文本形态").isEqualTo("RICH_TEXT");
        assertThat(html).as("允许的格式保留").contains("<h2>", "<strong>", "<em>", "<u>", "<ul>", "<li>");
        assertThat(html).as("脚本与事件属性被丢弃")
                .doesNotContain("<script", "alert(", "onclick", "steal()", "href", "<a ");
        assertThat(text).contains("Hello bold italic under", "Second", "one", "two");
        assertThat(text).as("脚本内容不进纯文本").doesNotContain("alert");
        assertThat(richTextAuditCount(submissionId)).isEqualTo(1);

        // 交卷同样走消毒：这里全部是白名单标签，不应再产生 security 事件
        Map<String, Object> submitted = send(student, "POST", "/api/offerings/" + offeringId + "/submissions",
                Map.of("contentHtml", "<p>Final <strong>answer</strong></p>"));
        assertThat(submitted.get("gradingState")).isEqualTo("QUEUED");
        assertThat(richTextAuditCount(submissionId)).isEqualTo(1);
        Map<String, Object> afterSubmit = jdbc.queryForMap(
                "select content_html as html, content_text as text from submissions where id = ?",
                UUID.fromString(submissionId));
        assertThat((String) afterSubmit.get("text")).isEqualTo("Final answer");
    }

    // ================= T061 / T066：文件提交 =================

    @Test
    @DisplayName("T061：txt / md / pdf / docx 都能解析成纯文本并进入批改队列")
    void supportedFileTypesAreExtracted() throws Exception {
        String classId = createClass("文件班");
        String invite = classInvite(classId);
        join(student, invite);

        Map<String, Object> txt = uploadAndSubmit(classId, "essay.txt", "text/plain",
                "My essay about the environment.".getBytes(StandardCharsets.UTF_8));
        assertFileRow((String) txt.get("submissionId"), "essay.txt", "text/plain", false, "QUEUED");
        assertThat((String) txt.get("attachmentName")).isEqualTo("essay.txt");
        assertThat(textOf((String) txt.get("submissionId")))
                .contains("My essay about the environment.");
        assertThat(attachmentExists((String) txt.get("submissionId"))).isTrue();

        Map<String, Object> md = uploadAndSubmit(classId, "notes.md", "text/plain",
                "# Heading\n\nBody with **bold**.".getBytes(StandardCharsets.UTF_8));
        assertThat(textOf((String) md.get("submissionId"))).contains("Heading", "Body with");

        Map<String, Object> pdf = uploadAndSubmit(classId, "essay.pdf", "application/pdf",
                pdfWithText("PDF essay content"));
        assertFileRow((String) pdf.get("submissionId"), "essay.pdf", "application/pdf", false, "QUEUED");
        assertThat(textOf((String) pdf.get("submissionId"))).contains("PDF essay content");

        Map<String, Object> docx = uploadAndSubmit(classId, "essay.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxWithText("DOCX essay content"));
        assertThat(textOf((String) docx.get("submissionId"))).contains("DOCX essay content");
    }

    @Test
    @DisplayName("T061+T066：.doc 与不支持的扩展名被拒绝；解析失败/空文本标 unreadable 且不进批改队列")
    void rejectedAndUnreadableFiles() throws Exception {
        assertThat(env.getProperty("spring.servlet.multipart.max-file-size")).as("100 MB 上限").isEqualTo("100MB");
        String classId = createClass("坏文件班");
        String invite = classInvite(classId);
        join(student, invite);

        HttpResponse<String> doc = upload(classId, "old.doc", "application/msword", "legacy".getBytes());
        assertThat(doc.statusCode()).isEqualTo(400);
        assertThat(doc.body()).contains("docx");

        HttpResponse<String> jpg = upload(classId, "photo.jpg", "image/jpeg", new byte[] {1, 2, 3});
        assertThat(jpg.statusCode()).isEqualTo(400);

        // 无文本层 PDF：提取为空 → unreadable，不评 0 分
        Map<String, Object> blank = uploadAndSubmit(classId, "blank.pdf", "application/pdf", blankPdf());
        assertThat(blank.get("unreadable")).isEqualTo(true);
        assertUnreadable((String) blank.get("submissionId"));

        // 损坏的 PDF：解析抛错也收敛成 unreadable，而不是 500
        Map<String, Object> broken = uploadAndSubmit(classId, "broken.pdf", "application/pdf",
                "this is not a pdf".getBytes(StandardCharsets.UTF_8));
        assertThat(broken.get("unreadable")).isEqualTo(true);
        assertUnreadable((String) broken.get("submissionId"));

        // 0 字节 txt：空文本同样 unreadable
        Map<String, Object> empty = uploadAndSubmit(classId, "empty.txt", "text/plain", new byte[0]);
        assertThat(empty.get("unreadable")).isEqualTo(true);
        assertUnreadable((String) empty.get("submissionId"));
    }

    // ================= T061：附件下载权限 =================

    @Test
    @DisplayName("T061：本人与教师可下载附件，其他学生与非附件提交一律 404，Content-Disposition 带原名")
    void attachmentDownloadPermissions() throws Exception {
        String classId = createClass("下载班");
        String invite = classInvite(classId);
        join(student, invite);
        String other = createAndLoginOtherStudent();
        join(other, invite);

        Map<String, Object> uploaded = uploadAndSubmit(classId, "my-essay.txt", "text/plain",
                "Downloadable attachment body.".getBytes(StandardCharsets.UTF_8));
        String submissionId = (String) uploaded.get("submissionId");

        HttpResponse<byte[]> own = download(student, submissionId);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(new String(own.body(), StandardCharsets.UTF_8)).contains("Downloadable attachment body.");
        assertThat(own.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment", "my-essay.txt");

        assertThat(download(teacher, submissionId).statusCode()).as("教师可下载").isEqualTo(200);
        assertThat(download(other, submissionId).statusCode()).as("他人 404").isEqualTo(404);
        assertThat(download(student, UUID.randomUUID().toString()).statusCode()).as("不存在 404").isEqualTo(404);

        // 富文本提交没有附件 → 404
        String richOffering = createOffering(classId);
        String richSubmission = send(student, "POST", "/api/offerings/" + richOffering + "/start", Map.of())
                .get("submissionId").toString();
        send(student, "POST", "/api/offerings/" + richOffering + "/submissions",
                Map.of("contentText", "plain text submission"));
        assertThat(download(student, richSubmission).statusCode()).isEqualTo(404);
    }

    // ================= fixtures / helpers =================

    private void assertFileRow(String submissionId, String expectedName, String expectedMime,
                               boolean unreadable, String expectedState) {
        Map<String, Object> row = jdbc.queryForMap(
                "select input_kind as kind, attachment_name as name, attachment_mime as mime, "
                        + "unreadable as unreadable, grading_state as state from submissions where id = ?",
                UUID.fromString(submissionId));
        assertThat(row.get("kind")).isEqualTo("FILE");
        assertThat(row.get("name")).isEqualTo(expectedName);
        assertThat(row.get("mime")).isEqualTo(expectedMime);
        assertThat(row.get("unreadable")).isEqualTo(unreadable);
        assertThat(row.get("state")).isEqualTo(expectedState);
    }

    private void assertUnreadable(String submissionId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select input_kind as kind, attachment_name as name, attachment_mime as mime, "
                        + "unreadable as unreadable, grading_state as state, content_text as text, "
                        + "subjective_total as subjective from submissions where id = ?",
                UUID.fromString(submissionId));
        assertThat(row.get("kind")).isEqualTo("FILE");
        assertThat(row.get("name")).isNotNull();
        assertThat(row.get("mime")).isNotNull();
        assertThat(row.get("unreadable")).isEqualTo(true);
        assertThat(row.get("state")).as("unreadable 不进批改队列").isEqualTo("SKIPPED");
        assertThat(row.get("text")).isNull();
        // 不参与评分：不进队列、不是 DONE/FAILED，也不会被判成 0 分
        assertThat(((java.math.BigDecimal) row.get("subjective")).signum()).isZero();
        Integer queued = jdbc.queryForObject(
                "select count(*) from submissions where id = ? and grading_state in ('QUEUED','PENDING','RUNNING','DONE')",
                Integer.class, UUID.fromString(submissionId));
        assertThat(queued).isZero();
    }

    private String textOf(String submissionId) {
        return jdbc.queryForObject("select content_text from submissions where id = ?", String.class,
                UUID.fromString(submissionId));
    }

    private boolean attachmentExists(String submissionId) {
        String path = jdbc.queryForObject("select attachment_path from submissions where id = ?", String.class,
                UUID.fromString(submissionId));
        return path != null && Files.isRegularFile(STORAGE_DIR.resolve(path));
    }

    private int richTextAuditCount(String submissionId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'security:rich_text_stripped' and target_id = ?",
                Integer.class, submissionId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> uploadAndSubmit(String classId, String fileName, String contentType,
                                                byte[] bytes) throws Exception {
        String offeringId = createOffering(classId);
        send(student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        HttpResponse<String> res = uploadRaw(student, offeringId, fileName, contentType, bytes);
        assertThat(res.statusCode()).as("上传 %s -> %s %s", fileName, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> upload(String classId, String fileName, String contentType, byte[] bytes)
            throws Exception {
        String offeringId = createOffering(classId);
        send(student, "POST", "/api/offerings/" + offeringId + "/start", Map.of());
        return uploadRaw(student, offeringId, fileName, contentType, bytes);
    }

    private HttpResponse<String> uploadRaw(String cookie, String offeringId, String fileName,
                                           String contentType, byte[] bytes) throws Exception {
        String boundary = "----teachloop" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/api/offerings/" + offeringId + "/submissions/file"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> download(String cookie, String submissionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/api/submissions/" + submissionId + "/attachment"))
                .header("Cookie", cookie)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private String createClass(String name) throws Exception {
        return post("/api/classes", Map.of("name", name), "id");
    }

    private String classInvite(String classId) throws Exception {
        HttpResponse<String> res = raw(teacher, "GET", "/api/classes", null);
        assertThat(res.statusCode()).isEqualTo(200);
        for (Object o : Json.parseList(res.body())) {
            Map<?, ?> c = (Map<?, ?>) o;
            if (classId.equals(c.get("id"))) {
                return c.get("inviteCode").toString();
            }
        }
        throw new AssertionError("找不到班级 " + classId);
    }

    private void join(String cookie, String inviteCode) throws Exception {
        send(cookie, "POST", "/api/classes/join", Map.of("inviteCode", inviteCode));
    }

    private String createOffering(String classId) throws Exception {
        String paperId = post("/api/papers", Map.of("title", "表单测试卷", "defaultDurationMinutes", 60), "id");
        String sectionId = post("/api/papers/" + paperId + "/sections",
                Map.of("title", "写作", "pointsPerItem", 20), "id");
        post("/api/papers/" + paperId + "/items", Map.of("number", 1, "questionType", "ESSAY",
                "stem", "Write an essay.", "points", 20, "paperSectionId", sectionId), "id");
        post("/api/papers/" + paperId + "/publish", Map.of(), "id");
        Map<String, Object> body = Map.of(
                "paperId", paperId, "kind", "ASSIGNMENT", "timingMode", "INDIVIDUAL",
                "dueAt", Instant.now().plusSeconds(7 * 86400).toString(), "durationMinutes", 60,
                "classes", List.of(Map.of("classId", classId)));
        String offeringId = send(teacher, "POST", "/api/offerings", body).get("id").toString();
        send(teacher, "POST", "/api/offerings/" + offeringId + "/publish", Map.of());
        return offeringId;
    }

    private String createAndLoginOtherStudent() throws Exception {
        if (users.findByEmail(OTHER_STUDENT).isPresent()) {
            return login(OTHER_STUDENT);
        }
        users.save(new User(UUID.randomUUID(), UserRole.STUDENT, OTHER_STUDENT, encoder.encode(PASSWORD),
                "其他学生", Instant.now()));
        return login(OTHER_STUDENT);
    }

    private byte[] pdfWithText(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] blankPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] docxWithText(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private String login(String email) throws Exception {
        HttpResponse<String> res = raw(null, "POST", "/api/auth/login",
                Map.of("email", email, "password", PASSWORD));
        assertThat(res.statusCode()).as("login %s: %s", email, res.body()).isEqualTo(200);
        List<String> cookies = res.headers().allValues("set-cookie");
        return cookies.stream()
                .map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("登录没有下发会话 cookie：" + cookies));
    }

    private String post(String path, Object body, String field) throws Exception {
        Object value = send(teacher, "POST", path, body).get(field);
        assertThat(value).as("%s 返回缺少字段 %s", path, field).isNotNull();
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String cookie, String method, String path, Object body)
            throws Exception {
        HttpResponse<String> res = raw(cookie, method, path, body);
        assertThat(res.statusCode()).withFailMessage("%s %s -> %s %s", method, path, res.statusCode(), res.body())
                .isBetween(200, 299);
        return Json.parse(res.body());
    }

    private HttpResponse<String> raw(String cookie, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String write(Object o) {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parse(String s) {
            return MAPPER.readValue(s, Map.class);
        }

        static List<?> parseList(String s) {
            return MAPPER.readValue(s, List.class);
        }
    }
}
