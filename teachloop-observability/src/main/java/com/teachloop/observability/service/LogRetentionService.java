package com.teachloop.observability.service;

import com.teachloop.observability.log.LogFiles;
import com.teachloop.observability.log.LogKind;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * T142 / AC-16.4：按天轮转的日志文件保留 90 天，到期删除。
 *
 * <p>只删 {@code requests-*.jsonl} / {@code events-*.jsonl} 文件；**审计表一行都不删**
 * （AC-16.5：审计永久保留，只允许归档到冷存储）。本类不依赖 AuditLogRepository 是有意的。
 */
@Service
public class LogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionService.class);

    private final LogFiles files;
    private final Clock clock;
    private final int retentionDays;

    public LogRetentionService(LogFiles files, Clock clock,
                               @Value("${teachloop.observability.retention-days:90}") int retentionDays) {
        this.files = files;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${teachloop.observability.cleanup-ms:3600000}")
    public void cleanup() {
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        int deleted = deleteBefore(today);
        if (deleted > 0) {
            log.info("日志清理：删除 {} 个超过 {} 天的文件", deleted, retentionDays);
        }
    }

    /** @return 删除的文件数；截止日之后（含当天）的文件保留。 */
    public int deleteBefore(LocalDate today) {
        LocalDate cutoff = today.minusDays(retentionDays);
        if (!Files.isDirectory(files.directory())) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(files.directory())) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                LocalDate day = dayOf(path.getFileName().toString());
                if (day != null && day.isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                    deleted++;
                }
            }
        } catch (IOException ex) {
            log.warn("日志清理失败 dir={} : {}", files.directory(), ex.getMessage());
        }
        return deleted;
    }

    private static LocalDate dayOf(String fileName) {
        for (LogKind kind : LogKind.values()) {
            LocalDate day = LogFiles.parseDay(kind, fileName);
            if (day != null) {
                return day;
            }
        }
        return null;
    }
}
