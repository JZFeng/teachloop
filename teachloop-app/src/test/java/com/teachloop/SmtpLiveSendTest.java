package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.auth.channel.SmtpMailSender;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 009 T523 的真发信自检（默认不跑，只在显式 -Dsmtp.live=true 时执行）。
 *
 * <p>授权码从 {@code ~/.teachloop-smtp.txt} 读，绝不打印；收件人默认发给自己。
 */
@EnabledIfSystemProperty(named = "smtp.live", matches = "true")
class SmtpLiveSendTest {

    @Test
    @DisplayName("用真实 163 SMTP 发一封自检信")
    void sendRealMail() throws Exception {
        Path tokenFile = Path.of(System.getProperty("user.home"), ".teachloop-smtp.txt");
        assertThat(Files.exists(tokenFile)).as("授权码文件不存在").isTrue();
        String secret = Files.readString(tokenFile).trim();
        assertThat(secret).hasSizeGreaterThanOrEqualTo(8);

        String account = "19725156124@163.com";
        SmtpMailSender sender = new SmtpMailSender(new SmtpMailSender.Settings(
                "smtp.163.com", 465, account, secret, account, "TeachLoop",
                SmtpMailSender.Security.SSL));
        sender.send(account, "TeachLoop 邮件通道自检",
                "这是一封来自 TeachLoop 的通道自检邮件。\n\n"
                        + "如果你收到它，说明注册激活邮件这条链路可以工作。\n"
                        + "时间：" + java.time.ZonedDateTime.now() + "\n");
    }
}
