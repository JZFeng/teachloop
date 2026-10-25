package com.teachloop.vocab.tts;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.storage.BlobStore;
import com.teachloop.vocab.word.WordNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
 * 发音端点（T410 / US-57、019 / AC-57.6/57.8/57.9）：缓存 → 本地引擎 →（失败时 404，前端回落浏览器）。
 *
 * <p>两种请求互斥：
 *
 * <ul>
 *   <li>{@code ?form=...&accent=uk|us}：单词（T410/T427）。词形 ≤64 字符，按规范化词形 + 音色 + 语速
 *       缓存；{@code accent} 只切换音色，不改单词的其它行为。
 *   <li>{@code ?text=...&lang=en|zh|es}：例句（019）。{@code text} 去首尾空白后 ≤200 字符（按 code point
 *       计数，超长返回 400，不静默截断）；{@code lang} 缺省 {@code en}，未知语言 400；音色按语言选
 *       （en→{@code tts.voice}，zh→{@code tts.voices.zh}，es→{@code tts.voices.es}）。例句只在首次点击时
 *       合成，键里含语言与文本，避免与单词音频撞键。
 * </ul>
 *
 * <p>{@code teachloop.vocab.tts.sentences.enabled=false}（AC-57.8）时 {@code text} 请求直接 404，
 * 单词发音不受影响；前端通过 {@code GET /api/vocab/config} 的 {@code sentenceAudio} 决定是否渲染喇叭。
 *
 * <p>命中缓存带长期缓存头；引擎失败不让页面卡住——返回 404，前端用 {@code SpeechSynthesis}。
 */
