package com.teachloop.identity.auth.channel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 009 T523：默认发送实现（真 SMTP）。测试用 @TestConfiguration 覆盖这个 Bean。 */
@Configuration
public class ChannelConfig {

    @Bean
    public SmtpMailSenderFactory smtpMailSenderFactory() {
        return (host, port, username, secret, from, to, subject, body) ->
                new SmtpMailSender(host, port, username, secret, from).send(to, subject, body);
    }
}
