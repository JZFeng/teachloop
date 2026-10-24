package com.teachloop.sitting.storage;

/** 存储后端不可用（例如切换到 OSS 但未配置）：映射为 503，而不是把业务错误说成 500。 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message) {
        super(message);
    }

    /** 保留 SDK 原始异常做诊断，但对外只返回通用消息（不含端点细节或密钥）。 */
    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
