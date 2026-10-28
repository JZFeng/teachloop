package com.teachloop.identity.auth.channel;

/** 通道发送失败。消息只含可对外的原因，不含凭据与收件人。 */
public class ChannelSendException extends Exception {

    private final String reason;

    public ChannelSendException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public ChannelSendException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
