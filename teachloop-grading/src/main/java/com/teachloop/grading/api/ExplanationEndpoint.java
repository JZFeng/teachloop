package com.teachloop.grading.api;

import com.teachloop.grading.domain.ItemExplanationRepository;
import com.teachloop.grading.explanation.ExplanationEngine;
import com.teachloop.grading.explanation.ExplanationLanguage;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

/**
 * T088：按需生成错题解析（教师 / 管理员）。
 *
 * <p>选题交给 sitting 的 {@link Ports.ExplanationWorkQueue}（答错人次降序、封顶 N）；
 * 本接口只负责跳过、逐题生成、幂等写库与计数。生成失败不影响接口其余题，也不写假内容。
 *
 * <p>刻意不加 {@code @Transactional}：每题写入是仓库方法自己的短事务，
 * 单题失败不会把整批打成 rollback-only，也不会回滚已经成功的解析。
 */
@RestController
@RequestMapping("/api/offerings/{offeringId}/explanations")
public class ExplanationEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ExplanationEndpoint.class);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 50;

    private final Ports.ExplanationWorkQueue workQueue;
    private final ItemExplanationRepository explanations;
    private final ExplanationEngine engine;
    private final Clock clock;
    /** 错题解析永远要真模型（stub 不覆盖），所以看 configured；无 identity 装配时放行。 */
    private final ObjectProvider<Ports.AiSettingsLookup> aiSettings;

    public ExplanationEndpoint(Ports.ExplanationWorkQueue workQueue,
                               ItemExplanationRepository explanations,
                               ExplanationEngine engine, Clock clock,
                               ObjectProvider<Ports.AiSettingsLookup> aiSettings) {
        this.workQueue = workQueue;
        this.explanations = explanations;
        this.engine = engine;
        this.clock = clock;
        this.aiSettings = aiSettings;
    }

    public record GenerateResult(int generated, int skippedExisting, int skippedNoStem,
                                 int failed, int total) {
    }

    @PostMapping("/generate")
    public GenerateResult generate(@PathVariable UUID offeringId,
                                   @RequestParam(name = "limit", required = false) Integer limit,
                                   @RequestAttribute("currentUser") CurrentUser me) {
        requireTeacherOrAdmin(me);
        requireAiConfigured();
        int effectiveLimit = clamp(limit);
        List<Ports.ExplanationItem> items = workQueue.wrongItemsOf(offeringId, effectiveLimit);

        int generated = 0;
        int skippedExisting = 0;
        int skippedNoStem = 0;
        int failed = 0;
        for (Ports.ExplanationItem item : items) {
            if (item.stem() == null || item.stem().isBlank()) {
                skippedNoStem++;
                continue;
            }
            String language = ExplanationLanguage.normalize(item.language());
            if (explanations.existsByItemIdAndLanguageAndPromptVersion(
                    item.itemId(), language, ExplanationEngine.PROMPT_VERSION)) {
                skippedExisting++;
                continue;
            }
            try {
                String text = engine.explain(item);
                int inserted = explanations.insertIfAbsent(UUID.randomUUID(), item.itemId(), language,
                        ExplanationEngine.PROMPT_VERSION, text, Instant.now(clock));
                if (inserted > 0) {
                    generated++;
                } else {
                    // 并发下另一个请求先写成功：唯一键保证只有一份，这里如实计为已存在。
                    skippedExisting++;
                }
            } catch (RuntimeException ex) {
                failed++;
                // 只记题目 id 与错误原因，不记录任何学生数据（解析路径本来就没有）。
                log.warn("错题解析生成失败 item={} language={} : {}",
                        item.itemId(), language, ex.getMessage());
            }
        }
        return new GenerateResult(generated, skippedExisting, skippedNoStem, failed, items.size());
    }

    private static int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static void requireTeacherOrAdmin(CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("请先登录");
        }
        if (!me.is("TEACHER") && !me.is("ADMIN")) {
            throw ApiException.forbidden("只有教师或管理员可以生成错题解析");
        }
    }

    /** 错题解析不走 stub 替身：没有可用模型就直接 409，不要逐题失败后才让老师猜原因。 */
    private void requireAiConfigured() {
        Ports.AiSettingsLookup lookup = aiSettings.getIfAvailable();
        if (lookup == null) {
            return;
        }
        if (!lookup.readiness().configured()) {
            throw ApiException.conflict("error.ai.notConfigured");
        }
    }
}
