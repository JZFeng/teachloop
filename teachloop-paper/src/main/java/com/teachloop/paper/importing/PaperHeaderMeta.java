package com.teachloop.paper.importing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从试卷原文头部读「命题人 / 审题人 / 总分」（D-34，AC-4.27④）。
 *
 * <p>真实卷子的第一屏几乎都写着 {@code **命题人：** 李璐  **审题人：** 刘嘉伦  **总分：** 150 分}。
 * 这些值本来就印在卷面上，让老师再手填一遍是多余的一步，所以「生成考题」时顺手读出来当**提示**：
 * 前端只用它回填空着的字段，不覆盖教师已填的值（AC-4.27④），服务端也不据此改任何数据。</p>
 *
 * <p>刻意做成独立小类而不是改 1359 行的 {@link PaperTextParser}：头部一行不是题目，
 * 不进「部分 → 题项」的结构，混进解析器会让题号切分多一堆特例（plan §20.3）。</p>
 *
 * <p>只认这三个标签，只在前 {@value #SCAN_LINES} 个非空行里找：
 * 「满分」不认——它在每个「第X部分」标题里都会出现（{@code 满分 30 分}），
 * 认了就会把第一节的小节分当成全卷总分。找不到的字段保持 {@code null}。</p>
 */
public final class PaperHeaderMeta {

    /** 头部提示：三个字段都可空，读不到就是 null。 */
    public record Hints(String authorName, String reviewerName, BigDecimal targetTotalPoints) {

        public boolean isEmpty() {
            return authorName == null && reviewerName == null && targetTotalPoints == null;
        }
    }

    /** 只看头部：真实卷的姓名/总分都在第一两行，扫太多行反而容易撞上正文里的「总分」字样。 */
    private static final int SCAN_LINES = 20;

    /** 名字截到空白、下一个标签、标点或表格竖线为止；全文的 {@code **} 已先剥掉。 */
    private static final String NAME = "([^\\s\\u3000:：,，;；|*]+)";
    private static final Pattern AUTHOR = Pattern.compile("命题人\\s*[:：]\\s*" + NAME);
    private static final Pattern REVIEWER = Pattern.compile("审题人\\s*[:：]\\s*" + NAME);
    private static final Pattern TOTAL = Pattern.compile("总分\\s*[:：]\\s*([0-9]+(?:\\.[0-9]+)?)");

    private PaperHeaderMeta() {
    }

    /** 按抽取后的行（已剥 Markdown 装饰）读头部；与解析器吃同一份 {@code lines}。 */
    public static Hints parse(List<DocumentTextExtractor.SourceLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return new Hints(null, null, null);
        }
        String author = null;
        String reviewer = null;
        BigDecimal total = null;
        int scanned = 0;
        for (DocumentTextExtractor.SourceLine line : lines) {
            String text = normalize(line.text());
            if (text.isEmpty()) {
                continue;
            }
            if (++scanned > SCAN_LINES) {
                break;
            }
            if (author == null) {
                author = firstGroup(AUTHOR, text);
            }
            if (reviewer == null) {
                reviewer = firstGroup(REVIEWER, text);
            }
            if (total == null) {
                String raw = firstGroup(TOTAL, text);
                if (raw != null) {
                    total = new BigDecimal(raw);
                }
            }
            if (author != null && reviewer != null && total != null) {
                break;
            }
        }
        return new Hints(author, reviewer, total);
    }

    /** 未抽取的纯文本（例如测试直接塞一份 .md 字符串）也能用：自己按行切、自己剥装饰。 */
    public static Hints parseText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new Hints(null, null, null);
        }
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        List<DocumentTextExtractor.SourceLine> lines = new ArrayList<>();
        int number = 1;
        for (String text : normalized.split("\n", -1)) {
            lines.add(new DocumentTextExtractor.SourceLine(number++, text));
        }
        return parse(lines);
    }

    /** 剥掉加粗/标题/表格竖线这类排版记号，标签与值之间就只剩空白。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("**", "").replace("`", "").replace("__", "").replace('|', ' ').strip();
    }

    private static String firstGroup(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }
}
