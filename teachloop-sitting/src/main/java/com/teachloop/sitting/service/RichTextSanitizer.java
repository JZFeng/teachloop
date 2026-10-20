package com.teachloop.sitting.service;

import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * 富文本消毒（T060 / AC-6.2 / AC-6.3）。
 *
 * <p>白名单只允许基础格式：段落、换行、粗体、斜体、下划线、有序/无序列表、引用、h1–h3；
 * <b>任何属性都不保留</b>，因此链接、图片、表格、样式与 {@code on*} 事件处理属性都会被丢掉。
 * 消毒后的 HTML 存 {@code content_html}，同时从干净的 DOM 里提取纯文本存 {@code content_text}
 * 供 AI 使用（模型永远看不到标签）。
 */
@Component
public class RichTextSanitizer {

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "br", "strong", "em", "u", "ol", "ul", "li", "blockquote", "h1", "h2", "h3");

    private static final Safelist SAFELIST = new Safelist().addTags(ALLOWED_TAGS.toArray(String[]::new));

    /**
     * @param html     消毒后的 HTML；输入为空时 null
     * @param text     从消毒后 DOM 提取的纯文本；为空时 null
     * @param stripped 是否丢弃过标签/属性（用于写 security:rich_text_stripped 审计）
     */
    public record Sanitized(String html, String text, boolean stripped) {
    }

    public Sanitized sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return new Sanitized(null, null, false);
        }
        Document dirty = Jsoup.parseBodyFragment(rawHtml);
        boolean stripped = false;
        for (Element element : dirty.body().getAllElements()) {
            if (element == dirty.body()) {
                continue;
            }
            // 不在白名单里的标签（script/style/a/img/table…）或任何属性（on*/href/style…）都算被剥离
            if (!ALLOWED_TAGS.contains(element.tagName()) || !element.attributes().isEmpty()) {
                stripped = true;
                break;
            }
        }
        String clean = Jsoup.clean(dirty.body().html(), SAFELIST);
        String text = Jsoup.parseBodyFragment(clean).body().text();
        return new Sanitized(clean.isBlank() ? null : clean, text.isBlank() ? null : text, stripped);
    }
}
