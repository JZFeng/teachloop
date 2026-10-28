package com.teachloop.identity.auth.channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 009 T523：最小 SMTP 客户端（隐式 TLS，465/994）。
 *
 * <p>为什么不用 jakarta.mail：本仓库离线构建，新增依赖会卡在 ~/.m2 无法写入；
 * 这里只用 JDK 自带的 SSLSocket，够发「激活邮件 / 验证码」这类纯文本信。
 * 需要附件或 HTML 富文本时再换 jakarta.mail，调用方（{@link #send}）不变。
 *
 * <p>安全：授权码只在这里作为 AUTH LOGIN 的凭据出现，不打印、不进异常消息。
 */
public final class SmtpMailSender {

    private static final int TIMEOUT_MS = 15_000;

    private final String host;
    private final int port;
    private final String username;
    private final String secret;
    private final String fromAddress;

    public SmtpMailSender(String host, int port, String username, String secret, String fromAddress) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.secret = secret;
        this.fromAddress = fromAddress == null || fromAddress.isBlank() ? username : fromAddress;
    }

    /** 发送一封纯文本信；失败抛 IOException，消息里只含服务器返回码，不含凭据。 */
    public void send(String to, String subject, String body) throws IOException {
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.startHandshake();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                expect(in, "220");
                cmd(in, out, "EHLO teachloop.local", "250");
                cmd(in, out, "AUTH LOGIN", "334");
                cmd(in, out, b64(username), "334");
                cmd(in, out, b64(secret), "235");
                cmd(in, out, "MAIL FROM:<" + fromAddress + ">", "250");
                cmd(in, out, "RCPT TO:<" + to + ">", "250");
                cmd(in, out, "DATA", "354");
                out.write(buildMessage(to, subject, body));
                out.flush();
                expect(in, "250");
                out.write("QUIT\r\n");
                out.flush();
            }
        }
    }

    private String buildMessage(String to, String subject, String body) {
        List<String> lines = new ArrayList<>();
        lines.add("From: " + fromAddress);
        lines.add("To: " + to);
        lines.add("Subject: =?UTF-8?B?" + b64(subject) + "?=");
        lines.add("MIME-Version: 1.0");
        lines.add("Content-Type: text/plain; charset=UTF-8");
        lines.add("Content-Transfer-Encoding: base64");
        lines.add("Date: " + java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)));
        lines.add("");
        lines.addAll(chunk(b64(body)));
        return String.join("\r\n", lines) + "\r\n.\r\n";
    }

    private static List<String> chunk(String s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += 76) {
            out.add(s.substring(i, Math.min(i + 76, s.length())));
        }
        return out;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private void cmd(BufferedReader in, Writer out, String line, String expectCode) throws IOException {
        out.write(line + "\r\n");
        out.flush();
        expect(in, expectCode);
    }

    /** 读多行响应（250- 续行），要求以期望码开头。 */
    private static void expect(BufferedReader in, String code) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("SMTP 连接被关闭");
            }
            if (!line.startsWith(code)) {
                // 只回传服务器状态码与前 60 字符，避免任何凭据意外外泄
                throw new IOException("SMTP 未按预期应答：" + line.substring(0, Math.min(60, line.length())));
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
    }
}
