package com.teachloop.vocab.tts;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.storage.BlobStore;
import com.teachloop.vocab.word.WordNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单词发音端点（T410 / US-57）：缓存 → 本地引擎 →（失败时 404，前端回落浏览器）。
 *
 * <p>键 = sha256(规范化词形 | provider | voice | rate)；只朗读单词本身，不读释义与例句。
 * 命中缓存带长期缓存头；引擎失败不让复习卡住——返回 404，前端用 SpeechSynthesis。
 */
@RestController
@RequestMapping("/api/vocab/audio")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class TtsAudioEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TtsAudioEndpoint.class);
    private static final String PREFIX = "vocab/tts/";
    private static final String CONTENT_TYPE = "audio/wav";

    private final BlobStore blobs;
    private final Optional<TtsEngine> engine;
    private final String provider;
    private final String voice;
    private final double rate;

    public TtsAudioEndpoint(BlobStore blobs, Optional<TtsEngine> engine,
                            @Value("${teachloop.vocab.tts.provider:local}") String provider,
                            @Value("${teachloop.vocab.tts.voice:en_US-amy-medium}") String voice,
                            @Value("${teachloop.vocab.tts.rate:1.0}") double rate) {
        this.blobs = blobs;
        this.engine = engine;
        this.provider = provider;
        this.voice = voice;
        this.rate = rate;
    }

    @GetMapping(params = "form")
    public ResponseEntity<byte[]> synthesize(@RequestParam String form) {
        String word = WordNormalizer.display(form);
        if (word.isEmpty() || word.length() > 64) {
            throw ApiException.badRequest("form is required (<=64 chars)");
        }
        String key = key(word);
        Optional<byte[]> cached = load(key);
        if (cached.isPresent()) {
            return audio(cached.get(), key);
        }
        if ("browser".equalsIgnoreCase(provider) || engine.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] audio = engine.get().synthesize(word, voice, rate);
            blobs.store(PREFIX + key + ".wav", audio, CONTENT_TYPE);
            return audio(audio, key);
        } catch (RuntimeException ex) {
            log.warn("本地 TTS 不可用，前端回落浏览器：{}", ex.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{key}")
    public ResponseEntity<byte[]> cached(@PathVariable String key) {
        return load(key)
                .map(bytes -> audio(bytes, key))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<byte[]> load(String key) {
        try {
            return blobs.load(PREFIX + key + ".wav");
        } catch (RuntimeException ex) {
            log.warn("音频缓存不可用：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    private ResponseEntity<byte[]> audio(byte[] bytes, String key) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(CONTENT_TYPE))
                .header(HttpHeaders.ETAG, "\"" + key + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(bytes);
    }

    private String key(String word) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((WordNormalizer.normalized(word) + "|" + provider + "|" + voice + "|"
                    + rate).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
