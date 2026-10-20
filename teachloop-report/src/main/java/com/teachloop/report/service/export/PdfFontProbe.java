package com.teachloop.report.service.export;

import org.openpdf.text.pdf.BaseFont;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PDF 中文字体自检（T122 / AC-14.6）。
 *
 * <p>启动时加载 OpenPDF 内置的 CJK 字体 STSong-Light + UniGB-UCS2-H（不嵌入字体文件，
 * 依赖阅读器的中文支持）。加载失败不会让应用起不来，而是把 {@link #available()} 置为 false，
 * 导出端点据此返回 503 并说明原因；其他导出（Markdown / XLSX / DOCX / ZIP）不受影响。
 */
@Component
public class PdfFontProbe {

    /** 中文字体名与编码：OpenPDF 内置资源，无需外部字体文件。 */
    static final String FONT_NAME = "STSong-Light";
    static final String ENCODING = "UniGB-UCS2-H";

    private static final Logger log = LoggerFactory.getLogger(PdfFontProbe.class);

    private final BaseFont baseFont;

    public PdfFontProbe() {
        BaseFont font = null;
        try {
            font = BaseFont.createFont(FONT_NAME, ENCODING, BaseFont.NOT_EMBEDDED);
        } catch (Exception ex) {
            log.error("PDF 中文字体自检失败：{} / {} 不可用，feedback.pdf 将返回 503", FONT_NAME, ENCODING, ex);
        }
        this.baseFont = font;
    }

    public boolean available() {
        return baseFont != null;
    }

    /** @throws PdfFontUnavailableException 自检失败时，由 endpoint 转 503 */
    public BaseFont font() {
        if (baseFont == null) {
            throw new PdfFontUnavailableException(
                    "PDF 中文字体不可用（" + FONT_NAME + " / " + ENCODING + "），请检查部署包字体资源");
        }
        return baseFont;
    }
}
