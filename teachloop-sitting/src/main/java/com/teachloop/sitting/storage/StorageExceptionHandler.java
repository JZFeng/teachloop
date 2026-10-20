package com.teachloop.sitting.storage;

import com.teachloop.shared.error.ApiError;
import com.teachloop.shared.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 上传相关的错误映射，抢在 shared 的兜底 500 之前：
 * 超过 100 MB → 400（指出上限），存储后端不可用 → 503。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StorageExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "文件超过 100 MB 上限", null));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "缺少文件字段 file", null));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "上传请求不合法", null));
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(StorageUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(ErrorCode.INTERNAL.name(), "附件存储暂不可用", ex.getMessage()));
    }
}
