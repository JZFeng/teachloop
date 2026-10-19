package com.teachloop.paper.api;

import com.teachloop.shared.security.CurrentUser;
import com.teachloop.paper.domain.*;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/papers")
public class PaperEndpoint {

    private final PaperRepository papers;
    private final PaperSectionRepository sections;
    private final ObjectiveItemRepository items;
    private final SectionCriteriaRepository criteria;
    private final Clock clock;

    private final AuditPort audit;

    public PaperEndpoint(PaperRepository papers, PaperSectionRepository sections,
                         ObjectiveItemRepository items, SectionCriteriaRepository criteria,
                         AuditPort audit, Clock clock) {
        this.papers = papers;
        this.sections = sections;
        this.items = items;
        this.criteria = criteria;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreatePaper(@NotBlank String title, Integer defaultDurationMinutes) {
    }

    public record CreateSection(@NotBlank String title, BigDecimal pointsPerItem) {
    }

    public record CreateItem(@NotNull Integer number, @NotNull QuestionType questionType, String stem,
                             String options, String answerKey, @NotNull BigDecimal points, UUID sectionId) {
    }

    public record CreateCriteria(@NotBlank String name, @NotNull BigDecimal maxPoints, String description,
                                 UUID sectionId) {
    }

    public record Rubric(String rubricMarkdown, String gradingPromptTemplate) {
    }

    public record PaperView(String id, String title, int totalPoints, int version, String status,
                            Integer defaultDurationMinutes) {
    }

    public record ItemView(String id, int number, String questionType, String stem, String options,
                           String answerKey, int points) {
    }

    public record SectionView(String id, String title, int position, int pointsPerItem, List<ItemView> items) {
    }

    public record PaperDetail(PaperView paper, List<SectionView> sections, List<String> criteriaNames) {
    }

    @PostMapping
    @Transactional
    public PaperView create(@RequestBody @Valid CreatePaper body, @RequestAttribute("currentUser") CurrentUser me) {
        var p = new Paper(UUID.randomUUID(), body.title().trim(), body.defaultDurationMinutes(), me.id(), Instant.now(clock));
        papers.save(p);
        return view(p);
    }

    @GetMapping
    public List<PaperView> mine(@RequestAttribute("currentUser") CurrentUser me) {
        return papers.findByAuthorIdOrderByCreatedAtDesc(me.id()).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public PaperDetail detail(@PathVariable UUID id) {
        Paper p = load(id);
        List<SectionView> secs = sections.findByPaperIdOrderByPosition(id).stream()
                .map(s -> new SectionView(s.getId().toString(), s.getTitle(), s.getPosition(),
                        s.getPointsPerItem() == null ? 0 : s.getPointsPerItem().intValue(),
                        items.findByPaperSectionIdOrderByPosition(s.getId()).stream()
                                .map(i -> new ItemView(i.getId().toString(), i.getNumber(), i.getQuestionType().name(),
                                        i.getStem(), i.getOptions(), i.getAnswerKey(), i.getPoints().intValue()))
                                .toList()))
                .toList();
        List<String> crit = criteria.findByPaperIdOrderByPosition(id).stream().map(SectionCriteria::getName).toList();
        return new PaperDetail(view(p), secs, crit);
    }

    @PostMapping("/{id}/sections")
    @Transactional
    public SectionView addSection(@PathVariable UUID id, @RequestBody @Valid CreateSection body) {
        load(id);
        int pos = sections.findByPaperIdOrderByPosition(id).size() + 1;
        var s = new PaperSection(UUID.randomUUID(), id, body.title().trim(), pos, body.pointsPerItem());
        sections.save(s);
        return new SectionView(s.getId().toString(), s.getTitle(), pos,
                body.pointsPerItem() == null ? 0 : body.pointsPerItem().intValue(), List.of());
    }

    @PostMapping("/{id}/items")
    @Transactional
    public ItemView addItem(@PathVariable UUID id, @RequestBody @Valid CreateItem body) {
        Paper p = load(id);
        if (p.getStatus() == PaperStatus.PUBLISHED) {
            throw ApiException.conflict("已发布的试卷内容已锁定，请复制为新版本再改");
        }
        UUID sectionId = body.sectionId();
        if (sectionId == null) {
            sectionId = sections.findByPaperIdOrderByPosition(id).stream().findFirst()
                    .orElseThrow(() -> ApiException.badRequest("请先创建部分")).getId();
        }
        int pos = items.findByPaperSectionIdOrderByPosition(sectionId).size() + 1;
        var item = new ObjectiveItem(UUID.randomUUID(), id, sectionId, body.number(), pos,
                body.questionType(), body.stem(), body.options(), body.answerKey(), body.points());
        items.save(item);
        recalcTotal(p);
        return new ItemView(item.getId().toString(), item.getNumber(), item.getQuestionType().name(),
                item.getStem(), item.getOptions(), item.getAnswerKey(), item.getPoints().intValue());
    }

    @PostMapping("/{id}/criteria")
    @Transactional
    public void addCriteria(@PathVariable UUID id, @RequestBody @Valid CreateCriteria body) {
        Paper p = load(id);
        UUID sectionId = body.sectionId();
        if (sectionId == null) {
            sectionId = sections.findByPaperIdOrderByPosition(id).stream().findFirst()
                    .orElseThrow(() -> ApiException.badRequest("请先创建部分")).getId();
        }
        int pos = criteria.findByPaperIdOrderByPosition(id).size() + 1;
        criteria.save(new SectionCriteria(UUID.randomUUID(), id, sectionId, body.name().trim(),
                body.maxPoints(), body.description(), pos));
        recalcTotal(p);
    }

    @PutMapping("/{id}/rubric")
    @Transactional
    public PaperView rubric(@PathVariable UUID id, @RequestBody Rubric body) {
        Paper p = load(id);
        p.setRubricMarkdown(body.rubricMarkdown());
        p.setGradingPromptTemplate(body.gradingPromptTemplate());
        return view(p);
    }

    @PostMapping("/{id}/publish")
    @Transactional
    public PaperView publish(@PathVariable UUID id,
                             @RequestAttribute("currentUser") CurrentUser me) {
        Paper p = load(id);
        if (p.getTotalPoints().signum() <= 0) {
            throw ApiException.badRequest("总分为 0，不能发布");
        }
        p.publish();
        audit.record(AuditEvent.of(me.id(), "paper:publish", "paper", id.toString()));
        return view(p);
    }

    private void recalcTotal(Paper p) {
        BigDecimal sum = items.findByPaperIdOrderByNumber(p.getId()).stream()
                .map(ObjectiveItem::getPoints).reduce(BigDecimal.ZERO, BigDecimal::add);
        sum = sum.add(criteria.findByPaperIdOrderByPosition(p.getId()).stream()
                .map(SectionCriteria::getMaxPoints).reduce(BigDecimal.ZERO, BigDecimal::add));
        p.setTotalPoints(sum);
    }

    private Paper load(UUID id) {
        return papers.findById(id).orElseThrow(() -> ApiException.notFound("试卷不存在"));
    }

    private PaperView view(Paper p) {
        return new PaperView(p.getId().toString(), p.getTitle(), p.getTotalPoints().intValue(),
                p.getVersion(), p.getStatus().name(), p.getDefaultDurationMinutes());
    }
}
