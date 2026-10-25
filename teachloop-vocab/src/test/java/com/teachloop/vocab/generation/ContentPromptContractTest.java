package com.teachloop.vocab.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** AC-60.12 / T439：prompt 与请求参数只有一份契约，Java 与 Python 都读它。 */
class ContentPromptContractTest {

    private final ContentPromptContract contract = new ContentPromptContract();

    @Test
    void loadsPromptAndRequestDefaultsFromClasspath() {
        assertThat(contract.promptVersion()).isEqualTo("vocab-content-v2.3");
        assertThat(contract.promptSha256()).hasSize(16);
        assertThat(contract.systemPrompt()).contains("Return ONLY one JSON object");
        assertThat(contract.languages()).containsExactly("zh", "en");
        assertThat(contract.exampleMax()).isEqualTo(6);
        assertThat(contract.exampleMin()).isEqualTo(1);

        ContentPromptContract.RequestDefaults request = contract.request();
        assertThat(request.temperature()).isEqualTo(0.2);
        assertThat(request.temperatureRetry()).isEqualTo(0.1);
        assertThat(request.maxTokens()).isEqualTo(6000);
        assertThat(request.timeout().toSeconds()).isEqualTo(20);
        assertThat(request.userAgent()).isNotBlank();
        assertThat(request.responseFormat().path("type").asString()).isEqualTo("json_object");
    }

    @Test
    void opencodeProfileDisablesThinkingAndSendsSessionHeader() {
        ContentPromptContract.ProviderProfile profile = contract
                .providerFor("https://opencode.ai/inference/go/openai/v1")
                .orElseThrow();
        assertThat(profile.name()).isEqualTo("opencode");
        assertThat(profile.disableThinking()).isEqualTo("thinking");
        assertThat(profile.sendsSessionHeader()).isTrue();
        assertThat(profile.sessionHeader()).isEqualTo("x-opencode-session");
    }

    @Test
    void unknownProviderGetsNoVendorSpecificFields() {
        assertThat(contract.providerFor("https://api.deepseek.com")).isEmpty();
        assertThat(contract.providerFor("")).isEmpty();
        assertThat(contract.providerFor(null)).isEmpty();
    }

    @Test
    void userPromptUsesSameTemplateAsBatchTool() {
        String prompt = contract.userPrompt("apple", "en", 6);
        assertThat(prompt).contains("Word: apple");
        assertThat(prompt).contains("translations exclude en; use zh");
        assertThat(prompt).contains("Example count: up to 6");
        assertThat(prompt).doesNotContain("{word}", "{language}", "{otherLanguages}", "{exampleCount}");
    }

    @Test
    void everyProviderHasBaseUrlAndModelDocumented() {
        assertThat(contract.providerNames()).contains("opencode", "local");
    }

    @Test
    void testContractResourceIsUsableForHttpTests() {
        ContentPromptContract test = new ContentPromptContract("/vocab/content-prompt-test.json");
        assertThat(test.providerFor("http://localhost:1234/v1")).isPresent();
        assertThat(test.providerFor("http://127.0.0.1:1234/v1")).isEmpty();
        assertThat(test.languages()).isEqualTo(List.of("zh", "en"));
    }
}
