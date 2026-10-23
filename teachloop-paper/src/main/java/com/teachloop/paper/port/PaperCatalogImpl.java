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
    private final PaperSectionRepository sections;
    private final ObjectiveItemRepository items;
    private final SectionCriteriaRepository criteria;

    public PaperCatalogImpl(PaperRepository papers, PaperSectionRepository sections,
                            ObjectiveItemRepository items, SectionCriteriaRepository criteria) {
        this.papers = papers;
        this.sections = sections;
        this.items = items;
        this.criteria = criteria;
    }

    @Override
    public Ports.PaperSnapshot load(UUID paperId) {
        Paper p = papers.findById(paperId).orElseThrow(() -> ApiException.notFound("试卷不存在"));
        var sectionById = sections.findByPaperIdOrderByPosition(paperId).stream()
                .collect(java.util.stream.Collectors.toMap(PaperSection::getId, s -> s));
        List<Ports.ItemSpec> specs = items.findByPaperIdOrderByNumber(paperId).stream()
                .map(i -> {
                    PaperSection section = sectionById.get(i.getPaperSectionId());
                    return new Ports.ItemSpec(i.getId(), i.getNumber(), i.getQuestionType().name(),
                            i.getAnswerKey(), i.getPoints(), i.getStem(), i.getOptions(),
                            section == null ? 0 : section.getPosition(),
                            section == null ? null : section.getTitle(),
                            i.getScoringStrategy().name(), i.getRubricMarkdown());
                })
                .toList();
        // D-29：试卷级 rubric 退役（列与接口保留兼容）。快照不再携带它；考试的写作题只认
        // 题项级 rubric，没有就由判分侧按配置合成「内容 + 语法与拼写」默认项（D-28）。
        return new Ports.PaperSnapshot(paperId, p.getVersion(), p.getTitle(), p.getTotalPoints().intValue(),
                p.getDefaultDurationMinutes() == null ? 60 : p.getDefaultDurationMinutes(), specs, null);
    }
}
