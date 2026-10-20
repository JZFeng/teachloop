package com.teachloop.observability.log;

import java.time.Instant;
import java.util.Map;

/**
 * 日志输出边界（T143）：MVP 写本地 JSONL，接 Elasticsearch 时只换实现。
 *
 * <p>业务代码只依赖本接口；ES 关闭、不可用或被替换都不会影响请求与业务写入。
 * 调用方保证 {@code fields} 里不含请求/响应正文。
 */
public interface LogSink {

    void write(LogKind kind, Instant at, Map<String, Object> fields);
}
