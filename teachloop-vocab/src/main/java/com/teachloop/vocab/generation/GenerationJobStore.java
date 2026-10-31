package com.teachloop.vocab.generation;

import com.teachloop.vocab.word.WordNormalizer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生成任务队列（T394 / plan §4）。
 *
 * <p>领取用 {@code FOR UPDATE SKIP LOCKED}，多实例/多线程不会重复领同一个 job；
 * 幂等键是表上的 {@code unique(subject_type, subject_id)}，重复导入只入队一次。
 */
@Repository
public class GenerationJobStore {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobStore.class);

    private final JdbcTemplate jdbc;

    public GenerationJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Job(long id, String subjectType, UUID subjectId, int attempts) {
    }

    public record WordInfo(UUID id, String form, String language) {
    }

    /** 查词任务（subject_type=FORM）的生成目标：不绑定任何 vocab_words 行。 */
    public record FormInfo(UUID id, String form, String language) {
    }

    /** 后台预热优先级（导入等批量来源）。 */
    public static final int PRIORITY_BACKGROUND = 0;
    /** 学生正在等的按需准备（复习/详情页），先于后台任务领取。 */
    public static final int PRIORITY_ON_DEMAND = 100;

    /** 一次入队一批词条；已存在的 job 不重复建。 */
    public void enqueueWords(Collection<UUID> wordIds, Instant now) {
        enqueueWords(wordIds, now, PRIORITY_BACKGROUND);
    }

    /** 带优先级的入队：priority 越大越先被 worker 领取（T426）。 */
    public void enqueueWords(Collection<UUID> wordIds, Instant now, int priority) {
        if (wordIds.isEmpty()) {
            return;
        }
        Timestamp timestamp = Timestamp.from(now);
        List<Object[]> batch = new ArrayList<>(wordIds.size());
        for (UUID wordId : wordIds) {
            batch.add(new Object[]{wordId, timestamp, timestamp, timestamp, priority});
        }
        jdbc.batchUpdate("""
                insert into vocab_generation_jobs
                    (subject_type, subject_id, state, attempts, next_run_at, created_at, updated_at,
                     priority)
                values ('WORD', ?, 'QUEUED', 0, ?, ?, ?, ?)
                on conflict (subject_type, subject_id) do nothing
                """, batch);
    }

    /**
     * 查词即生成（T515 / AC-60.13）：为「不在库、也没有全局内容」的词形入队一次生成。
     *
     * <p>subject_type 用 {@code FORM}——任务不绑定任何 {@code vocab_words} 行，生成结果只写全局
     * {@code ContentStore}，所以查词不会把词塞进学生词库。{@code subject_id} 由
     * {@code (language, normalizedForm)} 稳定派生，同一词形重复查词只会有一条 job。
     *
     * @return true 表示本次新建了任务；false 表示已有任务（QUEUED/RUNNING/SUCCEEDED/FAILED）
     */
    public boolean enqueueForm(String language, String form, Instant now, int priority) {
        String lang = language == null || language.isBlank() ? "en" : language;
        String normalized = WordNormalizer.normalized(form);
        if (normalized.isEmpty()) {
            return false;
        }
        Timestamp timestamp = Timestamp.from(now);
        int inserted = jdbc.update("""
                insert into vocab_generation_jobs
                    (subject_type, subject_id, subject_form, subject_language, state, attempts,
                     next_run_at, created_at, updated_at, priority)
                values ('FORM', ?, ?, ?, 'QUEUED', 0, ?, ?, ?, ?)
                on conflict (subject_type, subject_id) do nothing
                """, formSubjectId(lang, normalized), normalized, lang, timestamp, timestamp,
                timestamp, priority);
        return inserted > 0;
    }

    /** {@code (language, normalizedForm)} → 稳定的 subject_id；worker 与端点用同一个算法。 */
    public static UUID formSubjectId(String language, String form) {
        String lang = language == null || language.isBlank() ? "en" : language;
        String key = "vocab-content:" + lang + ":" + WordNormalizer.normalized(form);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** 查词任务要生成的目标（词形 + 语言）；非 FORM 任务或行不存在时为空。 */
    public Optional<FormInfo> form(UUID subjectId) {
        List<FormInfo> rows = jdbc.query("""
                select subject_id, subject_form, subject_language
                  from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_id = ?
                """, (rs, rowNumber) -> new FormInfo(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                subjectId);
        return rows.stream().findFirst();
    }

    /** 某个词形最近一条查词任务的状态（QUEUED/RUNNING/SUCCEEDED/FAILED）；没查过为空。 */
    public Optional<String> formState(String language, String form) {
        List<String> rows = jdbc.query("""
                select state
                  from vocab_generation_jobs
                 where subject_type = 'FORM' and subject_id = ?
                """, (rs, rowNumber) -> rs.getString(1), formSubjectId(language, form));
        return rows.stream().findFirst();
    }

    /**
     * 查词页点「重试」：把失败的查词任务放回队列并清零重试次数。
     *
     * <p>只在用户明确点重试时调用——页面自动轮询不会重置，避免一条生成不了的词无限重试。
     */
    @Transactional
    public void retryForm(String language, String form, Instant now) {
        retryForm(language, form, now, PRIORITY_ON_DEMAND);
    }

    /**
     * 带优先级的重排：词书发布/导入预热用 {@link #PRIORITY_BACKGROUND}（后台补内容，不抢学生的队首），
     * 查词页「重试」仍用 {@link #PRIORITY_ON_DEMAND}（学生正盯着屏幕等）。
     */
    @Transactional
    public void retryForm(String language, String form, Instant now, int priority) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                update vocab_generation_jobs
                   set state = 'QUEUED', attempts = 0, next_run_at = ?, last_error = null,
                       locked_by = null, locked_at = null, updated_at = ?, priority = ?
                 where subject_type = 'FORM' and subject_id = ?
                """, timestamp, timestamp, priority, formSubjectId(language, form));
    }

    /** 查词任务成功：内容已写全局 delta，任务只需置 SUCCEEDED（不动任何用户词条）。 */
    @Transactional
    public void succeedForm(long jobId, Instant now) {
        jdbc.update("""
                update vocab_generation_jobs
                   set state = 'SUCCEEDED', locked_by = null, locked_at = null,
                       last_error = null, updated_at = ?
                 where id = ?
                """, Timestamp.from(now), jobId);
    }

    /** 今天已建的任务数（日预算口径）。 */
    public long countCreatedSince(Instant since) {
        Long count = jdbc.queryForObject(
                "select count(*) from vocab_generation_jobs where created_at >= ?", Long.class,
                Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Transactional
    public List<Job> claim(int limit, String workerId, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("""
                with picked as (
                    select id
                      from vocab_generation_jobs
                     where state = 'QUEUED' and next_run_at <= ?
                     order by priority desc, next_run_at
                     limit ?
                     for update skip locked
                )
                update vocab_generation_jobs job
                   set state = 'RUNNING', locked_by = ?, locked_at = ?, updated_at = ?
                  from picked
                 where job.id = picked.id
                returning job.id, job.subject_type, job.subject_id, job.attempts
                """, (rs, rowNumber) -> new Job(
                rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4)),
                Timestamp.from(now), limit, workerId, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<WordInfo> word(UUID wordId) {
        List<WordInfo> rows = jdbc.query(
                "select id, form, language from vocab_words where id = ?",
                (rs, rowNumber) -> new WordInfo(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                wordId);
        return rows.stream().findFirst();
    }

    /**
     * 生成成功：job 标 SUCCEEDED，词条只写 {@code content_id} + READY（T431 / decisions/017 P3）。
     *
     * <p>内容本身已在 {@code ContentStore}（worker 先写 delta）；这里不再写用户行副本。
     * {@code contentId} 为 null（delta 写失败）时记 warn，状态仍置 READY——读路径按未准备显示，
     * 不阻塞复习。
     */
    @Transactional
    public void succeed(long jobId, UUID wordId, Long contentId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        if (contentId == null) {
            log.warn("内容生成成功但全局 content_id 为空 word={}（词条仍 READY，读路径显示未准备）", wordId);
        }
        jdbc.update("""
                update vocab_generation_jobs
                   set state = 'SUCCEEDED', locked_by = null, locked_at = null,
                       last_error = null, updated_at = ?
                 where id = ?
                """, timestamp, jobId);
        jdbc.update("""
                update vocab_words
                   set content_id = ?, content_status = 'READY', updated_at = ?
                 where id = ?
                """, contentId, timestamp, wordId);
    }

    /** 记录一次失败：未到上限回 QUEUED 并退避；到上限标 FAILED 且词条标「生成失败」。 */
    @Transactional
    public void fail(long jobId, UUID wordId, int attempts, boolean finalFailure,
                     Instant nextRunAt, String error, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                update vocab_generation_jobs
                   set attempts = ?,
                       state = ?,
                       next_run_at = ?,
                       last_error = ?,
                       locked_by = null,
                       locked_at = null,
                       updated_at = ?
                 where id = ?
                """, attempts, finalFailure ? "FAILED" : "QUEUED", Timestamp.from(nextRunAt),
                error == null ? null : error.substring(0, Math.min(error.length(), 1000)),
                timestamp, jobId);
        if (finalFailure && wordId != null) {
            jdbc.update("update vocab_words set content_status = 'FAILED', updated_at = ? where id = ?",
                    timestamp, wordId);
        }
    }

    /**
     * 24 小时仍未完成的 job：标 FAILED（内容缺失），词条任务顺带把词条标「生成失败」，不再参与领取。
     *
     * <p>T515：查词任务（subject_type=FORM）没有 vocab_words 行，只改任务本身——它的 subject_id
     * 是按词形派生的 UUID，拿去 update vocab_words 是无效写入。
     *
     * @return 受影响的词条数（只算 WORD 任务）
     */
    @Transactional
    public int sweepStale(Instant cutoff, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        List<String[]> stale = jdbc.query("""
                update vocab_generation_jobs
                   set state = 'FAILED', last_error = 'content missing after 24h',
                       locked_by = null, locked_at = null, updated_at = ?
                 where created_at < ? and state in ('QUEUED', 'RUNNING')
                returning subject_type, subject_id
                """, (rs, rowNumber) -> new String[]{rs.getString(1), rs.getString(2)},
                timestamp, Timestamp.from(cutoff));
        int words = 0;
        for (String[] row : stale) {
            if (!"WORD".equals(row[0])) {
                continue;
            }
            jdbc.update("update vocab_words set content_status = 'FAILED', updated_at = ? where id = ?",
                    timestamp, UUID.fromString(row[1]));
            words++;
        }
        return words;
    }

    /** T395 手动重试：词条回 PENDING，job 回 QUEUED（不存在则补建）。 */
    @Transactional
    public void retryWord(UUID wordId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("update vocab_words set content_status = 'PENDING', updated_at = ? where id = ?",
                timestamp, wordId);
        int updated = jdbc.update("""
                update vocab_generation_jobs
                   set state = 'QUEUED', attempts = 0, next_run_at = ?, last_error = null,
                       locked_by = null, locked_at = null, updated_at = ?, priority = ?
                 where subject_type = 'WORD' and subject_id = ?
                """, timestamp, timestamp, PRIORITY_ON_DEMAND, wordId);
        if (updated == 0) {
            enqueueWords(List.of(wordId), now, PRIORITY_ON_DEMAND);
        }
    }

    public String statusOfWord(UUID wordId) {
        return jdbc.queryForObject("select content_status from vocab_words where id = ?", String.class, wordId);
    }
}
