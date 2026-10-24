package com.teachloop.vocab.lexicon;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 词形规范化（spec §14.2 / plan §3.2 第 4 条）。
 *
 * <p>入库与查询统一用 {@link #normalized(String)}：NFC + 小写 + 去首尾空格。
 * 去变音符索引用 {@link #folded(String)}：NFD 去掉组合附加符（Mn）后拼回 NFC，
 * 让用户不打变音符也能命中（{@code eclat} → {@code éclat}）。
 */
public final class FormKey {

    private FormKey() {
    }

    public static String normalized(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
    }

    public static String folded(String normalized) {
        String decomposed = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        StringBuilder kept = new StringBuilder(decomposed.length());
        decomposed.codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.NON_SPACING_MARK)
                .forEach(kept::appendCodePoint);
        return Normalizer.normalize(kept.toString(), Normalizer.Form.NFC);
    }
}
