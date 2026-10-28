package com.teachloop.identity.auth.channel;

/**
 * 009 T521：身份通道端口。
 *
 * <p>「发一条验证信息给某个联系方式」这一件事的唯一抽象。新增通道只实现它并注册即可，
 * 调用方（注册 / 登录 / 忘记密码）永远只依赖这个接口——宪法第六条的「认证提供者必须可替换」。
 */
public interface VerificationChannel {

    /** 通道标识：email / sms / wechat。 */
    String channel();

    /** 通道是否配置齐全（不看开关；开关由 ChannelRegistry 叠加）。 */
    boolean configured();

    /** 前端展示用短名。 */
    String label();

    /** 输入框提示（邮箱 / 手机号 / 扫码）。 */
    String targetHint();

    /**
     * 发送一条验证信息。
     *
     * @throws ChannelSendException 发送失败（服务商 5xx、认证失败、超时）；调用方应作废对应令牌
     */
    void send(String target, String subject, String body) throws ChannelSendException;
}
