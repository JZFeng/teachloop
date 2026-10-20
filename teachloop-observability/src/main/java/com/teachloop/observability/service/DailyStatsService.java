package com.teachloop.observability.service;

import com.teachloop.observability.domain.DailyStatRepository;
import com.teachloop.observability.log.LogFiles;
import com.teachloop.observability.log.LogKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * T144 / AC-16.6：把前一天的 JSONL 明细聚合进 {@code daily_stats}。
 *
 * <p>口径（全部来自日志文件，不读业务库）：
 * <ul>
 *   <li>{@code page_views} / {@code actions} / {@code submissions}：events 中日事件名的条数；</li>
 *   <li>{@code gradings}：requests 中「POST 且 route 含 grading」的条数（派发/重试/批量通过的触发次数，不是被判份数）；</li>
 *   <li>{@code total_requests}：requests 全部条数；{@code errors}：status ≥ 500；</li>
 *   <li>{@code slow_requests}：durationMs ≥ 阈值（默认 1000）；{@code p95_latency_ms}：耗时的最近秩 P95。</li>
 * </ul>
 *
 * <p>可重跑幂等：每个指标走 {@code ON CONFLICT DO UPDATE}，同一天重跑多少次都只有 8 行。
 */
@Service
public class DailyStatsService {

    private static final Logger log = LoggerFactory.getLogger(DailyStatsService.class);

    public static final String PAGE_VIEWS = "page_views";
    public static final String ACTIONS = "actions";
    public static final String SUBMISSIONS = "submissions";
    public static final String GRADINGS = "gradings";
    public static final String TOTAL_REQUESTS = "total_requests";
    public static final String ERRORS = "errors";
    public static final String SLOW_REQUESTS = "slow_requests";
    public static final String P95_LATENCY_MS = "p95_latency_ms";

    private final DailyStatRepository stats;
    private final LogFiles files;
    private final Clock clock;
    private final long slowRequestMs;
    private final ObjectMapper mapper = new ObjectMapper();

    public DailyStatsService(DailyStatRepository stats, LogFiles files, Clock clock,
                             @Value("${teachloop.observability.slow-request-ms:1000}") long slowRequestMs) {
        this.stats = stats;
        this.files = files;
        this.clock = clock;
        this.slowRequestMs = slowRequestMs;
    }

    /** 每天 00:10 UTC 聚合昨天；固定 Clock 便于测试。 */
    @Scheduled(cron = "${teachloop.observability.daily-stats-cron:0 10 0 * * *}", zone = "UTC")
    public void aggregateYesterday() {
        aggregate(today().minusDays(1));
    }

    public Map<String, BigDecimal> aggregate(LocalDate day) {
        List<Map<String, Object>> requests = read(LogKind.REQUESTS, day);
        List<Map<String, Object>> events = read(LogKind.EVENTS, day);

        List<Long> durations = new ArrayList<>();
        int totalRequests = requests.size();
        int errors = 0;
        int slow = 0;
        int gradings = 0;
        for (Map<String, Object> request : requests) {
            long duration = number(request.get("durationMs"));
            durations.add(duration);
            if (number(request.get("status")) >= 500) {
                errors++;
            }
            if (duration >= slowRequestMs) {
                slow++;
            }
            if ("POST".equalsIgnoreCase(text(request.get("method")))
                    && text(request.get("route")).contains("grading")) {
                gradings++;
            }
        }

        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        metrics.put(PAGE_VIEWS, BigDecimal.valueOf(count(events, "page_view")));
        metrics.put(ACTIONS, BigDecimal.valueOf(count(events, "action_click")));
        metrics.put(SUBMISSIONS, BigDecimal.valueOf(count(events, "submit")));
        metrics.put(GRADINGS, BigDecimal.valueOf(gradings));
        metrics.put(TOTAL_REQUESTS, BigDecimal.valueOf(totalRequests));
        metrics.put(ERRORS, BigDecimal.valueOf(errors));
        metrics.put(SLOW_REQUESTS, BigDecimal.valueOf(slow));
        metrics.put(P95_LATENCY_MS, BigDecimal.valueOf(percentile95(durations)));

        metrics.forEach((metric, value) -> stats.upsert(day, metric, value));
        log.debug("daily_stats 聚合完成 day={} metrics={}", day, metrics);
        return metrics;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private List<Map<String, Object>> read(LogKind kind, LocalDate day) {
        Path file = files.file(kind, day);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<?, ?> parsed = mapper.readValue(line, Map.class);
                    Map<String, Object> row = new LinkedHashMap<>();
                    parsed.forEach((key, value) -> row.put(String.valueOf(key), value));
                    rows.add(row);
                } catch (RuntimeException ex) {
                    // 半个坏行不该让整天的汇总失败；跳过并保留其余计数。
                    log.warn("跳过无法解析的日志行 file={} : {}", file.getFileName(), ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("读取日志失败 file={} : {}", file, ex.getMessage());
        }
        return rows;
    }

    private static long count(List<Map<String, Object>> events, String name) {
        return events.stream().filter(e -> name.equals(text(e.get("name")))).count();
    }

    /** 最近秩 P95；没有请求时为 0。 */
    private static long percentile95(List<Long> durations) {
        if (durations.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(durations);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
