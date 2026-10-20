package com.teachloop.identity.api;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** 一条站内通知。read/readAt 二选一地表达已读状态，前端判断用 read。 */
    public record NotificationView(String id, String kind, String targetType, String targetId,
                                   String title, String body, boolean read, String readAt,
                                   String createdAt) {
    }

    public record UnreadCountView(long count) {
    }

    public record ReadAllView(int updated) {
    }
}
