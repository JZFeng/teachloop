package com.teachloop.classroom.domain;

/**
 * 011 / US-95：班级收费字段的第一期口径（不做支付）。
 *
 * <p>三列由迁移 {@code V077__classroom_class_pay.sql} 落地（属主 classroom）；第一期只允许
 * {@link Mode#FREE}：建班传付费 → 400（AC-95.2），入班遇付费班 → 409（AC-95.3）。
 */
public final class ClassPay {

    /** {@code classes.pay_mode} 的取值（库里有 check 约束，见 V077）。 */
    public enum Mode {
        /** 免费班：第一期唯一可创建、可加入的形态。 */
        FREE,
        /** 付费班：字段已预留，接口一律拒绝。 */
        PAID
    }

    /** {@code classes.currency} 的默认值；第一期只接受 CNY。 */
    public static final String DEFAULT_CURRENCY = "CNY";

    /** 建班传了付费字段时的 400 文案 key（AC-95.2）；zh：第一期不支持创建付费班级。 */
    public static final String MESSAGE_CREATE_NOT_SUPPORTED = "error.class.paidNotSupported";

    /** 入班遇到付费班时的 409 文案 key（AC-95.3）；zh：付费班级暂未开放。 */
    public static final String MESSAGE_PAID_NOT_AVAILABLE = "error.class.paidNotAvailable";

    private ClassPay() {
    }
}
