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
    private final boolean onImport;
    private final boolean lookupOnMiss;
    private final int dailyBudget;
    private final Clock clock;

    public VocabularyGenerationService(
            GenerationJobStore store,
            @Value("${teachloop.vocab.generation.enabled:true}") boolean enabled,
            @Value("${teachloop.vocab.generation.on-import:false}") boolean onImport,
            @Value("${teachloop.vocab.lookup.generate-on-miss:true}") boolean lookupOnMiss,
            @Value("${teachloop.vocab.generation.daily-budget:2000}") int dailyBudget,
            Clock clock) {
        this.store = store;
        this.enabled = enabled;
        this.onImport = onImport;
        this.lookupOnMiss = lookupOnMiss;
        this.dailyBudget = dailyBudget;
        this.clock = clock;
    }

    /**
     * 导入/发词后的批量预热（T426 起默认关闭）。
     *
     * <p>词库可能有几千个词，人背不完，全部预生成是浪费；默认改为学生复习/看详情时
     * 按窗口准备（{@link #prepareOnDemand}）。需要恢复旧的「导入即全量生成」时把
     * {@code teachloop.vocab.generation.on-import} 设为 true。
     */
    public void enqueueNewWords(Collection<UUID> wordIds) {
        if (!enabled || !onImport || wordIds.isEmpty()) {
            return;
        }
        Instant since = LocalDate.ofInstant(clock.instant(), ZONE).atStartOfDay(ZONE).toInstant();
        long used = store.countCreatedSince(since);
        if (used >= dailyBudget) {
            log.warn("内容生成日预算已打满（{}/{}），暂停入队；新词保持 PENDING，次日恢复",
                    used, dailyBudget);
            return;
        }
        store.enqueueWords(wordIds, clock.instant(), GenerationJobStore.PRIORITY_BACKGROUND);
    }

    /**
     * 学生正在等的内容：按需入队并排到队首，不受日预算限制。
     *
     * <p>窗口由调用方控制（复习页的首批 20 个、详情页的 1 个），同一词幂等；
     * 预算若也拦这里，学生点开复习却永远等不到内容，所以此处只做开关判断。
     */
    public int prepareOnDemand(Collection<UUID> wordIds) {
        if (!enabled || wordIds.isEmpty()) {
            return 0;
        }
        store.enqueueWords(wordIds, clock.instant(), GenerationJobStore.PRIORITY_ON_DEMAND);
        return wordIds.size();
    }

    /**
     * 查词即生成（T515 / AC-60.13，2026-10-26 用户 Bug）：查词页遇到「不在本人词库、全局也没有内容」
     * 的词形时，直接为这个词形入队一次生成，生成结果只写全局 {@code ContentStore}。
     *
     * <p>与 {@link #prepareOnDemand} 一样不受日预算限制（学生在页面上等），但多一个独立开关
     * {@code teachloop.vocab.lookup.generate-on-miss}（默认 true）：查词是全站最容易被随手触发的
     * 入口（打错一个字母就是一次 AI 调用），运营上要能一键关掉。
     *
     * @return true 表示本次新建了任务；false 表示已有关卡任务、开关关闭或词形为空
     */
    public boolean prepareLookup(String language, String form) {
        if (!enabled || !lookupOnMiss) {
            return false;
        }
        return store.enqueueForm(language, form, clock.instant(),
                GenerationJobStore.PRIORITY_ON_DEMAND);
    }

    /** 查词即生成是否开启（端点据此区分「已入队」与「功能被关掉」）。 */
    public boolean lookupEnabled() {
        return enabled && lookupOnMiss;
    }
}
