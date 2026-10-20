package com.teachloop.report.service.export;

import com.teachloop.report.service.export.ExportModels.ClassStat;
import com.teachloop.report.service.export.ExportModels.FeedbackEntry;
import com.teachloop.report.service.export.ExportModels.MissingStudent;
import com.teachloop.report.service.export.ExportModels.OfferingMeta;
import com.teachloop.report.service.export.ExportModels.StudentSubmission;
import com.teachloop.report.service.export.ExportModels.WrongItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Markdown 渲染（T120 / T121）：确定性统计，不生成任何教学建议。
 *
 * <p>只拼接已经算好的数字，时间统一按 UTC 输出，保证同一份数据两次导出逐字节一致。
 *
 * <p>PDF（T122）与 XLSX / DOCX（T123）需要新增 OpenPDF / POI 依赖，本任务不做。
 */
public final class ExportMarkdownRenderer {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private ExportMarkdownRenderer() {
    }

    /** 班级汇总 Markdown：标题、作业信息、按班统计、逐份提交、未提交名单。 */
    public static String summary(OfferingMeta offering, List<ClassStat> stats,
                                 List<StudentSubmission> submissions,
                                 List<MissingStudent> missing) {
        StringBuilder md = new StringBuilder(4096);
        md.append("# ").append(oneLine(offering.paperTitle())).append(" · 班级汇总\n\n");
        md.append("> 生成规则：只做确定性统计，不含教学建议；数字口径与班级汇总接口一致。\n\n");

        md.append("## 作业信息\n\n");
        md.append("| 项目 | 内容 |\n| :--- | :--- |\n");
        row(md, "作业名", oneLine(offering.paperTitle()));
        row(md, "类型", kindLabel(offering.kind()));
        row(md, "截止时间", time(offering.dueAt()));
        row(md, "成绩发布", offering.publishScores() ? "已发布" : "未发布");
        md.append('\n');

        md.append("## 按班统计\n\n");
        md.append("| 班级 id | 班级名称 | 应交 | 实交 | 按时 | 迟交 | 拒收 | 按时提交率 | 平均分 |\n");
        md.append("| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ClassStat s : stats) {
            md.append("| ").append(s.classId())
                    .append(" | ").append(oneLine(s.className()))
                    .append(" | ").append(s.expected())
                    .append(" | ").append(s.submitted())
                    .append(" | ").append(s.onTime())
                    .append(" | ").append(s.late())
                    .append(" | ").append(s.rejected())
                    .append(" | ").append(rate(s.onTimeRate()))
                    .append(" | ").append(averageText(s.averageScore(), offering.publishScores()))
                    .append(" |\n");
        }
        if (stats.isEmpty()) {
            md.append("| — | — | 0 | 0 | 0 | 0 | 0 | — | — |\n");
        }
        md.append('\n');

        md.append("## 逐份提交\n\n");
        md.append("| 学生 | attempt | 提交时间 | 迟交（秒） | 状态 | 客观分 | 主观分 | 总分 |\n");
        md.append("| :--- | ---: | :--- | ---: | :--- | ---: | ---: | ---: |\n");
        for (StudentSubmission s : submissions) {
            md.append("| ").append(oneLine(s.studentName()))
                    .append(" | ").append(s.attemptNo())
                    .append(" | ").append(time(s.submittedAt()))
                    .append(" | ").append(s.lateBySeconds())
                    .append(" | ").append(gradingStateLabel(s.gradingState()))
                    .append(" | ").append(num(s.objectiveTotal()))
                    .append(" | ").append(num(s.subjectiveTotal()))
                    .append(" | ").append(num(s.total()))
                    .append(" |\n");
        }
        if (submissions.isEmpty()) {
            md.append("| — | — | — | — | — | — | — | — |\n");
        }
        md.append('\n');

        md.append("## 未提交名单\n\n");
        if (missing.isEmpty()) {
            md.append("无。\n");
        } else {
            for (MissingStudent m : missing) {
                md.append("- ").append(oneLine(m.studentName()))
                        .append("（").append(m.studentId()).append("）\n");
            }
        }
        return md.toString();
    }

