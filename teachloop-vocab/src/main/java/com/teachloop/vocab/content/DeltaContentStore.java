package com.teachloop.vocab.content;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * AI 现场生成内容的本地 SQLite delta（decisions/017 P1 / plan §4.1）。
 *
 * <p>只读内容包是主，delta 承接「包里没有、AI 生成过」的词。单文件、WAL、单写者：
 * 本类所有读写都串行化，避免 SQLite 连接被多线程同时使用；多实例部署时这个文件必须换成
 * 共享存储（另立决策），v1 单机不做。
 *
 * <p>失败策略（plan §4.1.5）：打不开/读写失败时降级为不可用，读按 miss 处理、写抛给调用方
 * 记 warn，不让复习卡死；delta 写失败时用户行仍然 READY（P3 后该词会退回按词形查询）。
 *
 * <p>content_id 从 {@link #DELTA_ID_START} 起递增，与只读包的 1..N 不冲突；
 * {@link ContentService} 按 id 区间分派。
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class DeltaContentStore implements ContentReader {

    private static final Logger log = LoggerFactory.getLogger(DeltaContentStore.class);

    /** 包内 id 1..N；delta 从 10 亿起，给包留足空间。 */
    public static final long DELTA_ID_START = 1_000_000_000L;

    private final String promptVersion;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder failures = new LongAdder();

    private final Object lock = new Object();
    private Connection connection;
    private JdbcTemplate jdbc;
    private volatile boolean available;

    @Autowired
    public DeltaContentStore(
            @Value("${teachloop.vocab.content.store-path:./data/vocab-content-delta.db}") String storePath,
            @Value("${teachloop.vocab.content.prompt-version:vocab-content-v2.4}") String promptVersion) {
        this.promptVersion = promptVersion;
        open(Path.of(storePath));
    }

    private void open(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("PRAGMA journal_mode=WAL");
            jdbc.execute("PRAGMA busy_timeout=5000");
            jdbc.execute("PRAGMA synchronous=NORMAL");
            jdbc.execute("""
                    create table if not exists word_content_delta (
                      content_id integer primary key,
                      language text not null,
                      normalized_form text not null,
                      content text not null,
                      prompt_version text not null,
                      source text not null default 'AI',
                      created_at text not null
                    )
                    """);
            jdbc.execute("""
                    create unique index if not exists uk_word_content_delta
                        on word_content_delta (language, normalized_form, prompt_version)
                    """);
            available = true;
            log.info("内容 delta 已打开 path={} promptVersion={}", path.toAbsolutePath(), promptVersion);
        } catch (IOException | SQLException | RuntimeException ex) {
            available = false;
            failures.increment();
            log.warn("内容 delta 打不开（降级为只用内容包 + AI）path={}：{}", path, ex.toString());
        }
    }

    public boolean available() {
        return available;
    }

    @Override
    public String layer() {
        return "delta";
    }

    @Override
    public Optional<StoredContent> get(String language, String normalizedForm) {
        if (!available) {
            misses.increment();
            return Optional.empty();
        }
        synchronized (lock) {
            try {
                List<StoredContent> rows = jdbc.query("""
                                select content_id, content, source
                                  from word_content_delta
                                 where language = ? and normalized_form = ? and prompt_version = ?
                                """,
                        (rs, rowNumber) -> new StoredContent(
                                rs.getLong(1), language, normalizedForm, promptVersion,
                                rs.getString(2), rs.getString(3)),
                        language, normalizedForm, promptVersion);
                if (rows.isEmpty()) {
                    misses.increment();
                    return Optional.empty();
                }
                hits.increment();
                return Optional.of(rows.get(0));
            } catch (RuntimeException ex) {
                failures.increment();
                misses.increment();
                log.warn("内容 delta 读取失败（按 miss 处理）language={} form={}：{}",
                        language, normalizedForm, ex.toString());
                return Optional.empty();
            }
        }
    }

    @Override
    public Optional<StoredContent> getById(long contentId) {
        if (!available || contentId < DELTA_ID_START) {
            misses.increment();
            return Optional.empty();
        }
        synchronized (lock) {
            try {
                List<StoredContent> rows = jdbc.query("""
                                select language, normalized_form, content, source
                                  from word_content_delta
                                 where content_id = ? and prompt_version = ?
                                """,
                        (rs, rowNumber) -> new StoredContent(
                                contentId, rs.getString(1), rs.getString(2), promptVersion,
                                rs.getString(3), rs.getString(4)),
                        contentId, promptVersion);
                if (rows.isEmpty()) {
                    misses.increment();
                    return Optional.empty();
                }
                hits.increment();
                return Optional.of(rows.get(0));
            } catch (RuntimeException ex) {
                failures.increment();
                misses.increment();
                log.warn("内容 delta 按 id 读取失败（按 miss 处理）id={}：{}", contentId, ex.toString());
                return Optional.empty();
            }
        }
    }

    /**
     * 写一行 delta。同一 {@code (language, form, promptVersion)} 幂等：已存在就更新内容并复用 id。
     *
     * @throws IllegalStateException delta 不可用时抛出，由调用方决定降级方式
     */
    public long put(String language, String normalizedForm, String contentJson, String source) {
        if (!available) {
            failures.increment();
            throw new IllegalStateException("content delta is not available");
        }
        if (contentJson == null || contentJson.isBlank()) {
            throw new IllegalArgumentException("contentJson is required");
        }
        String origin = source == null || source.isBlank() ? "AI" : source;
        synchronized (lock) {
            try {
                List<Long> existing = jdbc.query(
                        "select content_id from word_content_delta"
                                + " where language = ? and normalized_form = ? and prompt_version = ?",
                        (rs, rowNumber) -> rs.getLong(1),
                        language, normalizedForm, promptVersion);
                if (!existing.isEmpty()) {
                    long id = existing.get(0);
                    jdbc.update("""
                                    update word_content_delta
                                       set content = ?, source = ?, created_at = ?
                                     where content_id = ?
                                    """,
                            contentJson, origin, Instant.now().toString(), id);
                    puts.increment();
                    return id;
                }
                Long max = jdbc.queryForObject(
                        "select coalesce(max(content_id), ?) from word_content_delta",
                        Long.class, DELTA_ID_START - 1);
                long id = (max == null ? DELTA_ID_START - 1 : max) + 1;
                jdbc.update("""
                                insert into word_content_delta
                                    (content_id, language, normalized_form, content, prompt_version,
                                     source, created_at)
                                values (?, ?, ?, ?, ?, ?, ?)
                                """,
                        id, language, normalizedForm, contentJson, promptVersion, origin,
                        Instant.now().toString());
                puts.increment();
                return id;
            } catch (RuntimeException ex) {
                failures.increment();
                throw new IllegalStateException("cannot write content delta: " + ex.getMessage(), ex);
            }
        }
    }

    public ContentStats stats() {
        return new ContentStats(hits.sum(), misses.sum(), puts.sum(), failures.sum());
    }

    @PreDestroy
    public void close() {
        synchronized (lock) {
            available = false;
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    log.debug("关闭内容 delta 连接失败：{}", ex.toString());
                }
            }
        }
    }
}
