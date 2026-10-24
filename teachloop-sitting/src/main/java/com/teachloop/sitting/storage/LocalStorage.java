package com.teachloop.sitting.storage;

import com.teachloop.shared.storage.BlobStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地文件存储（T067 开发实现）：目录来自 {@code teachloop.storage.local-dir}，默认 {@code ./uploads}。
 *
 * <p>只接受本模块生成的 key（字母、数字、{@code . _ - /}），拒绝 {@code ..} 与绝对路径，
 * 避免把用户可控的文件名拼进路径造成穿越。
 *
 * <p>T386：实现 shared 的 {@link BlobStore} 端口；路径校验与落盘行为与改前一致。
 */
@Component
@ConditionalOnProperty(name = "teachloop.storage.backend", havingValue = "local", matchIfMissing = true)
public class LocalStorage implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(LocalStorage.class);

    private final Path root;

    public LocalStorage(@Value("${teachloop.storage.local-dir:./uploads}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        log.info("附件本地存储目录：{}", root);
    }

    @Override
    public String store(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return key;
        } catch (IOException ex) {
            throw new UncheckedIOException("写入附件失败：" + key, ex);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException ex) {
            throw new UncheckedIOException("读取附件失败：" + key, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    /** key 只允许本模块生成的形态；任何越界都直接拒绝，不给「用 ../ 读别的文件」留口子。 */
    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/")
                || key.contains("\\") || !key.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("非法的附件 key");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的附件 key");
        }
        return target;
    }
}
