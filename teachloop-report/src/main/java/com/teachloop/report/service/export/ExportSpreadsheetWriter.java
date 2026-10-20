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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 成绩表导出（T123）：POI XSSF，按班统计 + 逐份提交 + 未提交名单。
 *
 * <p>与 Markdown 汇总同源同口径；表头按 {@code lang}（zh-CN / en）本地化。
 * 未发布成绩时平均分写「未发布」（英文 Not published），不给数字。
 */
public final class ExportSpreadsheetWriter {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private ExportSpreadsheetWriter() {
    }

    public static byte[] summary(OfferingMeta offering, List<ClassStat> stats,
                                 List<StudentSubmission> submissions, List<MissingStudent> missing,
                                 ExportLabels labels) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Styles styles = new Styles(workbook);
            classSheet(workbook, offering, stats, labels, styles);
            submissionSheet(workbook, submissions, labels, styles);
            missingSheet(workbook, missing, labels, styles);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 XLSX 失败", ex);
        }
    }

    private static void classSheet(XSSFWorkbook workbook, OfferingMeta offering,
                                   List<ClassStat> stats, ExportLabels labels, Styles styles) {
        Sheet sheet = workbook.createSheet(labels.byClass());

        Row title = sheet.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue(offering.paperTitle() + " · " + labels.summaryTitle());
        titleCell.setCellStyle(styles.title);

        int row = 1;
        row = meta(sheet, row, labels.assignmentName(), offering.paperTitle(), styles);
        row = meta(sheet, row, labels.type(), kind(offering.kind(), labels), styles);
        row = meta(sheet, row, labels.dueAt(), time(offering.dueAt()), styles);
        row = meta(sheet, row, labels.scoresPublished(),
                offering.publishScores() ? labels.published() : labels.notPublished(), styles);
        row++;

        Row section = sheet.createRow(row++);
        Cell sectionCell = section.createCell(0);
        sectionCell.setCellValue(labels.byClass());
        sectionCell.setCellStyle(styles.section);

        Row header = sheet.createRow(row++);
        String[] names = {
                labels.classId(), labels.className(), labels.expected(), labels.submitted(),
                labels.onTime(), labels.late(), labels.rejected(), labels.onTimeRate(),
                labels.average()};
        for (int i = 0; i < names.length; i++) {
            cell(header, i, names[i], styles.header);
        }

        for (ClassStat stat : stats) {
            Row r = sheet.createRow(row++);
            cell(r, 0, stat.classId(), styles.text);
            cell(r, 1, stat.className(), styles.text);
            cell(r, 2, stat.expected(), styles.integer);
            cell(r, 3, stat.submitted(), styles.integer);
            cell(r, 4, stat.onTime(), styles.integer);
            cell(r, 5, stat.late(), styles.integer);
            cell(r, 6, stat.rejected(), styles.integer);
            if (stat.onTimeRate() == null) {
                cell(r, 7, "", styles.text);
            } else {
                cell(r, 7, stat.onTimeRate().doubleValue(), styles.percent);
            }
            if (!offering.publishScores()) {
                cell(r, 8, labels.notPublished(), styles.text);
            } else if (stat.averageScore() == null) {
                cell(r, 8, "", styles.text);
            } else {
                cell(r, 8, stat.averageScore().doubleValue(), styles.decimal);
            }
        }
        widths(sheet, 36, 18, 10, 10, 10, 10, 10, 14, 12);
    }

    private static void submissionSheet(XSSFWorkbook workbook, List<StudentSubmission> submissions,
                                        ExportLabels labels, Styles styles) {
        Sheet sheet = workbook.createSheet(labels.bySubmission());
        Row header = sheet.createRow(0);
        String[] names = {
                labels.student(), labels.attempt(), labels.submittedAt(), labels.lateSeconds(),
                labels.status(), labels.objective(), labels.subjective(), labels.total()};
        for (int i = 0; i < names.length; i++) {
            cell(header, i, names[i], styles.header);
        }

        int row = 1;
        for (StudentSubmission s : submissions) {
            Row r = sheet.createRow(row++);
            cell(r, 0, s.studentName(), styles.text);
            cell(r, 1, s.attemptNo(), styles.integer);
            cell(r, 2, time(s.submittedAt()), styles.text);
            cell(r, 3, s.lateBySeconds(), styles.integer);
            cell(r, 4, status(s.gradingState(), labels), styles.text);
            cell(r, 5, s.objectiveTotal().doubleValue(), styles.decimal);
            cell(r, 6, s.subjectiveTotal().doubleValue(), styles.decimal);
            cell(r, 7, s.total().doubleValue(), styles.decimal);
        }
        widths(sheet, 24, 10, 26, 14, 12, 12, 12, 12);
    }

    private static void missingSheet(XSSFWorkbook workbook, List<MissingStudent> missing,
                                     ExportLabels labels, Styles styles) {
        Sheet sheet = workbook.createSheet(labels.notSubmitted());
        Row header = sheet.createRow(0);
        cell(header, 0, labels.student(), styles.header);
        int row = 1;
        if (missing.isEmpty()) {
            cell(sheet.createRow(row), 0, labels.none(), styles.text);
        } else {
            for (MissingStudent student : missing) {
                Row r = sheet.createRow(row++);
                cell(r, 0, student.studentName(), styles.text);
                cell(r, 1, student.studentId().toString(), styles.text);
            }
        }
        widths(sheet, 24, 42);
    }

    private static int meta(Sheet sheet, int rowIndex, String label, String value, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        cell(row, 0, label, styles.metaLabel);
        cell(row, 1, value, styles.text);
        return rowIndex + 1;
    }

    private static void widths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, Math.min(255, widths[i]) * 256);
        }
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void cell(Row row, int column, long value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void cell(Row row, int column, double value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
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

    /** 一个 workbook 内复用的样式集合（POI 样式不能跨 workbook 使用）。 */
    private static final class Styles {
        private final CellStyle title;
        private final CellStyle section;
        private final CellStyle header;
        private final CellStyle metaLabel;
        private final CellStyle text;
        private final CellStyle integer;
        private final CellStyle decimal;
        private final CellStyle percent;

        private Styles(XSSFWorkbook workbook) {
            title = workbook.createCellStyle();
            var titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title.setFont(titleFont);

            section = workbook.createCellStyle();
            var sectionFont = workbook.createFont();
            sectionFont.setBold(true);
            sectionFont.setFontHeightInPoints((short) 12);
            section.setFont(sectionFont);

            header = workbook.createCellStyle();
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            header.setFont(headerFont);

            metaLabel = workbook.createCellStyle();
            var metaFont = workbook.createFont();
            metaFont.setBold(true);
            metaLabel.setFont(metaFont);

            text = workbook.createCellStyle();
            integer = workbook.createCellStyle();
            integer.setDataFormat(workbook.createDataFormat().getFormat("0"));
            decimal = workbook.createCellStyle();
            decimal.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            percent = workbook.createCellStyle();
            percent.setDataFormat(workbook.createDataFormat().getFormat("0.00\"%\""));
        }
    }
}
