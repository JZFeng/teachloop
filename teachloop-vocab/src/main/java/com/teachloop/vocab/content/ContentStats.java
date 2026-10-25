package com.teachloop.vocab.content;

/**
 * 内容命中统计（plan §4.1.6）：排障时回答「这次复习到底走了包、delta 还是 AI」。
 *
 * <p>{@code puts} 是写入 delta 的次数；{@code failures} 是 delta 读写失败次数。
 * 只做进程内计数，重启归零；不写业务库。
 */
public record ContentStats(long hits, long misses, long puts, long failures) {

    public static ContentStats empty() {
        return new ContentStats(0, 0, 0, 0);
    }
}
