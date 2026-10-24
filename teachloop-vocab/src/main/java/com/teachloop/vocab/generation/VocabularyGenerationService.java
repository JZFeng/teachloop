package com.teachloop.vocab.generation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 入队前的预算与开关检查（T394 / plan §4）。
 *
 * <p>日预算按部署时区（Asia/Shanghai，spec A-3）的「今天」计算已建 job 数；
 * 超预算时**暂停入队**（新词保持 PENDING，不建 job），只记 warn，不阻塞导入。
 */
@Service
public class VocabularyGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VocabularyGenerationService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final GenerationJobStore store;
    private final boolean enabled;
    private final int dailyBudget;
    private final Clock clock;

    public VocabularyGenerationService(
            GenerationJobStore store,
            @Value("${teachloop.vocab.generation.enabled:true}") boolean enabled,
            @Value("${teachloop.vocab.generation.daily-budget:2000}") int dailyBudget,
            Clock clock) {
        this.store = store;
        this.enabled = enabled;
        this.dailyBudget = dailyBudget;
        this.clock = clock;
    }

    /** 导入后为新词入队；预算打满或开关关闭时静默跳过（词条仍可复习，内容稍后补）。 */
    public void enqueueNewWords(Collection<UUID> wordIds) {
        if (!enabled || wordIds.isEmpty()) {
            return;
        }
        Instant since = LocalDate.ofInstant(clock.instant(), ZONE).atStartOfDay(ZONE).toInstant();
        long used = store.countCreatedSince(since);
        if (used >= dailyBudget) {
            log.warn("内容生成日预算已打满（{}/{}），暂停入队；新词保持 PENDING，次日恢复",
                    used, dailyBudget);
            return;
        }
        store.enqueueWords(wordIds, clock.instant());
    }
}
