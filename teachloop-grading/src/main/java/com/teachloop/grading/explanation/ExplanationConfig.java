package com.teachloop.grading.explanation;

import com.teachloop.grading.ai.LlmClient;
import com.teachloop.grading.domain.AiCallRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 解析引擎装配。
 *
 * <p>Bean 无条件注册，学生反馈（sitting）永远拿得到 {@code ExplanationCatalog}；
 * 但真正调用模型要求 {@code teachloop.grading.engine=ai} 且配了 {@code DEEPSEEK_API_KEY}。
 * 未启用时引擎在每次生成时抛错，由接口计入 failed，不会写假内容。
 */
@Configuration
public class ExplanationConfig {

    @Bean
    public ExplanationEngine explanationEngine(ObjectProvider<LlmClient> llmClient,
                                               AiCallRepository aiCalls, Clock clock) {
        return new ExplanationEngine(llmClient.getIfAvailable(), aiCalls, clock);
    }
}
