package com.teachloop.report.service.export;

/**
 * PDF 中文字体不可用（AC-14.6）。由 {@code ExportExceptionHandler} 映射为 503。
 */
public class PdfFontUnavailableException extends RuntimeException {

    public PdfFontUnavailableException(String message) {
        super(message);
    }
}
