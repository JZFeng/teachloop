package com.teachloop.report.service.export;

import com.teachloop.report.service.export.ExportModels.ClassStat;
import com.teachloop.report.service.export.ExportModels.FeedbackDoc;
import com.teachloop.report.service.export.ExportModels.FeedbackEntry;
import com.teachloop.report.service.export.ExportModels.MissingStudent;
import com.teachloop.report.service.export.ExportModels.OfferingMeta;
import com.teachloop.report.service.export.ExportModels.StudentSubmission;
import com.teachloop.report.service.export.ExportModels.WrongItem;
import com.teachloop.shared.error.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * 导出编排（T120 / T121 / T124）：读取只读聚合 → 渲染 Markdown → 打包 ZIP。
 *
 * <p>明确不做：PDF（T122，需要 OpenPDF + Noto Sans CJK）与 XLSX / DOCX（T123，需要 POI）。
 * 这两类产物要新增第三方依赖并改父 pom，不在本任务范围内，接口也没有对应路由。
 */
@Service
public class OfferingExportService {

    /** ZIP 内每个 entry 的固定修改时间，让同一份数据导出的字节稳定。 */
    private static final long FIXED_ENTRY_TIME = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();

    private static final int MAX_BASE_NAME_LENGTH = 60;

    private final ExportReadService read;
    private final PdfFontProbe pdfFonts;

    public OfferingExportService(ExportReadService read, PdfFontProbe pdfFonts) {
        this.read = read;
        this.pdfFonts = pdfFonts;
    }

    /** 班级汇总 Markdown。仅教师 / 管理员可调用，角色闸门在 endpoint。 */
    public String summaryMarkdown(UUID offeringId) {
        OfferingMeta offering = requireOffering(offeringId);
        List<ClassStat> stats = read.classStats(offeringId, offering.publishScores());
        List<StudentSubmission> submissions = read.latestSubmitted(offeringId);
        List<MissingStudent> missing = read.missingStudents(offeringId);
        return ExportMarkdownRenderer.summary(offering, stats, submissions, missing);
    }

    /** 成绩表 XLSX（T123）：按班统计 + 逐份提交 + 未提交名单，表头随 lang。 */
    public byte[] summaryXlsx(UUID offeringId, String lang) {
        OfferingMeta offering = requireOffering(offeringId);
        return ExportSpreadsheetWriter.summary(offering,
                read.classStats(offeringId, offering.publishScores()),
                read.latestSubmitted(offeringId),
                read.missingStudents(offeringId),
                ExportLabels.of(lang));
    }

    /** 班级汇总 DOCX（T123）：内容与 Markdown 汇总同源，表头随 lang。 */
    public byte[] summaryDocx(UUID offeringId, String lang) {
        OfferingMeta offering = requireOffering(offeringId);
        return ExportDocxWriter.summary(offering,
                read.classStats(offeringId, offering.publishScores()),
                read.latestSubmitted(offeringId),
                read.missingStudents(offeringId),
                ExportLabels.of(lang));
    }

    /**
     * 逐份反馈 ZIP。
     *
     * @throws ApiException 404 布置不存在；409 成绩尚未发布（AC-14.5：只打包已发布反馈）
     */
    public byte[] feedbackZip(UUID offeringId) {
        OfferingMeta offering = requirePublishedOffering(offeringId);
        List<FeedbackEntry> entries = feedbackEntries(offeringId);

        List<String> baseNames = new ArrayList<>(entries.size());
        Map<String, Integer> seen = new HashMap<>();
        for (FeedbackEntry entry : entries) {
            String base = safeFileBase(entry.submission().studentName());
            baseNames.add(base);
            seen.merge(base.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }

        List<FeedbackDoc> docs = new ArrayList<>(entries.size());
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            StudentSubmission s = entries.get(i).submission();
            String base = baseNames.get(i);
            if (seen.get(base.toLowerCase(Locale.ROOT)) > 1) {
                // 没有单独的「学号」列，重名时用学生 id 前 8 位做区分后缀。
                base = base + "-" + s.studentId().toString().substring(0, 8);
            }
            String fileName = uniqueName(base, s.attemptNo(), usedNames);
            docs.add(new FeedbackDoc(fileName, ExportMarkdownRenderer.feedback(offering, entries.get(i))));
        }
        return zip(docs);
    }

