package com.teachloop.sitting.storage;

/** 存储后端不可用（例如切换到 OSS 但未配置）：映射为 503，而不是把业务错误说成 500。 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message) {
        super(message);
    }
}
