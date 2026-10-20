package com.teachloop.report.service.export;

import com.teachloop.report.service.export.ExportModels.ClassStat;
import com.teachloop.report.service.export.ExportModels.MissingStudent;
import com.teachloop.report.service.export.ExportModels.OfferingMeta;
import com.teachloop.report.service.export.ExportModels.StudentSubmission;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/**
 * 班级汇总 Word 导出（T123）：POI XWPF，内容与 Markdown 汇总同源。
 *
 * <p>表头随 {@code lang} 本地化；未发布成绩时平均分写「未发布」。
 */
public final class ExportDocxWriter {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private ExportDocxWriter() {
    }

    public static byte[] summary(OfferingMeta offering, List<ClassStat> stats,
                                 List<StudentSubmission> submissions, List<MissingStudent> missing,
                                 ExportLabels labels) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            heading(document, offering.paperTitle() + " · " + labels.summaryTitle(), 16);
            paragraph(document, labels.assignmentName() + "：" + offering.paperTitle(), false);
            paragraph(document, labels.type() + "：" + kind(offering.kind(), labels), false);
            paragraph(document, labels.dueAt() + "：" + time(offering.dueAt()), false);
            paragraph(document, labels.scoresPublished() + "："
                    + (offering.publishScores() ? labels.published() : labels.notPublished()), false);

            heading(document, labels.byClass(), 13);
            XWPFTable classTable = document.createTable(stats.size() + 1, 9);
            String[] classHeaders = {
                    labels.classId(), labels.className(), labels.expected(), labels.submitted(),
                    labels.onTime(), labels.late(), labels.rejected(), labels.onTimeRate(),
                    labels.average()};
            fill(classTable.getRow(0), classHeaders);
            for (int i = 0; i < stats.size(); i++) {
                ClassStat stat = stats.get(i);
                fill(classTable.getRow(i + 1), new String[]{
                        stat.classId(),
                        stat.className(),
                        String.valueOf(stat.expected()),
                        String.valueOf(stat.submitted()),
                        String.valueOf(stat.onTime()),
                        String.valueOf(stat.late()),
                        String.valueOf(stat.rejected()),
                        stat.onTimeRate() == null ? "" : stat.onTimeRate().toPlainString() + "%",
                        average(stat, offering, labels)});
            }

            heading(document, labels.bySubmission(), 13);
            XWPFTable submissionTable = document.createTable(submissions.size() + 1, 8);
            String[] submissionHeaders = {
                    labels.student(), labels.attempt(), labels.submittedAt(), labels.lateSeconds(),
                    labels.status(), labels.objective(), labels.subjective(), labels.total()};
            fill(submissionTable.getRow(0), submissionHeaders);
            for (int i = 0; i < submissions.size(); i++) {
                StudentSubmission s = submissions.get(i);
                fill(submissionTable.getRow(i + 1), new String[]{
                        s.studentName(),
                        String.valueOf(s.attemptNo()),
                        time(s.submittedAt()),
                        String.valueOf(s.lateBySeconds()),
                        status(s.gradingState(), labels),
                        s.objectiveTotal().toPlainString(),
                        s.subjectiveTotal().toPlainString(),
                        s.total().toPlainString()});
            }

            heading(document, labels.notSubmitted(), 13);
            if (missing.isEmpty()) {
                paragraph(document, labels.none(), false);
            } else {
                for (MissingStudent student : missing) {
                    paragraph(document, student.studentName() + "（" + student.studentId() + "）", false);
                }
            }

            document.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 DOCX 失败", ex);
        }
    }

    private static String average(ClassStat stat, OfferingMeta offering, ExportLabels labels) {
        if (!offering.publishScores()) {
            return labels.notPublished();
        }
        return stat.averageScore() == null ? "" : stat.averageScore().toPlainString();
    }

    private static void heading(XWPFDocument document, String text, int size) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(size);
        run.setText(text);
    }

    private static void paragraph(XWPFDocument document, String text, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        run.setText(text == null ? "" : text);
    }

    private static void fill(org.apache.poi.xwpf.usermodel.XWPFTableRow row, String[] values) {
        for (int i = 0; i < values.length && i < row.getTableCells().size(); i++) {
            XWPFTableCell cell = row.getCell(i);
            cell.setText(values[i] == null ? "" : values[i]);
        }
    }

    private static String time(Instant value) {
        return value == null ? "" : TIME.format(value) + " UTC";
    }

    private static String kind(String kind, ExportLabels labels) {
        return switch (kind == null ? "" : kind) {
            case "EXAM" -> labels.exam();
            case "ASSIGNMENT" -> labels.assignment();
            default -> kind == null ? "" : kind;
        };
    }

    private static String status(String state, ExportLabels labels) {
        return switch (state == null ? "" : state) {
            case "DONE" -> labels.graded();
            case "RUNNING" -> labels.grading();
            case "PENDING", "QUEUED" -> labels.pending();
            case "FAILED" -> labels.failed();
            case "DRAFT" -> labels.draft();
            default -> state == null ? "" : state;
        };
    }
}
