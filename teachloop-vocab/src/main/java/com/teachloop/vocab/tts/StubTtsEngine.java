package com.teachloop.vocab.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        return toneWav();
    }

    public int calls() {
        return calls.get();
    }

    public String lastText() {
        return lastText;
    }

    /** 0.2s / 440Hz 正弦波 WAV：测试/演示能听到干净短音，不会像文本字节那样变成噪音。 */
    private static byte[] toneWav() {
        int sampleRate = 22050;
        int samples = sampleRate / 5;
        int dataSize = samples * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataSize);
        buffer.put("WAVEfmt ".getBytes());
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes());
        buffer.putInt(dataSize);
        for (int index = 0; index < samples; index++) {
            double angle = 2 * Math.PI * 440 * index / sampleRate;
            buffer.putShort((short) (Math.sin(angle) * 6000));
        }
        return buffer.array();
    }
}
