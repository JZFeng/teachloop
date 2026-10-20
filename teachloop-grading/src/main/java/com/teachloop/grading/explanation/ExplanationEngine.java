package com.teachloop.grading.explanation;

import com.teachloop.grading.ai.LlmCallException;
import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.domain.AiCall;
import com.teachloop.grading.domain.AiCallRepository;
import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 错题解析引擎（T086）：一道题一次调用。
 *
 * <p>宪法第五条的唯一例外：解析需要标准答案，所以 prompt 可以带题干、选项与正确答案；
 * 但入参 {@link Ports.ExplanationItem} 里根本没有学生身份字段，prompt 也只由题目信息拼成。
 * 解析不参与计分，也绝不携带学生的作答或客观得分。
 *
 * <p>调用失败直接向上抛（不写假解析）；由 {@code ExplanationEndpoint} 单题捕获并计入 failed。
 */
public class ExplanationEngine {

    private static final Logger log = LoggerFactory.getLogger(ExplanationEngine.class);

    /** 参与唯一键；升级解析质量时改这里即可让旧版自动失效。 */
    public static final String PROMPT_VERSION = "explanation-v1";

    private static final String CHINESE_SYSTEM = """
            你是班级共用的错题解析作者。请针对一道客观题写一段简短解析：
            说明正确答案为什么成立；给出选项时，还要说明其他选项为什么错。
            只输出纯文本，不要 Markdown、JSON、标题或列表。
            中文解析不超过 120 字，英文解析不超过 120 词。
            解析由全班共用，只谈题目本身，不要提及具体某个人或某次作答。""";

    private static final String ENGLISH_SYSTEM = """
            You write explanations for objective exam questions that the whole class shares.
            Write one short explanation: say why the correct answer is correct and, when options
            are given, why each other option is wrong.
            Return plain text only: no Markdown, no JSON, no headings or lists.
            Keep it within 120 characters for Chinese or 120 words for English.
            The explanation is shared by the whole class: discuss the question itself only,
            never an individual attempt.""";

    /** {@code null} 表示 AI 引擎未启用（{@code teachloop.grading.engine=stub}）。 */
    private final LlmClient llm;
    private final AiCallRepository aiCalls;
    private final Clock clock;

    public ExplanationEngine(LlmClient llm, AiCallRepository aiCalls, Clock clock) {
        this.llm = llm;
        this.aiCalls = aiCalls;
        this.clock = clock;
    }

    /**
     * @return 纯文本解析（已 strip，非空）
     * @throws LlmCallException 引擎未启用、调用失败或模型返回空内容
     */
    public String explain(Ports.ExplanationItem item) {
        if (llm == null) {
            throw new LlmCallException("AI 解析引擎未启用，拒绝生成解析");
        }
        Prompt prompt = buildPrompt(item);
        LlmClient.LlmResponse response = llm.complete(
                new LlmClient.LlmRequest(prompt.system(), prompt.user()));
        recordCall(response);
        String text = response.content() == null ? "" : response.content().strip();
        if (text.isEmpty()) {
            throw new LlmCallException("模型返回的解析为空");
        }
        return text;
    }

    /**
     * prompt 只由题目信息拼成：题型、题干、选项、正确答案、输出语言。
     *
     * <p>刻意不放：学生 id、姓名、任何学生的作答、客观得分。测试会断言这些值不出现。
     */
    Prompt buildPrompt(Ports.ExplanationItem item) {
        String language = ExplanationLanguage.normalize(item.language());
        boolean english = ExplanationLanguage.isEnglish(language);
        StringBuilder user = new StringBuilder();
        user.append("Question type: ").append(dash(item.questionType())).append('\n');
        user.append("Question: ").append(dash(item.stem())).append('\n');
        if (item.options() != null && !item.options().isBlank()) {
            user.append("Options:\n").append(item.options().strip()).append('\n');
        }
        user.append("Correct answer: ").append(dash(item.answerKey())).append('\n');
        user.append("Output language: ").append(language);
        return new Prompt(english ? ENGLISH_SYSTEM : CHINESE_SYSTEM, user.toString());
    }

    private void recordCall(LlmClient.LlmResponse response) {
        try {
            aiCalls.save(new AiCall(UUID.randomUUID(), response.model(), PROMPT_VERSION,
                    response.tokensIn(), response.tokensOut(), response.latencyMs(),
                    response.content(), Instant.now(clock)));
        } catch (RuntimeException ex) {
            // 留痕失败不得掩盖解析结果（与 T083 同口径）。
            log.warn("ai_calls 写入失败，解析继续: {}", ex.getMessage());
        }
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value.strip();
    }

    record Prompt(String system, String user) {
    }
}
