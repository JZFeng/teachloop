package com.teachloop.identity.auth.channel;

import java.io.IOException;

/**
 * 009 T523：把「真发一封信」这一步抽成可替换的接口，测试里换成记录型假实现。
 *
 * <p>2026-10-28：参数收成 {@link SmtpMailSender.Settings}——管理端新增「发件人名称 / 加密方式」，
 * 再按位置传 9 个参数就没人看得懂哪个是哪个了。
 */
@FunctionalInterface
public interface SmtpMailSenderFactory {

    void send(SmtpMailSender.Settings settings, String to, String subject, String body) throws IOException;
}