    /** 逐份反馈 Markdown：YAML front matter + 客观/主观/总分 + 错题 + 复核评语。 */
    public static String feedback(OfferingMeta offering, FeedbackEntry entry) {
        StudentSubmission submission = entry.submission();
        StringBuilder md = new StringBuilder(2048);

        md.append("---\n");
        yaml(md, "student", submission.studentName());
        yaml(md, "offering", offering.paperTitle());
        md.append("attempt: ").append(submission.attemptNo()).append('\n');
        md.append("objective: ").append(num(submission.objectiveTotal())).append('\n');
        md.append("subjective: ").append(num(submission.subjectiveTotal())).append('\n');
        md.append("total: ").append(num(submission.total())).append('\n');
        md.append("late_seconds: ").append(submission.lateBySeconds()).append('\n');
        md.append("auto_submitted: ").append(submission.autoSubmitted()).append('\n');
        md.append("---\n\n");

        md.append("# ").append(oneLine(submission.studentName())).append(" · 逐份反馈\n\n");
        md.append("| 项目 | 内容 |\n| :--- | :--- |\n");
        row(md, "作业名", oneLine(offering.paperTitle()));
        row(md, "attempt", String.valueOf(submission.attemptNo()));
        row(md, "提交时间", time(submission.submittedAt()));
        row(md, "是否迟交", submission.lateBySeconds() > 0
                ? "是，迟交 " + submission.lateBySeconds() + " 秒" : "否");
        row(md, "自动交卷", submission.autoSubmitted() ? "是" : "否");
        row(md, "客观题小计", num(submission.objectiveTotal()));
        row(md, "主观题小计", num(submission.subjectiveTotal()));
        row(md, "总分", num(submission.total()));
        md.append('\n');

        md.append("## 错题\n\n");
        List<WrongItem> wrongItems = entry.wrongItems();
        if (wrongItems.isEmpty()) {
            md.append("无。\n");
        } else {
            md.append("| 题号 | 题型 | 我的作答 | 标准答案 | 分值 | 题干 |\n");
            md.append("| ---: | :--- | :--- | :--- | ---: | :--- |\n");
            for (WrongItem item : wrongItems) {
                md.append("| ").append(item.number())
                        .append(" | ").append(questionTypeLabel(item.questionType()))
                        .append(" | ").append(oneLine(item.answer()))
                        .append(" | ").append(oneLine(item.answerKey()))
                        .append(" | ").append(num(item.points()))
                        .append(" | ").append(oneLine(item.stem()))
                        .append(" |\n");
            }
        }
        md.append('\n');

        md.append("## 复核评语\n\n");
        String comment = entry.reviewComment();
        md.append(comment == null || comment.isBlank() ? "无。\n" : comment.strip() + "\n");
        return md.toString();
    }

    private static void row(StringBuilder md, String label, String value) {
        md.append("| ").append(oneLine(label)).append(" | ").append(oneLine(value)).append(" |\n");
    }

    private static void yaml(StringBuilder md, String key, String value) {
        md.append(key).append(": \"").append(value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ")).append("\"\n");
    }

    /** 表格单元格：竖线与换行会破坏 Markdown 表格结构。 */
    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("|", "\\|");
    }

    private static String time(Instant value) {
        return value == null ? "—" : TIME.format(value) + " UTC";
    }

    private static String num(BigDecimal value) {
        return value == null ? "—" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String rate(BigDecimal value) {
        return value == null ? "—" : value.toPlainString() + "%";
    }

    private static String averageText(BigDecimal average, boolean publishScores) {
        if (!publishScores) {
            return "未发布";
        }
        return average == null ? "—" : average.toPlainString();
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "EXAM" -> "考试";
            case "ASSIGNMENT" -> "作业";
            default -> kind == null ? "—" : kind;
        };
    }

    private static String gradingStateLabel(String state) {
        return switch (state == null ? "" : state) {
            case "DONE" -> "已批改";
            case "RUNNING" -> "批改中";
            case "PENDING", "QUEUED" -> "待批改";
            case "FAILED" -> "批改失败";
            case "DRAFT" -> "草稿";
            default -> state == null ? "—" : state;
        };
    }

    private static String questionTypeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "SINGLE_CHOICE" -> "单选";
            case "MULTIPLE_CHOICE" -> "多选";
            case "FILL_BLANK" -> "填空";
            case "ESSAY" -> "作文";
            default -> type == null ? "" : type;
        };
    }
}
