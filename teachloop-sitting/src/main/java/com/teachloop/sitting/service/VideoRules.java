package com.teachloop.sitting.service;

import java.util.ArrayList;
import java.util.List;

/** T062：时长 ≤360 秒、宽 ≤1920、高 ≤1080；返回具体超限项。 */
public final class VideoRules {

    public static final double MAX_DURATION_SECONDS = 360;
    public static final int MAX_WIDTH = 1920;
    public static final int MAX_HEIGHT = 1080;

    private VideoRules() {
    }

    public static List<String> violations(VideoProbe.VideoInfo info) {
        List<String> errors = new ArrayList<>();
        if (info.durationSeconds() > MAX_DURATION_SECONDS) {
            errors.add("时长 " + String.format("%.1f", info.durationSeconds()) + " 秒，超过 360 秒");
        }
        if (info.width() > MAX_WIDTH) {
            errors.add("宽度 " + info.width() + "，超过 1920");
        }
        if (info.height() > MAX_HEIGHT) {
            errors.add("高度 " + info.height() + "，超过 1080");
        }
        return errors;
    }
}
