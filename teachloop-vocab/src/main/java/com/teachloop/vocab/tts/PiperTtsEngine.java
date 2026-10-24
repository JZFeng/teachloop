package com.teachloop.vocab.tts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Piper sidecar（T411）：127.0.0.1:5002，2 秒超时；失败由端点回落浏览器。 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.tts.engine", havingValue = "piper", matchIfMissing = true)
public class PiperTtsEngine implements TtsEngine {

    private final String engineUrl;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public PiperTtsEngine(
            @Value("${teachloop.vocab.tts.engine-url:http://127.0.0.1:5002}") String engineUrl,
            @Value("${teachloop.vocab.tts.timeout-ms:2000}") long timeoutMs) {
        this.engineUrl = engineUrl.endsWith("/") ? engineUrl.substring(0, engineUrl.length() - 1) : engineUrl;
        this.timeout = Duration.ofMillis(Math.max(200, timeoutMs));
    }

    @Override
    public byte[] synthesize(String text, String voice, double rate) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "text", text, "voice", voice, "rate", rate));
            HttpRequest request = HttpRequest.newBuilder(URI.create(engineUrl + "/synthesize"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2 || response.body() == null || response.body().length == 0) {
                throw new IllegalStateException("TTS engine returned " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TTS interrupted", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("TTS unavailable: " + ex.getMessage(), ex);
        }
    }
}