    /**
     * 逐份反馈 PDF（T122）。
     *
     * @throws ApiException                 404 布置不存在；409 成绩尚未发布
     * @throws PdfFontUnavailableException   中文字体自检失败 → 503（AC-14.6）
     */
    public byte[] feedbackPdf(UUID offeringId, String lang) {
        OfferingMeta offering = requirePublishedOffering(offeringId);
        if (!pdfFonts.available()) {
            throw new PdfFontUnavailableException(
                    "PDF 中文字体不可用（STSong-Light / UniGB-UCS2-H），请检查部署包字体资源");
        }
        return ExportPdfWriter.feedback(offering, feedbackEntries(offeringId),
                pdfFonts.font(), ExportLabels.of(lang));
    }

    /** 逐份反馈的公共数据装配：最新一次已提交且未拒收的 attempt + 错题 + 评语。 */
    private List<FeedbackEntry> feedbackEntries(UUID offeringId) {
        List<StudentSubmission> submissions = read.latestSubmitted(offeringId);
        List<UUID> submissionIds = submissions.stream()
                .map(StudentSubmission::submissionId)
                .toList();
        Map<UUID, List<WrongItem>> wrongItems = read.wrongItems(submissionIds);
        Map<UUID, String> comments = read.reviewComments(submissionIds);
        List<FeedbackEntry> entries = new ArrayList<>(submissions.size());
        for (StudentSubmission s : submissions) {
            entries.add(new FeedbackEntry(s,
                    wrongItems.getOrDefault(s.submissionId(), List.of()),
                    comments.get(s.submissionId())));
        }
        return entries;
    }

    private OfferingMeta requirePublishedOffering(UUID offeringId) {
        OfferingMeta offering = requireOffering(offeringId);
        if (!offering.publishScores()) {
            throw ApiException.conflict("成绩尚未发布");
        }
        return offering;
    }

    private OfferingMeta requireOffering(UUID offeringId) {
        return read.offering(offeringId)
                .orElseThrow(() -> ApiException.notFound("布置不存在"));
    }

    private static String uniqueName(String base, int attemptNo, Set<String> used) {
        String candidate = base + "-attempt" + attemptNo + ".md";
        int suffix = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + "-" + suffix + "-attempt" + attemptNo + ".md";
            suffix++;
        }
        return candidate;
    }

    /**
     * 文件名安全化：去掉 / \ : * ? " < > | 与控制字符，空白压成下划线。
     * 中文姓名原样保留（ZIP entry 名按 UTF-8 写出）。
     */
    static String safeFileBase(String displayName) {
        if (displayName == null) {
            return "student";
        }
        String cleaned = displayName
                .replaceAll("[\\\\/:*?\"<>|]", "")
                .replaceAll("\\p{Cntrl}", "")
                .replaceAll("\\s+", "_")
                .replaceAll("^[._]+", "")
                .replaceAll("[._]+$", "");
        if (cleaned.isEmpty()) {
            return "student";
        }
        if (cleaned.length() > MAX_BASE_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_BASE_NAME_LENGTH);
        }
        return cleaned;
    }

    private static byte[] zip(List<FeedbackDoc> docs) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (FeedbackDoc doc : docs) {
                ZipEntry entry = new ZipEntry("feedback/" + doc.fileName());
                entry.setTime(FIXED_ENTRY_TIME);
                zip.putNextEntry(entry);
                zip.write(doc.markdown().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("生成反馈 ZIP 失败", ex);
        }
        return buffer.toByteArray();
    }
}
