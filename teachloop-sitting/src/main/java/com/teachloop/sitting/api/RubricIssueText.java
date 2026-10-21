package com.teachloop.sitting.api;

import com.teachloop.sitting.service.RubricValidator;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 把 {@link RubricValidator.Issue} 的消息 key + 参数按请求语言渲染成文字（T258）。
 *
 * <p>校验器本身不依赖 Spring，只返回 key；两个出口（预览与布置）在这里本地化，
 * 避免 service 里出现中文界面文案。</p>
 */
final class RubricIssueText {

    private RubricIssueText() {
    }

    static List<String> render(MessageSource messages, List<RubricValidator.Issue> issues) {
        Locale locale = LocaleContextHolder.getLocale();
        return issues.stream()
                .map(issue -> messages.getMessage(
                        issue.code(), issue.args().toArray(), issue.code(), locale))
                .toList();
    }

    static String join(MessageSource messages, List<RubricValidator.Issue> issues) {
        Locale locale = LocaleContextHolder.getLocale();
        String separator = messages.getMessage("error.rubric.separator", null, "; ", locale);
        return String.join(separator, render(messages, issues));
    }
}
