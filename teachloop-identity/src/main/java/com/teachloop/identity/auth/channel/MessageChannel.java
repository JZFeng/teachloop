package com.teachloop.identity.auth.channel;

/**
 * 009 T521：出站消息通道端口（2026-10-28 由 {@code VerificationChannel} 改名）。
 *
 * <p>「往某个联系方式发一条消息」这一件事的唯一抽象：邮件、短信、微信都实现它，
 * 将来加新的投递方式（站内信、推送、企业微信…）也只实现它并注册，调用方不变。
 *
 * <p>现在的调用方是异步队列 {@code OutboundMessageWorker}——业务代码不再直接 {@link #send}，
 * 只往队列里放一条；发失败由队列按退避重试，不会拖住 HTTP 请求。
 *
 * <p>实现类要保证：{@link #configured()} 只读配置、不发网络请求（管理端列表每屏都会调它）。
 */
public interface MessageChannel {

    /** 通道标识：email / sms / wechat。 */
    String channel();

    /** 通道是否配置齐全（不看开关；开关由 ChannelRegistry 叠加）。 */
    boolean configured();

    /** 前端展示用短名。 */
    String label();

    /** 输入框提示（邮箱 / 手机号 / 扫码）。 */
    String targetHint();

    /**
     * 发一条消息。
     *
     * @throws ChannelSendException 发送失败（服务商 5xx、认证失败、超时）；
     *                              由 {@code OutboundMessageWorker} 捕获并按退避重试
     */
    void send(String target, String subject, String body) throws ChannelSendException;
}
