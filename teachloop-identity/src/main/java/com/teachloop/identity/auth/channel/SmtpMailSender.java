package com.teachloop.identity.auth.channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 009 T523：最小 SMTP 客户端。
 *
 * <p>为什么不用 jakarta.mail：本仓库离线构建，新增依赖会卡在 ~/.m2 无法写入；
 * 这里只用 JDK 自带的 Socket / SSLSocket，够发「激活邮件 / 验证码」这类纯文本信。
 *
 * <p>加密方式（2026-10-28 按其三档补齐，对应管理端「加密方式」单选）：
 * <ul>
 *   <li>{@link Security#SSL}：隐式 TLS，连上就握手（465 / 994，163、QQ 的默认）；</li>
 *   <li>{@link Security#STARTTLS}：先明文 EHLO，再 {@code STARTTLS} 升级（587 / 25）；</li>
 *   <li>{@link Security#NONE}：不加密（只用于内网中继；公网会明文暴露授权码）。</li>
 * </ul>
 *
 * <p>安全：授权码只在这里作为 AUTH LOGIN 的凭据出现，不打印、不进异常消息。
 */
public final class SmtpMailSender {

    private static final int TIMEOUT_MS = 15_000;

    /** 传输加密方式；管理端「加密方式」三项与之一一对应。 */
    public enum Security { SSL, STARTTLS, NONE }

    /**
     * 一次发送所需的全部连接参数。
     *
     * <p>{@code fromName} 只进 {@code From:} 头的显示名（RFC 2047 编码），
     * 不参与 {@code MAIL FROM} 与认证——信封地址必须是纯邮箱，否则 163 直接 553。
     */
    public record Settings(String host, int port, String username, String secret,
                           String fromAddress, String fromName, Security security) {
    }

    private final Settings settings;

    public SmtpMailSender(Settings settings) {
        this.settings = settings;
    }

    /** 发送一封纯文本信；失败抛 IOException，消息里只含服务器返回码，不含凭据。 */
    public void send(String to, String subject, String body) throws IOException {
        Socket socket = null;
        try {
            socket = connect();
            BufferedReader in = reader(socket);
            Writer out = writer(socket);
            expect(in, "220");

            ehlo(in, out);
            if (settings.security() == Security.STARTTLS) {
                cmd(in, out, "STARTTLS", "220");
                // 不关流：关掉会连带关掉底层 socket，STARTTLS 就没法在同一个连接上升级。
                socket = upgrade(socket);
                in = reader(socket);
                out = writer(socket);
                ehlo(in, out);
            }

            cmd(in, out, "AUTH LOGIN", "334");
            cmd(in, out, b64(settings.username()), "334");
            cmd(in, out, b64(settings.secret()), "235");
            cmd(in, out, "MAIL FROM:<" + fromAddress() + ">", "250");
            cmd(in, out, "RCPT TO:<" + to + ">", "250");
            cmd(in, out, "DATA", "354");
            out.write(buildMessage(to, subject, body));
            out.flush();
            expect(in, "250");
            out.write("QUIT\r\n");
            out.flush();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响「信已发出」的结论
                }
            }
        }
    }

    private Socket connect() throws IOException {
        if (settings.security() == Security.SSL) {
            SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.startHandshake();
            return socket;
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(settings.host(), settings.port()), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        return socket;
    }

    /** STARTTLS：在已连接的明文 socket 上分层升级为 TLS（对端证书走 JDK 默认信任库）。 */
    private Socket upgrade(Socket plain) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) factory.createSocket(plain, settings.host(), settings.port(), true);
        tls.setSoTimeout(TIMEOUT_MS);
        tls.startHandshake();
        return tls;
    }

    private void ehlo(BufferedReader in, Writer out) throws IOException {
        cmd(in, out, "EHLO teachloop.local", "250");
    }

    private String fromAddress() {
        return settings.fromAddress() == null || settings.fromAddress().isBlank()
                ? settings.username()
                : settings.fromAddress();
    }

    /** 显示名按 RFC 2047 编码，信封地址仍是纯邮箱。 */
    private String fromHeader() {
        String name = settings.fromName();
        if (name == null || name.isBlank()) {
            return fromAddress();
        }
        return "=?UTF-8?B?" + b64(name) + "?= <" + fromAddress() + ">";
    }

    private String buildMessage(String to, String subject, String body) {
        List<String> lines = new ArrayList<>();
        lines.add("From: " + fromHeader());
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

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static Writer writer(Socket socket) throws IOException {
        return new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
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
