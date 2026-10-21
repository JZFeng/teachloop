package com.teachloop.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * T245：业务异常的消息如果是消息 key，就按请求语言解析；如果是历史写死的中文原文，原样返回。
 *
 * <p>这条兼容是「存量不动、新代码可用 key」的前提：291 处存量字面量继续按原样返回，
 * 不会因为改了异常处理器而全部变成英文。</p>
 */
class GlobalExceptionHandlerTest {

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    private static String messageFor(ApiException ex, Locale locale) {
        LocaleContextHolder.setLocale(locale);
        ApiError body = new GlobalExceptionHandler(messageSource()).handleApi(ex).getBody();
        assertThat(body).isNotNull();
        return body.message();
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("消息是 key：英文请求得英文，中文请求得中文")
    void localizesMessageKeyByAcceptLanguage() {
        String zh = messageFor(ApiException.badRequest("error.review.notSubmitted"), Locale.SIMPLIFIED_CHINESE);
        String en = messageFor(ApiException.badRequest("error.review.notSubmitted"), Locale.ENGLISH);

        assertThat(zh).contains("还没有交卷");
        assertThat(en).contains("has not been submitted");
        assertThat(zh).isNotEqualTo(en);
    }

    @Test
    @DisplayName("消息是历史中文原文：不查资源，原样返回")
    void keepsRawLiteralUnchanged() {
        String zh = messageFor(ApiException.badRequest("每题分值不能为负数"), Locale.SIMPLIFIED_CHINESE);
        String en = messageFor(ApiException.badRequest("每题分值不能为负数"), Locale.ENGLISH);

        assertThat(zh).isEqualTo("每题分值不能为负数");
        assertThat(en).isEqualTo("每题分值不能为负数");
    }

    @Test
    @DisplayName("空消息与 null 不炸")
    void toleratesEmptyMessage() {
        assertThat(messageFor(ApiException.badRequest(""), Locale.ENGLISH)).isEmpty();
        assertThat(messageFor(ApiException.badRequest(null), Locale.ENGLISH)).isNull();
    }
}
