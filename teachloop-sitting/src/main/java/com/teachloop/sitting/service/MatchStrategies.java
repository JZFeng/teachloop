package com.teachloop.sitting.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** T053：exact / normalized / choice / set 四种策略。 */
public final class MatchStrategies {

    private static final Pattern PUNCT = Pattern.compile("[\\p{Punct}，。；：！？、“”‘’（）【】《》·…—]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern SEPARATOR = Pattern.compile("[,;，；\\s]+");

    public static final MatchStrategy EXACT = new MatchStrategy() {
        @Override
        public String id() {
            return "exact";
        }

        @Override
        public boolean matches(String expected, String given) {
            return expected != null && expected.equals(given);
        }
    };

    /** 大小写、首尾空白、连续空白、标点都不影响。 */
    public static final MatchStrategy NORMALIZED = new MatchStrategy() {
        @Override
        public String id() {
            return "normalized";
        }

        @Override
        public boolean matches(String expected, String given) {
            return normalize(expected).equals(normalize(given));
        }
    };

    /** 选择题：只比较字母集合，AB 与 BA 等价。 */
    public static final MatchStrategy CHOICE = new MatchStrategy() {
        @Override
        public String id() {
            return "choice";
        }

        @Override
        public boolean matches(String expected, String given) {
            Set<String> a = letters(expected);
            Set<String> b = letters(given);
            return !a.isEmpty() && a.equals(b);
        }
    };

    /** 填空多写法：按逗号/分号/空白拆分后比较集合，顺序无关。 */
    public static final MatchStrategy SET = new MatchStrategy() {
        @Override
        public String id() {
            return "set";
        }

        @Override
        public boolean matches(String expected, String given) {
            Set<String> a = parts(expected);
            Set<String> b = parts(given);
            return !a.isEmpty() && a.equals(b);
        }
    };

    private static final List<MatchStrategy> ALL = List.of(EXACT, NORMALIZED, CHOICE, SET);

    private MatchStrategies() {
    }

    public static MatchStrategy get(String id) {
        return ALL.stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知匹配策略: " + id));
    }

    public static List<MatchStrategy> all() {
        return ALL;
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return SPACE.matcher(PUNCT.matcher(s.trim().toLowerCase(Locale.ROOT)).replaceAll("")).replaceAll(" ").trim();
    }

    static Set<String> letters(String s) {
        Set<String> out = new TreeSet<>();
        if (s == null) {
            return out;
        }
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                out.add(String.valueOf(c));
            }
        }
        return out;
    }

    static Set<String> parts(String s) {
        Set<String> out = new TreeSet<>();
        if (s == null) {
            return out;
        }
        for (String part : SEPARATOR.split(s.trim())) {
            if (!part.isBlank()) {
                out.add(normalize(part));
            }
        }
        return out;
    }
}
