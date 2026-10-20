package com.teachloop.observability.log;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 日志目录与按天文件名的唯一口径。
 *
 * <p>目录来自 {@code teachloop.observability.log-dir}（默认 {@code ./logs}），测试注入临时目录，
 * 避免测试与开发日志互相污染。日期一律按 UTC 计算（宪法第八条）。
 */
@Component
public class LogFiles {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path directory;

    public LogFiles(@Value("${teachloop.observability.log-dir:./logs}") String logDir) {
        this.directory = Path.of(logDir).toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    /** 例如 {@code requests-2026-10-19.jsonl}。 */
    public Path file(LogKind kind, LocalDate day) {
        return directory.resolve(kind.prefix() + "-" + DAY.format(day) + ".jsonl");
    }

    public static String fileName(LogKind kind, LocalDate day) {
        return kind.prefix() + "-" + DAY.format(day) + ".jsonl";
    }

    public static LocalDate parseDay(LogKind kind, String fileName) {
        String prefix = kind.prefix() + "-";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".jsonl")) {
            return null;
        }
        String day = fileName.substring(prefix.length(), fileName.length() - ".jsonl".length());
        try {
            return LocalDate.parse(day, DAY);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
