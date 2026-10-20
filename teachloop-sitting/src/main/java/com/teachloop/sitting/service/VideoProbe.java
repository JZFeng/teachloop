package com.teachloop.sitting.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** T062：调用 ffprobe 读取视频时长与宽高；不猜时长。 */
@Component
public class VideoProbe {

    public record VideoInfo(double durationSeconds, int width, int height) {
    }

    public static final class ProbeException extends RuntimeException {
        public ProbeException(String message) {
            super(message);
        }
    }

    private final String ffprobe;
    private final ObjectMapper mapper;

    public VideoProbe(@Value("${teachloop.video.ffprobe:ffprobe}") String ffprobe, ObjectMapper mapper) {
        this.ffprobe = ffprobe;
        this.mapper = mapper;
    }

    public VideoInfo probe(Path file) {
        List<String> cmd = new ArrayList<>(List.of(ffprobe, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,duration", "-of", "json", file.toString()));
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new ProbeException("无法读取视频信息，请确认文件可播放");
            }
            var json = mapper.readTree(output);
            var stream = json.has("streams") && json.get("streams").isArray() && json.get("streams").size() > 0
                    ? json.get("streams").get(0) : null;
            if (stream == null || !stream.has("width") || !stream.has("height")) {
                throw new ProbeException("视频没有可识别的画面轨道");
            }
            double duration = stream.has("duration") ? Double.parseDouble(stream.get("duration").asString()) : 0;
            if (duration <= 0) {
                throw new ProbeException("无法读取视频时长");
            }
            return new VideoInfo(duration, stream.get("width").asInt(), stream.get("height").asInt());
        } catch (IOException ex) {
            throw new ProbeException("服务器缺少 ffprobe，无法校验视频");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProbeException("视频校验被中断");
        } catch (NumberFormatException ex) {
            throw new ProbeException("视频时长格式无法识别");
        }
    }
}
