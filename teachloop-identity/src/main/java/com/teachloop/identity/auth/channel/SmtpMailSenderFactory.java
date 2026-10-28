package com.teachloop.identity.auth.channel;

import java.io.IOException;

/** 009 T523：把「真发一封信」这一步抽成可替换的接口，测试里换成记录型假实现。 */
@FunctionalInterface
public interface SmtpMailSenderFactory {

    void send(String host, int port, String username, String secret, String fromAddress,
              String to, String subject, String body) throws IOException;
}
