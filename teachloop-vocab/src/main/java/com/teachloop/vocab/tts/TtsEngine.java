package com.teachloop.vocab.tts;

/** 本地 TTS 引擎的可替换边界（T410/T411）：只接收单词与音色/语速，返回音频字节。 */
public interface TtsEngine {

    byte[] synthesize(String text, String voice, double rate);
}
