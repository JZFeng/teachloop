package com.teachloop.report.api;

import com.teachloop.report.service.export.PdfFontUnavailableException;
import com.teachloop.shared.error.ApiError;
import com.teachloop.shared.error.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 导出专属错误映射（AC-14.6）：字体自检失败返回 503，而不是共享兜底里的 500。
 *
 * <p>用 {@link Ordered#HIGHEST_PRECEDENCE} 抢在 shared 的 {@code GlobalExceptionHandler}
 * 之前匹配，避免 {@code @ExceptionHandler(Exception.class)} 把它吞成内部错误。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExportExceptionHandler {

    @ExceptionHandler(PdfFontUnavailableException.class)
    public ResponseEntity<ApiError> handleFontUnavailable(PdfFontUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(ErrorCode.INTERNAL, ex.getMessage()));
    }
}
