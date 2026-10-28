package com.teachloop.identity.auth.channel;

/**
 * 2026-10-28：管理端「发送测试邮件」的固定内容。
 *
 * <p>放在通道实现包而不是 API 层：API 层只负责权限与编排，用户能读到的中文文案
 * 由消息键或通道实现提供（`scripts/check-i18n.py` 会拦住 api/service 里的中文字面量）。
 */
public final class ChannelTestMail {

    public static final String SUBJECT = "TeachLoop 测试邮件";

    public static final String BODY =
            "这是一封来自 TeachLoop 系统设置的测试邮件。收到它说明邮件通道可以正常发信。";

    private ChannelTestMail() {
    }
}
