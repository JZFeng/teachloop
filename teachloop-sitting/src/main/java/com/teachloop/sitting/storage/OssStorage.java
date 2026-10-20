package com.teachloop.sitting.storage;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OSS 存储的生产占位实现（T067）。
 *
 * <p>V1 只交付存储抽象与本地实现；OSS SDK 与签名 URL 属部署阶段（plan §15），
 * 本类刻意不含任何上传逻辑。行为约定：
 * <ul>
 *   <li>只有 {@code teachloop.storage.backend=oss} 时才装配；</li>
 *   <li>缺少 {@code teachloop.storage.oss.bucket} 时<b>启动即报错</b>，避免跑到线上才发现附件全丢；</li>
 *   <li>配置齐全但实现未接入时，运行期抛 503（{@link StorageUnavailableException}），而不是 500。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "teachloop.storage.backend", havingValue = "oss")
public class OssStorage implements StorageBackend {

    public OssStorage(@Value("${teachloop.storage.oss.bucket:}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "已选择 OSS 存储但未配置 teachloop.storage.oss.bucket；开发环境请用 teachloop.storage.backend=local");
        }
    }

    @Override
    public String store(String key, byte[] content, String contentType) {
        throw new StorageUnavailableException("OSS 存储尚未接入（T067 生产实现待部署阶段完成）");
    }

    @Override
    public Optional<byte[]> load(String key) {
        throw new StorageUnavailableException("OSS 存储尚未接入（T067 生产实现待部署阶段完成）");
    }
}
