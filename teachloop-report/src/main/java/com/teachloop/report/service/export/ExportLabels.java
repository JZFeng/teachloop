package com.teachloop.report.service.export;

import java.util.Locale;

/**
 * 导出表头与固定文案的双语标签（T123：表头随 {@code lang=zh-CN|en}）。
 *
 * <p>Markdown 与 ZIP 保持原样（中文），只有 XLSX / DOCX 的表头按 lang 切换；
 * PDF 也支持 lang，默认 zh-CN。所有字符串都是导出模板的一部分，不来自数据库。
 */
public final class ExportLabels {

    private final boolean en;

    private ExportLabels(boolean en) {
        this.en = en;
    }

    /** lang 以 en 开头时用英文，其余（含空）用中文。 */
    public static ExportLabels of(String lang) {
        return new ExportLabels(lang != null
                && lang.trim().toLowerCase(Locale.ROOT).startsWith("en"));
    }

    // ----- 汇总：标题与作业信息 -----

    public String summaryTitle() {
        return en ? "Class summary" : "班级汇总";
    }

    public String assignmentInfo() {
        return en ? "Assignment info" : "作业信息";
    }

    public String assignmentName() {
        return en ? "Assignment" : "作业名";
    }

    public String type() {
        return en ? "Type" : "类型";
    }

    public String dueAt() {
        return en ? "Due at" : "截止时间";
    }

    public String scoresPublished() {
        return en ? "Scores published" : "成绩发布";
    }

    public String published() {
        return en ? "Published" : "已发布";
    }

    public String notPublished() {
        return en ? "Not published" : "未发布";
    }

    public String exam() {
        return en ? "Exam" : "考试";
    }

    public String assignment() {
        return en ? "Assignment" : "作业";
    }

    // ----- 汇总：按班统计 -----

    public String byClass() {
        return en ? "By class" : "按班统计";
    }

    public String classId() {
        return en ? "Class id" : "班级 id";
    }

    public String className() {
        return en ? "Class name" : "班级名称";
    }

    public String expected() {
        return en ? "Expected" : "应交";
    }

    public String submitted() {
        return en ? "Submitted" : "实交";
    }

    public String onTime() {
        return en ? "On time" : "按时";
    }

    public String late() {
        return en ? "Late" : "迟交";
    }

    public String rejected() {
        return en ? "Rejected" : "拒收";
    }

    public String onTimeRate() {
        return en ? "On-time rate" : "按时提交率";
    }

    public String average() {
        return en ? "Average" : "平均分";
    }

    // ----- 汇总：逐份提交 -----

    public String bySubmission() {
        return en ? "Submissions" : "逐份提交";
    }

    public String student() {
        return en ? "Student" : "学生";
    }

    public String attempt() {
        return en ? "Attempt" : "attempt";
    }

    public String submittedAt() {
        return en ? "Submitted at" : "提交时间";
    }

    public String lateSeconds() {
        return en ? "Late (seconds)" : "迟交（秒）";
    }

    public String status() {
        return en ? "Status" : "状态";
    }

    public String objective() {
        return en ? "Objective" : "客观分";
    }

    public String subjective() {
        return en ? "Subjective" : "主观分";
    }

    public String total() {
        return en ? "Total" : "总分";
    }

    public String notSubmitted() {
        return en ? "Not submitted" : "未提交名单";
    }

    public String none() {
        return en ? "None." : "无。";
    }

    // ----- 状态 -----

    public String graded() {
        return en ? "Graded" : "已批改";
    }

    public String grading() {
        return en ? "Grading" : "批改中";
    }

    public String pending() {
        return en ? "Pending" : "待批改";
    }

    public String failed() {
        return en ? "Failed" : "批改失败";
    }

    public String draft() {
        return en ? "Draft" : "草稿";
    }

    // ----- 逐份反馈（PDF） -----

    public String feedbackTitle() {
        return en ? "Feedback" : "逐份反馈";
    }

    public String objectiveSubtotal() {
        return en ? "Objective subtotal" : "客观题小计";
    }

    public String subjectiveSubtotal() {
        return en ? "Subjective subtotal" : "主观题小计";
    }

    public String wrongAnswers() {
        return en ? "Wrong answers" : "错题";
    }

    public String number() {
        return en ? "No." : "题号";
    }

    public String questionType() {
        return en ? "Type" : "题型";
    }

    public String myAnswer() {
        return en ? "My answer" : "我的作答";
    }

    public String answerKey() {
        return en ? "Answer key" : "标准答案";
    }

    public String points() {
        return en ? "Points" : "分值";
    }

    public String stem() {
        return en ? "Stem" : "题干";
    }

    public String reviewComment() {
        return en ? "Review comment" : "复核评语";
    }

    public String yes() {
        return en ? "Yes" : "是";
    }

    public String no() {
        return en ? "No" : "否";
    }
}
