package com.teachloop.observability.api;

import com.teachloop.observability.log.LogKind;
import com.teachloop.observability.log.LogSink;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/**
 * T141 / AC-16.2：前端事件采集。
 *
 * <p>批量接收 {@code {"events":[{"name":"page_view","props":{...}}]}}，只接受白名单事件名，
 * 追加写 {@code events-YYYY-MM-DD.jsonl}，**不入业务库**（daily_stats 在每日任务里另行汇总）。
 * 限流由 gate 过滤器统一负责，这里不返回 429。
 */
@RestController
@RequestMapping("/api")
public class EventEndpoint {

    public static final Set<String> ALLOWED_EVENTS = Set.of(
            "page_view", "action_click", "exam_start", "answer_save", "submit");

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final LogSink sink;
    private final Clock clock;

    public EventEndpoint(LogSink sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    public record EventItem(String name, Map<String, Object> props) {
    }

    public record EventBatch(List<EventItem> events) {
    }

    public record EventAccepted(int accepted) {
    }

    @PostMapping("/events")
    public EventAccepted ingest(@RequestBody EventBatch body,
                                @RequestAttribute("currentUser") CurrentUser me,
                                HttpServletRequest request) {
        if (body == null || body.events() == null || body.events().isEmpty()) {
            throw ApiException.badRequest("events 不能为空");
        }
        for (EventItem event : body.events()) {
            if (event == null || event.name() == null || !ALLOWED_EVENTS.contains(event.name())) {
                throw ApiException.badRequest("事件名不在白名单: "
                        + (event == null ? "null" : event.name()));
            }
        }
        // 先整批校验再写，避免「部分事件已落盘、部分被拒」的半成功状态。
        Instant now = Instant.now(clock);
        String requestId = requestId(request);
        for (EventItem event : body.events()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("ts", now.toString());
            fields.put("requestId", requestId);
            fields.put("userId", me.id().toString());
            fields.put("name", event.name());
            fields.put("props", event.props() == null ? Map.of() : event.props());
            sink.write(LogKind.EVENTS, now, fields);
        }
        return new EventAccepted(body.events().size());
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        return header != null && !header.isBlank()
                ? header.strip()
                : java.util.UUID.randomUUID().toString();
    }
}
