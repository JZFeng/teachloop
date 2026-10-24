package com.teachloop.vocab.tts;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 测试/演示用的确定性引擎：记录调用次数与最后一次文本（验证只读单词、二次播放不调引擎）。 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.tts.engine", havingValue = "stub")
public class StubTtsEngine implements TtsEngine {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile String lastText = "";

    @Override
    public byte[] synthesize(String text, String voice, double rate) {
        calls.incrementAndGet();
        lastText = text;
        return ("stub-audio:" + text).getBytes(StandardCharsets.UTF_8);
    }

    public int calls() {
        return calls.get();
    }

    public String lastText() {
        return lastText;
    }
}
