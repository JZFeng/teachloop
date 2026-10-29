package com.teachloop.vocab.tts;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 背单词功能的前端能力开关（019 / AC-57.8）。
 *
 * <p>前端启动时拉一次 {@code GET /api/vocab/config}，用 {@code sentenceAudio} 决定例句旁边是否渲染喇叭。
 * 这样「例句朗读效果不好」只需把环境变量 {@code VOCAB_TTS_SENTENCES_ENABLED} 改成 false 重部署，
 * 不用发前端版本；关闭后 {@code /api/vocab/audio?text=} 返回 404（见 {@link TtsAudioEndpoint}）。
 */
@RestController
@RequestMapping("/api/vocab")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabConfigEndpoint {

    private final boolean sentenceAudio;
    private final boolean quiz;

    public VocabConfigEndpoint(
            @Value("${teachloop.vocab.tts.sentences.enabled:true}") boolean sentenceAudio,
            @Value("${teachloop.vocab.quiz.enabled:false}") boolean quiz) {
        this.sentenceAudio = sentenceAudio;
        this.quiz = quiz;
    }

    /** 前端启动时拉一次，决定例句喇叭与「小测」入口是否渲染（AC-57.8 / AC-87.10）。 */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("sentenceAudio", sentenceAudio, "quiz", quiz);
    }
}
