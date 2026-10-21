package com.teachloop.sitting.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.sitting.service.RubricValidator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

class RubricIssueTextTest {

    private static MessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.setLocale(Locale.getDefault());
    }

    @Test
    void rendersChineseAndEnglishWithArguments() {
        List<RubricValidator.Issue> issues =
                List.of(RubricValidator.Issue.of("error.rubric.missingPoints", "内容要点"));

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertThat(RubricIssueText.render(messages(), issues))
                .containsExactly("评分项「内容要点」缺少分值");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(RubricIssueText.render(messages(), issues))
                .containsExactly("Rubric criterion \"内容要点\" has no points.");
    }

    @Test
    void joinsIssuesWithLocaleSeparator() {
        List<RubricValidator.Issue> issues = List.of(
                RubricValidator.Issue.of("error.rubric.empty"),
                RubricValidator.Issue.of("error.rubric.duplicate", "Content"));

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertThat(RubricIssueText.join(messages(), issues))
                .isEqualTo("评分标准不能为空；评分项名称重复：Content");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(RubricIssueText.join(messages(), issues))
                .isEqualTo("The rubric is empty; Duplicate rubric criterion name: Content");
    }
}
