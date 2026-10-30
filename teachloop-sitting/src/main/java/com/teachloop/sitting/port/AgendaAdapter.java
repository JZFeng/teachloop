package com.teachloop.sitting.port;

import com.teachloop.shared.port.Ports;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 011 / T558：{@link Ports.AgendaPort} 的 sitting 实现。
 *
 * <p>三件事：
 * <ol>
 *   <li>班级从 {@link Ports.ClassMembership#classIdsOf} 取（1 次 O(1) 调用），不 join 别模块的表；</li>
 *   <li>同一场布置发到多个班时按 offering 去重，截止时间取**更早**那条
 *       （与 {@code OfferingWindows.resolve} 的「取最早截止」一致）；</li>
 *   <li>班名一次批量补（{@code classNames}），不在循环里逐个查。</li>
 * </ol>
 * 排序不在这里做：端点按「逾期在前、dueAt 升序」再排一次，端口只保证数据完整。
 */
@Component
public class AgendaAdapter implements Ports.AgendaPort {

    private final AgendaStore store;
    private final Ports.ClassMembership members;

    public AgendaAdapter(AgendaStore store, Ports.ClassMembership members) {
        this.store = store;
        this.members = members;
    }

    @Override
    public List<Ports.AssignmentRow> pending(UUID studentId) {
        if (studentId == null) {
            return List.of();
        }
        List<UUID> classIds = members.classIdsOf(studentId);
        if (classIds.isEmpty()) {
            return List.of();
        }
        List<AgendaStore.PendingRow> rows = store.pending(classIds, studentId);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 去重：同一场只留截止更早的那条；LinkedHashMap 保留查询的 due_at 升序（稳定输出）。
        Map<UUID, AgendaStore.PendingRow> earliest = new LinkedHashMap<>();
        for (AgendaStore.PendingRow row : rows) {
            earliest.merge(row.offeringId(), row,
                    (left, right) -> left.dueAt().isAfter(right.dueAt()) ? right : left);
        }
        Map<UUID, String> names = classNamesOf(earliest.values().stream()
                .map(AgendaStore.PendingRow::classId).toList(), classIds);
        return earliest.values().stream()
                .map(row -> new Ports.AssignmentRow(row.offeringId(), row.title(),
                        names.get(row.classId()), row.dueAt(), row.status()))
                .toList();
    }

    @Override
    public List<Ports.ExamRow> upcoming(UUID studentId, Instant from, Instant to) {
        if (studentId == null || from == null || to == null) {
            return List.of();
        }
        List<UUID> classIds = members.classIdsOf(studentId);
        if (classIds.isEmpty()) {
            return List.of();
        }
        List<AgendaStore.ExamRow> rows = store.upcoming(classIds, from, to);
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, AgendaStore.ExamRow> earliest = new LinkedHashMap<>();
        for (AgendaStore.ExamRow row : rows) {
            earliest.merge(row.offeringId(), row,
                    (left, right) -> left.startsAt().isAfter(right.startsAt()) ? right : left);
        }
        Map<UUID, String> names = classNamesOf(earliest.values().stream()
                .map(AgendaStore.ExamRow::classId).toList(), classIds);
        return earliest.values().stream()
                .map(row -> new Ports.ExamRow(row.offeringId(), row.title(),
                        names.get(row.classId()), row.startsAt()))
                .toList();
    }

    /** 班名查询失败不该让整段数据消失：回退成「没有班名标签」。 */
    private Map<UUID, String> classNamesOf(List<UUID> rowClassIds, List<UUID> fallback) {
        List<UUID> ids = rowClassIds.stream().distinct().toList();
        try {
            Map<UUID, String> names = members.classNames(ids.isEmpty() ? fallback : ids);
            return names == null ? Map.of() : names;
        } catch (RuntimeException ex) {
            return ids.stream().collect(Collectors.toMap(id -> id, id -> (String) null));
        }
    }
}
