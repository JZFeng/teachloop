package com.teachloop.sitting.api;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.sitting.domain.Submission;
import com.teachloop.sitting.domain.SubmissionRepository;
import com.teachloop.sitting.service.SubmissionAttachments;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 附件下载（T061）。
 *
 * <p>权限：教师/管理员可下载，学生只能下载自己的；不属于自己的附件一律 404，
 * 不泄漏「这份提交存不存在」。文件名来自数据库的原始名，存储 key 不出现在响应里。
 */
@RestController
@RequestMapping("/api/submissions")
public class SubmissionAttachmentEndpoint {

    private final SubmissionRepository submissions;
    private final SubmissionAttachments attachments;

    public SubmissionAttachmentEndpoint(SubmissionRepository submissions, SubmissionAttachments attachments) {
        this.submissions = submissions;
        this.attachments = attachments;
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> download(@PathVariable java.util.UUID id,
                                           @RequestAttribute("currentUser") CurrentUser me) {
        Submission submission = submissions.findById(id)
                .orElseThrow(() -> ApiException.notFound("附件不存在"));
        if (me.is("STUDENT") && !me.id().equals(submission.getStudentId())) {
            throw ApiException.notFound("附件不存在");
        }
        if (submission.getAttachmentPath() == null) {
            throw ApiException.notFound("附件不存在");
        }
        byte[] content = attachments.load(submission.getAttachmentPath())
                .orElseThrow(() -> ApiException.notFound("附件不存在"));
        String fileName = submission.getAttachmentName() == null || submission.getAttachmentName().isBlank()
                ? "attachment" : submission.getAttachmentName();
        MediaType type = mediaType(submission.getAttachmentMime());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .contentLength(content.length)
                .body(content);
    }

    private static MediaType mediaType(String mime) {
        if (mime == null || mime.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mime);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
