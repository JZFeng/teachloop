package com.teachloop.grading.explanation;

import java.util.Locale;

/**
 * 解析语言口径：与作业 {@code feedback_language} 一致（AC-18.9）。
 *
 * <p>空值统一按 {@code zh-CN} 处理，保证写入端与读取端落在同一个唯一键上；
 * 存库前与查缓存前都调用同一个归一口径，避免「生成时 null、读取时 zh-CN」查不中。
 */
public final class ExplanationLanguage {

    public static final String DEFAULT = "zh-CN";

    private ExplanationLanguage() {
    }

    public static String normalize(String language) {
        return language == null || language.isBlank() ? DEFAULT : language.strip();
    }

    public static boolean isEnglish(String language) {
        return normalize(language).toLowerCase(Locale.ROOT).startsWith("en");
    }
}
