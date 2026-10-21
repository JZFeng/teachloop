package com.teachloop.sitting.api;

import com.teachloop.sitting.service.RubricValidator;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.*;

/** T258：创建作业前预览 rubric 的解析结果与校验问题，不落库。 */
@RestController
@RequestMapping("/api/rubrics")
public class RubricPreviewEndpoint {

    private final MessageSource messages;

    public RubricPreviewEndpoint(MessageSource messages) {
        this.messages = messages;
    }

    public record Request(String rubricMarkdown, BigDecimal totalPoints) {
    }

    public record Response(boolean valid, List<RubricValidator.Criterion> criteria,
                           BigDecimal total, List<String> issues) {
    }

    @PostMapping("/preview")
    public Response preview(@RequestBody Request body) {
        RubricValidator.Result result = RubricValidator.validate(
                body == null ? null : body.rubricMarkdown(),
                body == null ? null : body.totalPoints());
        return new Response(result.valid(), result.criteria(), result.total(),
                RubricIssueText.render(messages, result.issues()));
    }
}
