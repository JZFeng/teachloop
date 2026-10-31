package com.teachloop.vocab.generation;

import com.teachloop.vocab.content.ContentStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final ContentStore content;
    private final boolean enabled;
    private final boolean onImport;
    private final boolean lookupOnMiss;
    private final int dailyBudget;
    private final Clock clock;

    public VocabularyGenerationService(
            GenerationJobStore store,
            ContentStore content,
            @Value("${teachloop.vocab.generation.enabled:true}") boolean enabled,
            @Value("${teachloop.vocab.generation.on-import:false}") boolean onImport,
            @Value("${teachloop.vocab.lookup.generate-on-miss:true}") boolean lookupOnMiss,
            @Value("${teachloop.vocab.generation.daily-budget:2000}") int dailyBudget,
            Clock clock) {
        this.store = store;
        this.content = content;
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
     * 词书发布 / 已发布书导入后的预热（2026-10-30 用户 Bug：发布后学生进来还是「生成中」，后台零任务）。
     *
     * <p>书的词条不绑定任何学生词库行，所以走的不是 WORD 任务而是 FORM 任务
     * （{@link GenerationJobStore#enqueueForm}，与查词即生成同一条通路，结果写全局内容索引）——
     * 发布之后内容就开始生成，学生的书详情页覆盖率会自己往上爬，不必等他先加入再进复习。
     *
     * <p>三条边界：
     * <ul>
     *   <li>**只补缺失**：内容索引里已就绪的词形一个都不入队（同一份内容不重复烧 AI）；
     *       内容索引不可用时整体跳过——那时候分不清「没生成」和「查不到」，按缺失处理会把整本书重烧一遍。</li>
     *   <li>**受日预算**（与导入预热同口径）：打满时只记 warn，学生按需路径不受预算影响，复习不会被卡。</li>
     *   <li>**在跑的不重置**：QUEUED/RUNNING 直接跳过；FAILED 或「任务成功但内容索引里没有」
     *       （内容包换版、提示词升级、delta 被清）重新排队，否则这些词永远停在「生成中」。</li>
     * </ul>
     *
     * @param language       书的语言；空按 en
     * @param normalizedForms 书的全部词形（调用方给规范化后的值）
     * @return 本次新建或重新排队的任务数
     */
    public int prepareBookForms(String language, Collection<String> normalizedForms) {
        if (!enabled || normalizedForms == null || normalizedForms.isEmpty()) {
            return 0;
        }
        if (!content.available()) {
            log.warn("内容索引不可用，跳过词书预热（{} 个词形）：无法区分「没生成」与「查不到」",
                    normalizedForms.size());
            return 0;
        }
        String lang = language == null || language.isBlank() ? "en" : language;
        Set<String> ready = content.existingForms(lang, normalizedForms);
        List<String> missing = new ArrayList<>();
        for (String form : normalizedForms) {
            if (form != null && !form.isBlank() && !ready.contains(form)) {
                missing.add(form);
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }
        Instant since = LocalDate.ofInstant(clock.instant(), ZONE).atStartOfDay(ZONE).toInstant();
        long used = store.countCreatedSince(since);
        int left = (int) Math.max(0, dailyBudget - used);
        if (left <= 0) {
            log.warn("内容生成日预算已打满（{}/{}），暂停词书预热；今日缺 {} 个词，次日发布/导入会补上",
                    used, dailyBudget, missing.size());
            return 0;
        }
        int queued = 0;
        for (String form : missing) {
            if (queued >= left) {
                log.warn("词书预热达到日预算余额 {}，剩余 {} 个词留到明日",
                        left, missing.size() - queued);
                break;
            }
            Optional<String> state = store.formState(lang, form);
            boolean accepted;
            if (state.isEmpty()) {
                accepted = store.enqueueForm(lang, form, clock.instant(),
                        GenerationJobStore.PRIORITY_BACKGROUND);
            } else if ("QUEUED".equals(state.get()) || "RUNNING".equals(state.get())) {
                continue;
            } else {
                // FAILED，或 SUCCEEDED 但内容索引里查不到（换版/清库）：清零重排，否则永远停在「生成中」。
                store.retryForm(lang, form, clock.instant(),
                        GenerationJobStore.PRIORITY_BACKGROUND);
                accepted = true;
            }
            if (accepted) {
                queued++;
            }
        }
        if (queued > 0) {
            log.info("词书预热入队 {} 个词形（缺 {} 个，日预算余 {}）", queued, missing.size(), left);
        }
        return queued;
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
