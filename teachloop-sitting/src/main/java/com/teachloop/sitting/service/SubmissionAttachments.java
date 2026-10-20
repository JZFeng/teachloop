package com.teachloop.sitting.service;

import com.teachloop.shared.error.ApiException;
import com.teachloop.sitting.storage.StorageBackend;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件提交的落库前处理（T061 / T067）：校验 → 提取文本 → 存进 StorageBackend。
 *
 * <p>key 由本模块生成：{@code submissions/<offeringId>/<uuid>.<ext>}，不含原始文件名，
 * 既避免路径穿越，也不把学生的文件名带进存储路径（下载时再从 {@code attachment_name} 读）。
 */
@Service
public class SubmissionAttachments {

    public record Prepared(String storagePath, String fileName, String mime, String text, boolean unreadable) {
    }

    private final StorageBackend storage;
    private final SubmissionFileExtractor extractor;

    public SubmissionAttachments(StorageBackend storage, SubmissionFileExtractor extractor) {
        this.storage = storage;
        this.extractor = extractor;
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

    public Optional<byte[]> load(String storagePath) {
        return storage.load(storagePath);
    }
}
