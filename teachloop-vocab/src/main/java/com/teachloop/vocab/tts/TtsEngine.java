package com.teachloop.vocab.tts;

/** 本地 TTS 引擎的可替换边界（T410/T411）：只接收单词与音色/语速，返回音频字节。 */
public interface TtsEngine {

    byte[] synthesize(String text, String voice, double rate);

    /** 返回音频的 MIME 类型；本地 Piper 输出 WAV（不能标成 audio/mpeg，否则结尾会解码出噪音）。 */
    default String contentType() {
        return "audio/wav";
    }
}
