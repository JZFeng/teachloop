package com.teachloop.report.service.export;

import com.teachloop.report.service.AggregateReportService.OfferingRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/** T151：报表导出，复用 XLSX（POI）与 PDF（OpenPDF + 内置 CJK 字体）。 */
public final class AggregateExportWriter {

    private AggregateExportWriter() {
    }

    public static byte[] offeringsXlsx(List<OfferingRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("按作业");
            String[] headers = {"作业 ID", "试卷", "应交", "已交", "平均分", "未交"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            int r = 1;
            for (OfferingRow row : rows) {
                Row line = sheet.createRow(r++);
                line.createCell(0).setCellValue(row.offeringId().toString());
                line.createCell(1).setCellValue(row.paperTitle());
                line.createCell(2).setCellValue(row.expected());
                line.createCell(3).setCellValue(row.submitted());
                if (row.averageScore() == null) {
                    line.createCell(4).setCellValue("—");
                } else {
                    line.createCell(4).setCellValue(row.averageScore().doubleValue());
                }
                line.createCell(5).setCellValue(row.unsubmitted());
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成报表 XLSX 失败", ex);
        }
    }

    public static byte[] offeringsPdf(List<OfferingRow> rows, BaseFont baseFont) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            Font title = new Font(baseFont, 16, Font.BOLD);
            Font body = new Font(baseFont, 10);
            document.add(new Paragraph("TeachLoop 报表 · 按作业", title));
            document.add(new Paragraph("平均分只统计已提交；未交单独计数。", body));
            document.add(new Paragraph(" "));
            PdfPTable table = new PdfPTable(new float[]{2.4f, 3.4f, 1f, 1f, 1.2f, 1f});
            table.setWidthPercentage(100);
            for (String head : new String[]{"作业 ID", "试卷", "应交", "已交", "平均分", "未交"}) {
                table.addCell(new org.openpdf.text.Phrase(head, body));
            }
            for (OfferingRow row : rows) {
                table.addCell(new org.openpdf.text.Phrase(row.offeringId().toString(), body));
                table.addCell(new org.openpdf.text.Phrase(row.paperTitle(), body));
                table.addCell(new org.openpdf.text.Phrase(String.valueOf(row.expected()), body));
                table.addCell(new org.openpdf.text.Phrase(String.valueOf(row.submitted()), body));
                table.addCell(new org.openpdf.text.Phrase(
                        row.averageScore() == null ? "—" : row.averageScore().toPlainString(), body));
                table.addCell(new org.openpdf.text.Phrase(String.valueOf(row.unsubmitted()), body));
            }
            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("生成报表 PDF 失败", ex);
        }
    }
}
