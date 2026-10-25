package com.teachloop.vocab.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.shared.port.Ports;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AC-60.12 的真实调用冒烟（不跑 CI，默认关闭）。
 *
 * <p>跑法：{@code OPENCODE_GO_KEY=... ./mvnw -pl teachloop-vocab -am test \
 * -Dtest=AiVocabularyContentGeneratorLiveTest -Dvocab.live-ai=true}
 *
 * <p>只验三件事：真端点能出 v2 内容、结构可用、单请求耗时落进 AC-60.12 的区间（打印实测值，
 * 不做硬断言以免网络抖动误判）。成本约 $0.002。
 */
@EnabledIfSystemProperty(named = "vocab.live-ai", matches = "true")
class AiVocabularyContentGeneratorLiveTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContentPromptContract contract = new ContentPromptContract();

    @Test
    void generatesV2ContentFromRealEndpoint() {
        String key = System.getenv("OPENCODE_GO_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "OPENCODE_GO_KEY 未设置，跳过真实调用");

        String baseUrl = contract.providerBaseUrl("opencode");
        String model = contract.providerModel("opencode");
        Ports.AiSettingsLookup settings = new Ports.AiSettingsLookup() {
            @Override
            public Optional<Ports.AiSettings> current() {
                return Optional.of(new Ports.AiSettings("opencode", baseUrl, model, key,
                        new BigDecimal("0.2"), true));
            }

            @Override
            public Optional<Ports.AiSettings> resolve(UUID teacherId) {
                return current();
            }
        };
        AiVocabularyContentGenerator generator = new AiVocabularyContentGenerator(settings, contract, 0);

        List<Long> latencies = new ArrayList<>();
        for (String word : List.of("apple", "resilient", "serendipity")) {
            long started = System.nanoTime();
            String content = generator.generate(word, "en");
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            latencies.add(elapsedMs);
            JsonNode parsed = mapper.readTree(content);
            System.out.printf("[live] %-12s %5dms %5dB examples=%d%n",
                    word, elapsedMs, content.length(), parsed.path("examples").size());
            assertThat(parsed.path("schemaVersion").asInt()).isEqualTo(2);
            assertThat(parsed.path("word").asString()).isEqualToIgnoringCase(word);
            assertThat(parsed.path("examples").size()).isBetween(1, 6);
            assertThat(parsed.path("meanings").path("zh").asString()).isNotBlank();
            assertThat(parsed.path("meanings").path("en").asString()).isNotBlank();
        }
        latencies.sort(Long::compareTo);
        long p50 = latencies.get(latencies.size() / 2);
        System.out.printf("[live] p50=%dms all=%s (AC-60.12 目标 p50<=7000ms)%n", p50, latencies);
        assertThat(p50).isLessThan(12_000);
    }
}
