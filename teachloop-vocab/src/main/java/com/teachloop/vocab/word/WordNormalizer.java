package com.teachloop.vocab.word;

import com.teachloop.vocab.lexicon.FormKey;
import java.text.Normalizer;
import java.util.Locale;

/**
 * 词条规范化（T393 / FR-093 / AC-46.3）。
 *
 * <p>展示形态：NFC + 全角转半角 + 去首尾空格；去重键：再小写、再去变音符
 * （T393 验收要求「大小写 / 全角 / 变音符去重」，与 lexicon 的去变音符索引同一口径）。
 * 不做词形还原（spec A-6：run / running 是两个词）。
 */
public final class WordNormalizer {

    private WordNormalizer() {
    }

    public static String display(String raw) {
        if (raw == null) {
            return "";
        }
        return toHalfWidth(Normalizer.normalize(raw, Normalizer.Form.NFC)).strip();
    }

    public static String normalized(String raw) {
        String display = display(raw).toLowerCase(Locale.ROOT);
        return FormKey.folded(display);
    }

    /** 正面必填且 ≤64 字符（AC-46.2）；按 Unicode code point 计数，不按 UTF-16 单元。 */
    public static boolean valid(String raw) {
        String display = display(raw);
        return !display.isEmpty() && display.codePointCount(0, display.length()) <= 64;
    }

    static String toHalfWidth(String text) {
        StringBuilder output = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\u3000') {
                output.append(' ');
            } else if (ch >= '\uFF01' && ch <= '\uFF5E') {
                output.append((char) (ch - 0xFEE0));
            } else {
                output.append(ch);
            }
        }
        return output.toString();
    }
}
