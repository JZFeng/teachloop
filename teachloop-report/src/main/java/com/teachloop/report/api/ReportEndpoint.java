package com.teachloop.report.api;

import com.teachloop.report.service.AggregateReportService;
import com.teachloop.report.service.WrongItemsReportService;
import com.teachloop.report.service.export.AggregateExportWriter;
import com.teachloop.report.service.export.PdfFontProbe;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表查询（T152 / T192）：常见错误 Top N，支持跨班级 / 跨时间段聚合。
 *
 * <p>教师只读自己班级、管理员全局（AC-17.7）；本接口先做角色闸门，学生 403。
 * 所有参数都经解析校验后再进参数化 SQL，不拼接用户输入。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportEndpoint {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final WrongItemsReportService wrongItems;
    private final AggregateReportService aggregate;
    private final PdfFontProbe pdfFont;
    /** T329：报表按 id 列表取数，必须先确认这些布置/班级属于调用者。 */
    private final Ports.OfferingOwnership ownership;
    private final Ports.ClassMembership membership;

    public ReportEndpoint(WrongItemsReportService wrongItems, AggregateReportService aggregate,
                          PdfFontProbe pdfFont, Ports.OfferingOwnership ownership,
                          Ports.ClassMembership membership) {
        this.wrongItems = wrongItems;
        this.aggregate = aggregate;
        this.pdfFont = pdfFont;
        this.ownership = ownership;
        this.membership = membership;
    }

    /** T151：按作业报表 XLSX。 */
    @org.springframework.web.bind.annotation.GetMapping("/offerings.xlsx")
    public org.springframework.http.ResponseEntity<byte[]> offeringsXlsx(
            @RequestParam String offeringIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        byte[] body = AggregateExportWriter.offeringsXlsx(aggregate.byOffering(ownedOfferings(offeringIds, me)));
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=report-offerings.xlsx")
                .body(body);
    }

    /** T151：按作业报表 PDF；字体不可用时 503。 */
    @org.springframework.web.bind.annotation.GetMapping("/offerings.pdf")
    public org.springframework.http.ResponseEntity<byte[]> offeringsPdf(
            @RequestParam String offeringIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        if (!pdfFont.available()) {
            throw new ApiException(com.teachloop.shared.error.ErrorCode.UNAVAILABLE, "PDF 中文字体不可用");
        }
        byte[] body = AggregateExportWriter.offeringsPdf(
                aggregate.byOffering(ownedOfferings(offeringIds, me)), pdfFont.font());
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=report-offerings.pdf")
                .body(body);
    }

    /** T150：按作业聚合；平均分只算已提交，未交单列。 */
    @GetMapping("/offerings")
    public List<AggregateReportService.OfferingRow> byOffering(
            @RequestParam String offeringIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        return aggregate.byOffering(ownedOfferings(offeringIds, me));
    }

    /** T150：按班级聚合。 */
    @GetMapping("/classes")
    public List<AggregateReportService.ClassRow> byClass(
            @RequestParam String classIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        return aggregate.byClass(ownedClasses(classIds, me));
    }

    /** T150：按学生聚合；分母是该生参与的场次。 */
    @GetMapping("/students")
    public List<AggregateReportService.StudentRow> byStudent(
            @RequestParam String offeringIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        return aggregate.byStudent(ownedOfferings(offeringIds, me));
    }

    /** 只保留调用者自己的布置；一个都不剩就 404（不告诉他「这些 id 存在但不归你」）。 */
    private List<UUID> ownedOfferings(String raw, CurrentUser me) {
        List<UUID> ids = requiredIds(raw);
        if (me.is("ADMIN")) {
            return ids;
        }
        Set<UUID> owned = ownership.ownedOfferingIds(ids, me.id());
        List<UUID> visible = ids.stream().filter(owned::contains).toList();
        if (visible.isEmpty()) {
            throw ApiException.notFound("error.report.noAccess");
        }
        return visible;
    }

    private List<UUID> ownedClasses(String raw, CurrentUser me) {
        List<UUID> ids = requiredIds(raw);
        if (me.is("ADMIN")) {
            return ids;
        }
        Set<UUID> owned = membership.classIdsOwnedBy(ids, me.id());
        List<UUID> visible = ids.stream().filter(owned::contains).toList();
        if (visible.isEmpty()) {
            throw ApiException.notFound("error.report.noAccess");
        }
        return visible;
    }

    private static void requireStaff(CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能查看报表");
        }
    }

    private static List<UUID> requiredIds(String raw) {
        List<UUID> ids = uuidList(raw);
        if (ids.isEmpty()) {
            throw ApiException.badRequest("id 列表必填，逗号分隔的 UUID");
        }
        return ids;
    }

    /**
     * {@code GET /api/reports/wrong-items?offeringIds=a,b&classIds=c,d&from=ISO&to=ISO&limit=10}
     *
     * <p>统计范围覆盖所有有作答的客观题，limit 只截断返回（AC-17.10）。
     */
    @GetMapping("/wrong-items")
    public ReportDtos.WrongItemsView wrongItems(
            @RequestParam(required = false) String offeringIds,
            @RequestParam(required = false) String classIds,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit,
            @RequestAttribute("currentUser") CurrentUser me) {
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("学生不能查看报表");
        }

        List<UUID> offeringFilter = uuidList(offeringIds);
        if (offeringFilter.isEmpty()) {
            throw ApiException.badRequest("offeringIds 必填，逗号分隔的 UUID");
        }
        List<UUID> classFilter = uuidList(classIds);
        return wrongItems.query(offeringFilter, classFilter,
                instant(from, "from"), instant(to, "to"), limit(limit));
    }

    private static List<UUID> uuidList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(value));
            } catch (RuntimeException ex) {
                throw ApiException.badRequest("不是合法的 UUID：" + value);
            }
        }
        return new ArrayList<>(ids);
    }

    private static Instant instant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest(field + " 必须是 ISO-8601 时间（如 2026-12-31T23:59:59Z）");
        }
    }

    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("limit 必须是 1–100 的整数");
        }
        if (value < 1 || value > MAX_LIMIT) {
            throw ApiException.badRequest("limit 必须在 1–100 之间");
        }
        return value;
    }
}
