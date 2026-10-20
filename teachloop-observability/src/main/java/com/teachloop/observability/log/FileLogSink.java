package com.teachloop.observability.log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * T140–T143：本地 JSONL 文件实现。一行一个 JSON 对象，按天一个文件，追加写。
 *
 * <p>文件写失败只抛给调用方；请求日志过滤器会吞掉异常，保证「日志挂了不影响业务请求」；
 * 前端事件接口则把写入失败如实暴露为 500，不假装收下。
 */
@Component
public class FileLogSink implements LogSink {

    private final LogFiles files;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileLogSink(LogFiles files) {
        this.files = files;
    }

    @Override
    public synchronized void write(LogKind kind, Instant at, Map<String, Object> fields) {
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        String line = mapper.writeValueAsString(fields);
        try {
            Files.createDirectories(files.directory());
            Files.writeString(files.file(kind, day), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException("日志写入失败: " + files.file(kind, day), ex);
        }
    }
}
