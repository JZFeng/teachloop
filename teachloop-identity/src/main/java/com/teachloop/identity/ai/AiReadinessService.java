package com.teachloop.identity.ai;

import com.teachloop.shared.port.Ports;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 就绪判断（唯一真源）。
 *
 * <p>两个概念必须分开，否则要么漏拦、要么误拦：
 * <ul>
 *   <li>{@code configured}：有可用模型 = 当前 provider 已选 <b>或</b> 环境变量
 *       {@code DEEPSEEK_API_KEY} 非空。错题解析、背单词内容生成永远要真模型，只看这个。</li>
 *   <li>{@code ready}：现在批改能不能真的出分 = {@code configured || engine == stub}。
 *       批改引擎的 stub 是开发/QA 替身，没有 Key 也能出分，所以派发入口看这个。</li>
 * </ul>
 *
 * <p>identity 只读 {@code teachloop.grading.engine} 这个部署级开关，用来判断 stub 替身是否免 Key；
 * 它不决定引擎怎么装配（那是 grading 的 {@code AiGradingConfig} 的职责），也不写任何配置。
 */
@Service
public class AiReadinessService {

    private final AiSettingsService settings;
    private final String engine;
    private final String envApiKey;

    public AiReadinessService(AiSettingsService settings,
                              @Value("${teachloop.grading.engine:ai}") String engine,
                              @Value("${DEEPSEEK_API_KEY:}") String envApiKey) {
        this.settings = settings;
        this.engine = normalizeEngine(engine);
        this.envApiKey = envApiKey;
    }

    public Ports.AiReadiness readiness() {
        Optional<Ports.AiSettings> provider = settings.lookup().filter(Ports.AiSettings::configured);
        boolean envConfigured = envApiKey != null && !envApiKey.isBlank();
        boolean configured = provider.isPresent() || envConfigured;
        boolean stub = "stub".equals(engine);
        boolean ready = configured || stub;
        String source = provider.isPresent() ? "provider"
                : envConfigured ? "env"
                : stub ? "stub"
                : null;
        return new Ports.AiReadiness(configured, engine, ready, source,
                provider.map(Ports.AiSettings::provider).orElse(null),
                provider.map(Ports.AiSettings::model).orElse(null));
    }

    /** 契约只有 ai / stub 两个值；未知或空值一律按真实模型（ai）处理，宁可多拦也不误放。 */
    private static String normalizeEngine(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ai";
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return "stub".equals(value) ? "stub" : "ai";
    }
}
