package com.teachloop.report.api;

import com.teachloop.report.service.AggregateReportService;
import com.teachloop.report.service.WrongItemsReportService;
import com.teachloop.shared.error.ApiException;
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

    public ReportEndpoint(WrongItemsReportService wrongItems, AggregateReportService aggregate) {
        this.wrongItems = wrongItems;
        this.aggregate = aggregate;
    }

    /** T150：按作业聚合；平均分只算已提交，未交单列。 */
    @GetMapping("/offerings")
    public List<AggregateReportService.OfferingRow> byOffering(
            @RequestParam String offeringIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        return aggregate.byOffering(requiredIds(offeringIds));
    }

    /** T150：按班级聚合。 */
    @GetMapping("/classes")
    public List<AggregateReportService.ClassRow> byClass(
            @RequestParam String classIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        return aggregate.byClass(requiredIds(classIds));
    }

    /** T150：按学生聚合；分母是该生参与的场次。 */
    @GetMapping("/students")
    public List<AggregateReportService.StudentRow> byStudent(
            @RequestParam String offeringIds, @RequestAttribute("currentUser") CurrentUser me) {
        requireStaff(me);
        return aggregate.byStudent(requiredIds(offeringIds));
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
