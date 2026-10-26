package com.teachloop.sitting.service;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.storage.BlobStore;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件提交的落库前处理（T061 / T067）：校验 → 提取文本 → 存进 shared 的 {@link BlobStore}（T386）。
 *
 * <p>key 由本模块生成：{@code submissions/<offeringId>/<uuid>.<ext>}，不含原始文件名，
 * 既避免路径穿越，也不把学生的文件名带进存储路径（下载时再从 {@code attachment_name} 读）。
 */
@Service
public class SubmissionAttachments {

    public record Prepared(String storagePath, String fileName, String mime, String text, boolean unreadable) {
    }

    private final BlobStore storage;
    private final SubmissionFileExtractor extractor;

    public SubmissionAttachments(BlobStore storage, SubmissionFileExtractor extractor) {
        this.storage = storage;
        this.extractor = extractor;
    }

    /** 只解析、不落库（2026-10-26 用户拍板）：上传文件用来回填编辑框，不交卷、不写 BlobStore。 */
    public record Preview(String fileName, String extension, String text, boolean unreadable) {
    }

    /** 校验并保存附件；解析失败只标记 unreadable，不抛错（AC-6.9）。 */
    public Prepared prepare(UUID offeringId, MultipartFile file) {
        SubmissionFileExtractor.ValidatedFile validated = extractor.validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw ApiException.badRequest("文件读取失败，请重试");
        }
        SubmissionFileExtractor.Extraction extraction =
                extractor.extract(validated.extension(), bytes);
        String key = "submissions/" + offeringId + "/" + UUID.randomUUID() + "." + validated.extension();
        storage.store(key, bytes, validated.mime());
        return new Prepared(key, validated.originalName(), validated.mime(),
                extraction.text(), extraction.unreadable());
    }

    /** 只解析、不落库：学生只是把文件内容填进编辑框，还没交卷，所以这里不做任何写入。 */
    public Preview preview(MultipartFile file) {
        SubmissionFileExtractor.ValidatedFile validated = extractor.validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw ApiException.badRequest("文件读取失败，请重试");
        }
        SubmissionFileExtractor.Extraction extraction =
                extractor.extract(validated.extension(), bytes);
        return new Preview(validated.originalName(), validated.extension(),
                extraction.text(), extraction.unreadable());
    }

    /** T062：视频不提取文本，只校验后落存储；能力与文件附件同一个 BlobStore。 */
    public String storeVideo(UUID offeringId, byte[] bytes, String extension, String mime) {
        String key = "submissions/" + offeringId + "/" + UUID.randomUUID() + "." + extension;
        storage.store(key, bytes, mime);
        return key;
    }

    public Optional<byte[]> load(String storagePath) {
        return storage.load(storagePath);
    }
}
