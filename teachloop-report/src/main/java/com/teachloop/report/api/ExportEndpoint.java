package com.teachloop.report.api;

import com.teachloop.report.service.export.OfferingExportService;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导出（T120 / T121 / T124）：班级汇总 Markdown 与逐份反馈 ZIP。
 *
 * <p>权限：教师 / 管理员可用，学生 403；ZIP 还要求先发布成绩（AC-14.5）。
 * 数字与 {@code GET /api/offerings/{id}/class-summary} 同源（同一套只读 SQL 口径）。
 *
 * <p>PDF（T122）与 XLSX / DOCX（T123）需要新增 OpenPDF / POI 依赖，本接口暂不提供。
 */
@RestController
@RequestMapping("/api/offerings/{offeringId}/export")
public class ExportEndpoint {

    private static final MediaType MARKDOWN_UTF8 =
            MediaType.parseMediaType("text/markdown; charset=utf-8");

    private final OfferingExportService export;

    public ExportEndpoint(OfferingExportService export) {
        this.export = export;
    }

    /** {@code GET /api/offerings/{id}/export/summary.md} */
    @GetMapping("/summary.md")
    public ResponseEntity<byte[]> summaryMarkdown(@PathVariable UUID offeringId,
                                                  @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me, "学生不能导出班级汇总");
        byte[] body = export.summaryMarkdown(offeringId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MARKDOWN_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        attachment("summary-" + offeringId + ".md"))
                .body(body);
    }

    /** {@code GET /api/offerings/{id}/export/feedback.zip}；未发布成绩返回 409。 */
    @GetMapping("/feedback.zip")
    public ResponseEntity<byte[]> feedbackZip(@PathVariable UUID offeringId,
                                              @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me, "学生不能导出逐份反馈");
        byte[] body = export.feedbackZip(offeringId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        attachment("feedback-" + offeringId + ".zip"))
                .body(body);
    }

    private static void requireStaff(CurrentUser me, String message) {
        if (me == null) {
            throw ApiException.unauthorized("请先登录");
        }
        if (me.is("STUDENT")) {
            throw ApiException.forbidden(message);
        }
    }

    /** 文件名都是 UUID，纯 ASCII；保留 filename* 只为符合 RFC 5987 的客户端更稳。 */
    private static String attachment(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded;
    }
}
