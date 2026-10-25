package com.teachloop.vocab.generation;

import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.word.WordNormalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 内容生成轮询器（T394 / plan §4）：与 grading worker 同思路，不引入 MQ。
 *
 * <p>每轮：先把超过 24 小时仍未完成的 job 标「内容缺失」并移出可领取状态；
 * 再用 {@code FOR UPDATE SKIP LOCKED} 领一批 QUEUED job；成功后把内容写全局
 * {@code ContentStore}（delta）并把用户行置 READY + {@code content_id}，
 * 失败按 1 分钟 / 5 分钟退避，第 3 次失败标 FAILED。
 */
@Component
public class VocabularyGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(VocabularyGenerationWorker.class);
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private final GenerationJobStore store;
    private final VocabularyContentGenerator generator;
    private final ContentStore contentStore;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Clock clock;
    private final String workerId = "vocab-worker-" + UUID.randomUUID();

    public VocabularyGenerationWorker(
            GenerationJobStore store,
            VocabularyContentGenerator generator,
            ContentStore contentStore,
            @Value("${teachloop.vocab.generation.enabled:true}") boolean enabled,
            @Value("${teachloop.vocab.generation.batch-size:5}") int batchSize,
            @Value("${teachloop.vocab.generation.max-attempts:3}") int maxAttempts,
            Clock clock) {
        this.store = store;
        this.generator = generator;
        this.contentStore = contentStore;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${teachloop.vocab.generation.poll-ms:2000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        int stale = store.sweepStale(now.minus(STALE_AFTER), now);
        if (stale > 0) {
            log.warn("内容生成：{} 个词超过 24 小时仍未生成，已标内容缺失并移出队列", stale);
        }
        List<GenerationJobStore.Job> jobs = store.claim(batchSize, workerId, now);
        for (GenerationJobStore.Job job : jobs) {
            process(job);
        }
    }

    private void process(GenerationJobStore.Job job) {
        if (!"WORD".equals(job.subjectType())) {
            store.fail(job.id(), null, job.attempts() + 1, true, clock.instant(),
                    "unsupported subject type " + job.subjectType(), clock.instant());
            return;
        }
        Optional<GenerationJobStore.WordInfo> word = store.word(job.subjectId());
        if (word.isEmpty()) {
            store.fail(job.id(), null, job.attempts() + 1, true, clock.instant(),
                    "word not found", clock.instant());
            return;
        }
        try {
            String content = generator.generate(word.get().form(), word.get().language());
            // T429 / decisions/017：AI 成功的内容先写全局 delta，学生行只引用 content_id。
            // delta 写失败不阻塞复习：用户行仍 READY，读路径按词形再查一次；记 warn 即可。
            Long contentId = null;
            try {
                contentId = contentStore.put(word.get().language(),
                        WordNormalizer.normalized(word.get().form()), content, "AI");
            } catch (RuntimeException ex) {
                log.warn("内容写入全局 delta 失败 word={}（用户行仍 READY）：{}",
                        word.get().form(), ex.getMessage());
            }
            store.succeed(job.id(), word.get().id(), contentId, clock.instant());
        } catch (RuntimeException ex) {
            int attempts = job.attempts() + 1;
            boolean finalFailure = attempts >= maxAttempts;
            Instant nextRunAt = clock.instant().plusSeconds(attempts == 1 ? 60 : 300);
            store.fail(job.id(), word.get().id(), attempts, finalFailure, nextRunAt,
                    ex.getMessage(), clock.instant());
            log.warn("内容生成失败 word={} attempts={}/{}：{}",
                    word.get().form(), attempts, maxAttempts, ex.getMessage());
        }
    }
}
