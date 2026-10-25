package com.teachloop.vocab.content.pack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把内容包装载结果写一条可检索的日志（T430 / plan §4.1.5）。
 *
 * <p>真正的装载在 {@link ContentPackReader} 构造期完成（bean 创建即校验 sha256 与 idx），
 * 因此这里只做「结果播报」与兜底：{@code pack-required=true} 而装载却不可用时说明构造期
 * 漏了异常，这里再抛一次，避免带着空包静默启动。
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class ContentPackBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContentPackBootstrap.class);

    private final ContentPackReader reader;

    public ContentPackBootstrap(ContentPackReader reader) {
        this.reader = reader;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (reader.available()) {
            log.info("内容包就绪：{} 条（language={} version={} promptVersion={}），索引常驻≈{}KB，装载 {}ms，dir={}",
                    reader.count(), reader.language(), reader.version(), reader.promptVersion(),
                    reader.indexHeapBytes() / 1024, reader.loadMillis(), reader.dir());
            return;
        }
        if (reader.packRequired()) {
            throw new ContentPackException("内容包不可用，但 teachloop.vocab.content.pack-required=true："
                    + reader.unavailableReason());
        }
        log.info("内容包不可用（{}），全局内容只用 delta + AI；dir={}", reader.unavailableReason(), reader.dir());
    }
}
