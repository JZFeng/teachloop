package com.teachloop.paper.port;

import com.teachloop.paper.domain.*;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** paper 暴露给 sitting / grading 的只读端口。 */
@Component
public class PaperCatalogImpl implements Ports.PaperCatalog {

    private final PaperRepository papers;
    private final ObjectiveItemRepository items;
    private final SectionCriteriaRepository criteria;

    public PaperCatalogImpl(PaperRepository papers, ObjectiveItemRepository items, SectionCriteriaRepository criteria) {
        this.papers = papers;
        this.items = items;
        this.criteria = criteria;
    }

    @Override
    public Ports.PaperSnapshot load(UUID paperId) {
        Paper p = papers.findById(paperId).orElseThrow(() -> ApiException.notFound("试卷不存在"));
        List<Ports.ItemSpec> specs = items.findByPaperIdOrderByNumber(paperId).stream()
                .map(i -> new Ports.ItemSpec(i.getId(), i.getNumber(), i.getQuestionType().name(),
                        i.getAnswerKey(), i.getPoints(), i.getStem()))
                .toList();
        String rubric = p.getRubricMarkdown() == null ? defaultRubric() : p.getRubricMarkdown();
        return new Ports.PaperSnapshot(paperId, p.getVersion(), p.getTitle(), p.getTotalPoints().intValue(),
                p.getDefaultDurationMinutes() == null ? 60 : p.getDefaultDurationMinutes(), specs, rubric);
    }

    private String defaultRubric() {
        return """
               # 评分标准
               - 内容要点：是否覆盖题目要求的全部要点。
               - 语言准确性：语法、用词与句式是否准确得体。
               """;
    }
}
