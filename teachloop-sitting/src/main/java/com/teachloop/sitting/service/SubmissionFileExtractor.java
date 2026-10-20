package com.teachloop.sitting.service;

import com.teachloop.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件提交的校验与文本提取（T061 / T066）。
 *
 * <p>扩展名 + 声明的内容类型双校验；100 MB 上限。支持 pdf / docx / txt / md，
 * {@code .doc} 明确拒绝并提示另存为 docx（v1 不解析二进制 doc，plan §10）。
 *
 * <p>解析失败或提取为空 → {@code unreadable=true}：不参与评分，<b>不产生 0 分</b>（AC-6.9）。
 */
@Component
public class SubmissionFileExtractor {

    private static final Logger log = LoggerFactory.getLogger(SubmissionFileExtractor.class);

    public static final long MAX_BYTES = 100L * 1024 * 1024;

    private static final Set<String> SUPPORTED = Set.of("pdf", "docx", "txt", "md");

    public record ValidatedFile(String extension, String mime, String originalName, long size) {
    }

    /** @param text 提取出的纯文本；不可读时 null */
    public record Extraction(String text, boolean unreadable) {
    }

    /**
     * 扩展名与内容类型校验；不读取文件内容（字节由调用方读一次）。
     */
    public ValidatedFile validate(MultipartFile file) {
        if (file == null) {
            throw ApiException.badRequest("请选择要上传的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("文件超过 100 MB 上限");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        if (originalName.isEmpty()) {
            throw ApiException.badRequest("文件名不能为空");
        }
        String extension = extensionOf(originalName);
        if ("doc".equals(extension)) {
            throw ApiException.badRequest("不支持 .doc，请另存为 .docx 后上传");
        }
        if (!SUPPORTED.contains(extension)) {
            throw ApiException.badRequest("只支持 pdf / docx / txt / md 文件");
        }
        String declared = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
        String mime = expectedMime(extension);
        if (!declared.isEmpty() && !acceptedTypes(extension).contains(declared)) {
            throw ApiException.badRequest("文件类型与扩展名不一致");
        }
        return new ValidatedFile(extension, mime, clip(originalName, 255), file.getSize());
    }

    /** 按扩展名提取文本；任何异常都收敛为 unreadable，绝不把解析失败变成 0 分。 */
    public Extraction extract(String extension, byte[] bytes) {
        try {
            String text = switch (extension) {
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                case "txt", "md" -> new String(bytes, StandardCharsets.UTF_8);
                default -> null;
            };
            String normalized = text == null ? null : text.strip();
            if (normalized == null || normalized.isEmpty()) {
                return new Extraction(null, true);
            }
            return new Extraction(normalized, false);
        } catch (Exception ex) {
            log.warn("附件解析失败，标记 unreadable：{} ({})", extension, ex.toString());
            return new Extraction(null, true);
        }
    }

    public static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String line = paragraph.getText();
                if (line != null && !line.isBlank()) {
                    out.append(line).append('\n');
                }
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder cells = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (!cells.isEmpty()) {
                            cells.append(" | ");
                        }
                        cells.append(cell.getText());
                    }
                    if (!cells.isEmpty()) {
                        out.append(cells).append('\n');
                    }
                }
            }
            return out.toString();
        }
    }

    private static String expectedMime(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "md" -> "text/markdown";
            default -> "text/plain";
        };
    }

    private static List<String> acceptedTypes(String extension) {
        return switch (extension) {
            case "pdf" -> List.of("application/pdf", "application/octet-stream");
            case "docx" -> List.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/octet-stream", "application/zip");
            case "md" -> List.of("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream");
            default -> List.of("text/plain", "text/markdown", "application/octet-stream");
        };
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
