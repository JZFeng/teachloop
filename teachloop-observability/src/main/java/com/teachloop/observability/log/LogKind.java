package com.teachloop.observability.log;

/** 日志类别：决定按天滚动的文件名前缀。 */
public enum LogKind {
    REQUESTS("requests"),
    EVENTS("events");

    private final String prefix;

    LogKind(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
