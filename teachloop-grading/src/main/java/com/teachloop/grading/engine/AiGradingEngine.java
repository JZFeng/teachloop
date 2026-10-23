package com.teachloop.grading.engine;

import com.teachloop.grading.ai.GradeOutputValidator;
import com.teachloop.grading.ai.InvalidGradeOutputException;
import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.ai.LlmClientProvider;
import com.teachloop.grading.domain.AiCall;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.grading.prompt.EssayRubricWeights;
import com.teachloop.grading.prompt.PromptBuilder;
import com.teachloop.grading.prompt.SubjectiveGradeRequest;
import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实 AI 判分引擎（T080–T083）。
 *
 * <p>职责链：组装只含主观信息的请求 → 构造 prompt → 调模型 → 每次调用写 ai_calls →
 * 校验输出；输出不合法时带纠正提示重试一次，仍失败则抛出，由 GradingWorker 标 failed。
 *
 * <p>调用失败（网络/鉴权/非 2xx）不重试：那不是模型「输出不合法」，重发同样会失败。
 */
public class AiGradingEngine implements GradingEngine {

    private static final Logger log = LoggerFactory.getLogger(AiGradingEngine.class);

    private static final int MAX_ATTEMPTS = 2;

    private final PromptBuilder promptBuilder;
    private final GradeOutputValidator validator;
    private final LlmClientProvider clients;
    private final AiCallRepository aiCalls;
    private final Clock clock;
    private final EssayRubricWeights essayWeights;

    /** 兼容旧测试/调用：固定客户端。 */
    public AiGradingEngine(PromptBuilder promptBuilder, GradeOutputValidator validator, LlmClient llm,
                           AiCallRepository aiCalls, Clock clock) {
        this(promptBuilder, validator, (LlmClientProvider) teacherId -> llm, aiCalls, clock,
                EssayRubricWeights.DEFAULT);
    }

    public AiGradingEngine(PromptBuilder promptBuilder, GradeOutputValidator validator,
                           LlmClientProvider clients, AiCallRepository aiCalls, Clock clock) {
        this(promptBuilder, validator, clients, aiCalls, clock, EssayRubricWeights.DEFAULT);
    }

    /** 指定作文默认评分比例（D-28）；没有 rubric 的作文用它合成「内容 + 语法与拼写」。 */
    public AiGradingEngine(PromptBuilder promptBuilder, GradeOutputValidator validator,
                           LlmClientProvider clients, AiCallRepository aiCalls, Clock clock,
                           EssayRubricWeights essayWeights) {
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.clients = clients;
        this.aiCalls = aiCalls;
        this.clock = clock;
        this.essayWeights = essayWeights == null ? EssayRubricWeights.DEFAULT : essayWeights;
    }

    /** 逐篇判分（D-26）：一道作文一次调用，合并交给 {@link SubjectiveGradingLoop}。 */
    @Override
    public Ports.GradingResult grade(Ports.WorkItem item) {
        return SubjectiveGradingLoop.grade(item, this::gradeEssay);
    }

    private Ports.GradingResult gradeEssay(Ports.WorkItem item, Ports.ItemSpec essay) {
        SubjectiveGradeRequest request = SubjectiveGradeRequest.forEssay(item, essay, essayWeights);
        if (request.isEmpty()) {
            return new Ports.GradingResult(null, BigDecimal.ZERO, List.of(), null, List.of());
        }
        PromptBuilder.Prompt prompt = promptBuilder.build(request);
        String user = prompt.user();
        InvalidGradeOutputException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                user = prompt.user() + correction(request.feedbackLanguage(), lastFailure.getMessage());
            }
            LlmClient llm = clients.forTeacher(item.createdBy());
            LlmClient.LlmResponse response = llm.complete(
                    new LlmClient.LlmRequest(prompt.system(), user));
            recordCall(prompt.promptVersion(), response);
            try {
                Ports.GradingResult result = validator.validate(response.content(), request);
                return new Ports.GradingResult(result.objectivePoints(), result.subjectivePoints(),
                        result.criteria(), result.feedback(), result.explanations(),
                        result.overall(), result.strength(), result.suggestion(),
                        response.model(), prompt.promptVersion(), clients.source(item.createdBy()));
            } catch (InvalidGradeOutputException ex) {
                lastFailure = ex;
                log.warn("AI 输出不合法（第 {} 次）: {}", attempt, ex.getMessage());
            }
        }
        throw new InvalidGradeOutputException(
                "连续 " + MAX_ATTEMPTS + " 次输出不合法：" + lastFailure.getMessage(), lastFailure);
    }

    private void recordCall(String promptVersion, LlmClient.LlmResponse response) {
        try {
            aiCalls.save(new AiCall(UUID.randomUUID(), response.model(), promptVersion,
                    response.tokensIn(), response.tokensOut(), response.latencyMs(),
                    response.content(), Instant.now(clock)));
        } catch (RuntimeException ex) {
            // 留痕失败不得掩盖判分结果（T083）。
            log.warn("ai_calls 写入失败，判分继续: {}", ex.getMessage());
        }
    }

    private static String correction(String language, String reason) {
        boolean english = language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
        if (english) {
            return "\n\n## Correction\nYour previous output was invalid: " + reason
                    + ". Return only one JSON object that follows the output contract exactly "
                    + "(criteria names, order, maximum points and total rule).";
        }
        return "\n\n## 纠正提示\n上一次输出不合法：" + reason
                + "。请只输出符合输出契约的 JSON 对象；criteria 的名称、顺序、满分与 total 规则必须完全一致。";
    }
}
