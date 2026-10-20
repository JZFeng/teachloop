package com.teachloop.report.service.export;

import com.teachloop.report.service.export.ExportModels.FeedbackEntry;
import com.teachloop.report.service.export.ExportModels.OfferingMeta;
import com.teachloop.report.service.export.ExportModels.StudentSubmission;
import com.teachloop.report.service.export.ExportModels.WrongItem;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfWriter;

/**
 * 逐份反馈 PDF（T122）：OpenPDF + 内置 CJK 字体 STSong-Light / UniGB-UCS2-H。
 *
 * <p>每位学生一页，内容与 Markdown / ZIP 同源：总分、客观/主观小计、错题与标准答案、复核评语。
 * 字体不嵌入（{@link BaseFont#NOT_EMBEDDED}），由 {@link PdfFontProbe} 在启动时自检；
 * 自检失败时端点返回 503（AC-14.6）。
 */
public final class ExportPdfWriter {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private ExportPdfWriter() {
    }

    public static byte[] feedback(OfferingMeta offering, List<FeedbackEntry> entries,
                                  BaseFont baseFont, ExportLabels labels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font title = new Font(baseFont, 16, Font.BOLD);
            Font heading = new Font(baseFont, 12, Font.BOLD);
            Font body = new Font(baseFont, 10);
            Font muted = new Font(baseFont, 9);
            // 内置 CJK 字体是非嵌入的预定义 CMap，PDF 里没有 ToUnicode；
            // OpenPDF 的文本提取器读不出中文。这里额外写一行 Helvetica 的机器可读元数据，
            // 让导出产物仍可被解析/检索（渲染中文不受影响）。
            Font machine = new Font(Font.HELVETICA, 8);

            boolean firstPage = true;
            for (FeedbackEntry entry : entries) {
                if (!firstPage) {
                    document.newPage();
                }
                firstPage = false;
                StudentSubmission s = entry.submission();

                document.add(new Paragraph(
                        s.studentName() + " · " + labels.feedbackTitle(), title));
                document.add(new Paragraph(labels.assignmentName() + ": " + offering.paperTitle(), body));
                document.add(new Paragraph(labels.attempt() + ": " + s.attemptNo(), body));
                document.add(new Paragraph(labels.submittedAt() + ": " + time(s.submittedAt()), body));
                document.add(new Paragraph(labels.lateSeconds() + ": " + s.lateBySeconds(), body));
                document.add(new Paragraph(labels.objectiveSubtotal() + ": " + num(s.objectiveTotal()), body));
                document.add(new Paragraph(labels.subjectiveSubtotal() + ": " + num(s.subjectiveTotal()), body));
                document.add(new Paragraph(labels.total() + ": " + num(s.total()), heading));
                document.add(new Paragraph(" "));

                document.add(new Paragraph(labels.wrongAnswers(), heading));
                if (entry.wrongItems().isEmpty()) {
                    document.add(new Paragraph(labels.none(), body));
                } else {
                    for (WrongItem item : entry.wrongItems()) {
                        document.add(new Paragraph(item.number() + ". "
                                + "[" + questionType(item.questionType()) + "] "
                                + labels.points() + ": " + num(item.points()), body));
                        document.add(new Paragraph("    " + labels.myAnswer() + ": " + blank(item.answer())
                                + "    " + labels.answerKey() + ": " + blank(item.answerKey()), body));
                        if (item.stem() != null && !item.stem().isBlank()) {
                            document.add(new Paragraph("    " + labels.stem() + ": " + item.stem(), muted));
                        }
                    }
                }
                document.add(new Paragraph(" "));

                document.add(new Paragraph(labels.reviewComment(), heading));
                String comment = entry.reviewComment();
                document.add(new Paragraph(
                        comment == null || comment.isBlank() ? labels.none() : comment.strip(), body));
                document.add(new Paragraph(" ", body));

                document.add(new Paragraph("student=" + s.studentId()
                        + " attempt=" + s.attemptNo()
                        + " objective=" + num(s.objectiveTotal())
                        + " subjective=" + num(s.subjectiveTotal())
                        + " total=" + num(s.total())
                        + " late=" + s.lateBySeconds()
                        + " auto=" + s.autoSubmitted(), machine));
                for (WrongItem item : entry.wrongItems()) {
                    document.add(new Paragraph("item=" + item.number()
                            + " answer=" + (item.answer() == null ? "" : item.answer())
                            + " key=" + (item.answerKey() == null ? "" : item.answerKey())
                            + " points=" + num(item.points()), machine));
                }
            }
            if (entries.isEmpty()) {
                document.add(new Paragraph(labels.none(), body));
            }

            document.close();
            return out.toByteArray();
        } catch (DocumentException ex) {
            throw new IllegalStateException("生成 PDF 失败", ex);
        }
    }

    private static String num(java.math.BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String time(Instant value) {
        return value == null ? "" : TIME.format(value) + " UTC";
    }

    private static String questionType(String type) {
        return switch (type == null ? "" : type) {
            case "SINGLE_CHOICE" -> "单选";
            case "MULTIPLE_CHOICE" -> "多选";
            case "FILL_BLANK" -> "填空";
            case "ESSAY" -> "作文";
            default -> type == null ? "" : type;
        };
    }
}
