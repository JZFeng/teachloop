package com.teachloop.vocab.generation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    private final JdbcTemplate jdbc;

    public GenerationJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Job(long id, String subjectType, UUID subjectId, int attempts) {
    }

    public record WordInfo(UUID id, String form, String language) {
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

    @Transactional
    public void succeed(long jobId, UUID wordId, String contentJson, Long contentId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                update vocab_generation_jobs
                   set state = 'SUCCEEDED', locked_by = null, locked_at = null,
                       last_error = null, updated_at = ?
                 where id = ?
                """, timestamp, jobId);
        jdbc.update("""
                update vocab_words
                   set content = cast(? as jsonb), content_id = ?, content_status = 'READY',
                       updated_at = ?
                 where id = ?
                """, contentJson, contentId, timestamp, wordId);
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
     * 24 小时仍未完成的 job：标 FAILED（内容缺失）并把词条标「生成失败」，不再参与领取。
     *
     * @return 受影响的词条数
     */
    @Transactional
    public int sweepStale(Instant cutoff, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        List<UUID> wordIds = jdbc.query("""
                update vocab_generation_jobs
                   set state = 'FAILED', last_error = 'content missing after 24h',
                       locked_by = null, locked_at = null, updated_at = ?
                 where created_at < ? and state in ('QUEUED', 'RUNNING')
                returning subject_id
                """, (rs, rowNumber) -> rs.getObject(1, UUID.class), timestamp, Timestamp.from(cutoff));
        for (UUID wordId : wordIds) {
            jdbc.update("update vocab_words set content_status = 'FAILED', updated_at = ? where id = ?",
                    timestamp, wordId);
        }
        return wordIds.size();
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