@RestController
@RequestMapping("/api/vocab/audio")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class TtsAudioEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TtsAudioEndpoint.class);
    private static final String PREFIX = "vocab/tts/";
    private static final String CONTENT_TYPE = "audio/wav";

    /** AC-57.9：例句文本上限 200 字符（按 Unicode code point 计数）。 */
    static final int MAX_SENTENCE_CODEPOINTS = 200;

    /** 例句支持的学习语言（019）；其它值返回 400。 */
    static final Set<String> SENTENCE_LANGS = Set.of("en", "zh", "es");

    private final BlobStore blobs;
    private final Optional<TtsEngine> engine;
    private final String provider;
    private final String voice;
    private final String ukVoice;
    private final String usVoice;
    private final String zhVoice;
    private final String esVoice;
    private final double rate;
    private final boolean sentencesEnabled;

    public TtsAudioEndpoint(
            BlobStore blobs,
            Optional<TtsEngine> engine,
            @Value("${teachloop.vocab.tts.provider:local}") String provider,
            @Value("${teachloop.vocab.tts.voice:en_US-amy-medium}") String voice,
            @Value("${teachloop.vocab.tts.voices.uk:en_GB-alan-medium}") String ukVoice,
            @Value("${teachloop.vocab.tts.voices.us:en_US-amy-medium}") String usVoice,
            @Value("${teachloop.vocab.tts.voices.zh:zh_CN-huayan-medium}") String zhVoice,
            @Value("${teachloop.vocab.tts.voices.es:es_ES-davefx-medium}") String esVoice,
            @Value("${teachloop.vocab.tts.rate:1.0}") double rate,
            @Value("${teachloop.vocab.tts.sentences.enabled:true}") boolean sentencesEnabled) {
        this.blobs = blobs;
        this.engine = engine;
        this.provider = provider;
        this.voice = voice;
        this.ukVoice = ukVoice;
        this.usVoice = usVoice;
        this.zhVoice = zhVoice;
        this.esVoice = esVoice;
        this.rate = rate;
        this.sentencesEnabled = sentencesEnabled;
    }

    /**
     * 单词与例句共用一个入口：{@code form} 与 {@code text} 互斥（同时出现 400）。
     *
     * <p>两个都不带时返回 404（与改造前「没有匹配的映射」一致），避免把裸路径变成 400。
     */
    @GetMapping
    public ResponseEntity<byte[]> synthesize(@RequestParam(required = false) String form,
                                             @RequestParam(required = false) String text,
                                             @RequestParam(required = false) String accent,
                                             @RequestParam(required = false) String lang) {
        if (form != null && text != null) {
            throw ApiException.badRequest("form and text are mutually exclusive");
        }
        if (text != null) {
            return sentence(text, lang);
        }
        if (form != null) {
            return word(form, accent);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/{key}")
    public ResponseEntity<byte[]> cached(@PathVariable String key) {
        return load(key)
                .map(bytes -> audio(bytes, key))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 单词（T410/T427）：≤64 字符，accent 只换音色，行为与改造前一致。 */
    private ResponseEntity<byte[]> word(String form, String accent) {
        String word = WordNormalizer.display(form);
        if (word.isEmpty() || word.length() > 64) {
            throw ApiException.badRequest("form is required (<=64 chars)");
        }
        String voice = voiceFor(accent);
        String key = wordKey(word, voice);
        return cacheOrSynthesize(word, voice, key);
    }

    /**
     * 例句（019 / AC-57.6/57.9）：开关关闭时 404（AC-57.8），否则校验文本与语言后按需合成。
     *
     * <p>顺序固定为「开关 → 文本 → 语言」：关了开关就不再关心请求内容，前端也拿不到音频。
     */
    private ResponseEntity<byte[]> sentence(String rawText, String rawLang) {
        if (!sentencesEnabled) {
            return ResponseEntity.notFound().build();
        }
        String text = rawText.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_SENTENCE_CODEPOINTS) {
            throw ApiException.badRequest(
                    "text is required (<=%d chars)".formatted(MAX_SENTENCE_CODEPOINTS));
        }
        String lang = rawLang == null || rawLang.isBlank()
                ? "en"
                : rawLang.strip().toLowerCase(Locale.ROOT);
        if (!SENTENCE_LANGS.contains(lang)) {
            throw ApiException.badRequest("unsupported lang (expected en|zh|es): " + rawLang);
        }
        String voice = sentenceVoice(lang);
        String key = sentenceKey(lang, text, voice);
        return cacheOrSynthesize(text, voice, key);
    }

    private ResponseEntity<byte[]> cacheOrSynthesize(String text, String voice, String key) {
        Optional<byte[]> cached = load(key);
        if (cached.isPresent()) {
            return audio(cached.get(), key);
        }
        if ("browser".equalsIgnoreCase(provider) || engine.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] audio = engine.get().synthesize(text, voice, rate);
            blobs.store(PREFIX + key + ".wav", audio, CONTENT_TYPE);
            return audio(audio, key);
        } catch (RuntimeException ex) {
            log.warn("本地 TTS 不可用，前端回落浏览器：{}", ex.getMessage());
            return ResponseEntity.notFound().build();
        }
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

    /** T427：uk/us 各用各的音色；未知 accent 退回默认音色，不报错打断播放。 */
    private String voiceFor(String accent) {
        if ("uk".equalsIgnoreCase(accent)) {
            return ukVoice;
        }
        if ("us".equalsIgnoreCase(accent)) {
            return usVoice;
        }
        return voice;
    }

    /** 019：例句按学习语言选音色；en 沿用默认音色（美音）。 */
    private String sentenceVoice(String lang) {
        return switch (lang) {
            case "zh" -> zhVoice;
            case "es" -> esVoice;
            default -> voice;
        };
    }

    private String wordKey(String word, String voice) {
        return sha256("word|" + WordNormalizer.normalized(word) + "|" + provider + "|" + voice + "|"
                + rate);
    }

    private String sentenceKey(String lang, String text, String voice) {
        return sha256("sentence|" + lang + "|" + text + "|" + provider + "|" + voice + "|" + rate);
    }

    private String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
