package com.teachloop.vocab.content.pack;

/**
 * 只读内容包缺失、损坏或自相矛盾（decisions/017 P2 / plan §4.1.5）。
 *
 * <p>是否让应用启动失败由 {@code teachloop.vocab.content.pack-required} 决定（plan §4.1.5）：
 * 生产建议 true —— 包缺失或损坏都拒绝启动，避免带着半份包静默降级；开发默认 false ——
 * 缺失记 info、损坏记 warn，然后只用 delta + AI。
 */
public class ContentPackException extends RuntimeException {

    public ContentPackException(String message) {
        super(message);
    }

    public ContentPackException(String message, Throwable cause) {
        super(message, cause);
    }
}
