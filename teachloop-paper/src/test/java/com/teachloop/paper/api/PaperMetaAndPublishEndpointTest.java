package com.teachloop.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.teachloop.paper.domain.ObjectiveItem;
import com.teachloop.paper.domain.ObjectiveItemRepository;
import com.teachloop.paper.domain.Paper;
import com.teachloop.paper.domain.PaperContentSnapshotRepository;
import com.teachloop.paper.domain.PaperRepository;
import com.teachloop.paper.domain.PaperSection;
import com.teachloop.paper.domain.PaperSectionRepository;
import com.teachloop.paper.domain.QuestionType;
import com.teachloop.paper.domain.SectionCriteriaRepository;
import com.teachloop.shared.audit.AuditEvent;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiError;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;

/**
 * T460 / T461：`PUT /api/papers/{id}/meta`、发布三条复校验、复制语义（AC-4.26–4.27）。
 *
 * <p>不启 Spring 容器：仓储全是 Mockito 替身，文案资源用真实的
 * {@code messages.properties}（{@link ResourceBundleMessageSource}，同样是纯 Java，不起上下文）。</p>
 */
class PaperMetaAndPublishEndpointTest {

    private final PaperRepository papers = mock(PaperRepository.class);
    private final PaperSectionRepository sectionRepo = mock(PaperSectionRepository.class);
    private final ObjectiveItemRepository itemRepo = mock(ObjectiveItemRepository.class);
    private final SectionCriteriaRepository criteriaRepo = mock(SectionCriteriaRepository.class);
    private final PaperContentSnapshotRepository snapshotRepo = mock(PaperContentSnapshotRepository.class);
    private final AuditPort audit = mock(AuditPort.class);

    private PaperEndpoint endpoint;
    private PaperEndpoint plainEndpoint;
    private UUID paperId;
    private UUID teacherId;
    private Paper paper;
    private PaperSection section;

    private final List<ObjectiveItem> itemRows = new ArrayList<>();
    private final List<Paper> savedPapers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        messages.setUseCodeAsDefaultMessage(false);

        Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
        endpoint = new PaperEndpoint(papers, sectionRepo, itemRepo, criteriaRepo, audit, clock,
                id -> 0, (id, keys) -> 0, snapshotRepo, messages);
        // 另一个不注入文案资源：断言「抛错时给的是消息 key」时用它（GlobalExceptionHandler 在生产里解析）
        plainEndpoint = new PaperEndpoint(papers, sectionRepo, itemRepo, criteriaRepo, audit, clock);

        paperId = UUID.randomUUID();
        teacherId = UUID.randomUUID();
        paper = new Paper(paperId, "七年级英语月考", 90, teacherId, Instant.now());
        paper.setAuthorName("李璐");
        paper.setReviewerName("刘嘉伦");
        paper.setTargetTotalPoints(new BigDecimal("150"));
        paper.setContentMarkdown("# 原卷\n1. 甲题");
        section = new PaperSection(UUID.randomUUID(), paperId, "一、单项选择", 1, new BigDecimal("2"));

        when(papers.findById(paperId)).thenReturn(Optional.of(paper));
        when(papers.save(any(Paper.class))).thenAnswer(invocation -> {
            Paper saved = invocation.getArgument(0);
            savedPapers.add(saved);
            return saved;
        });
        when(sectionRepo.findByPaperIdOrderByPosition(paperId)).thenAnswer(inv -> List.of(section));
        when(itemRepo.findByPaperIdOrderByNumber(paperId)).thenAnswer(inv -> List.copyOf(itemRows));
        when(criteriaRepo.findByPaperIdOrderByPosition(paperId)).thenReturn(List.of());
    }

    private CurrentUser teacher() {
        return new CurrentUser(teacherId, "TEACHER", "王老师");
    }

    private ObjectiveItem item(int number, QuestionType type, String answerKey, String points) {
        ObjectiveItem item = new ObjectiveItem(UUID.randomUUID(), paperId, section.getId(), number, number,
                type, "第 " + number + " 题", type == QuestionType.ESSAY ? null : "A. 一\nB. 二",
                answerKey, new BigDecimal(points));
        itemRows.add(item);
        return item;
    }

    // ---------------------------------------------------------------- meta

    @Test
    void metaReplacesAllThreeFieldsAndLeavesTitleDurationAndTotalAlone() {
        paper.setTotalPoints(new BigDecimal("148.5"));

        PaperEndpoint.PaperView view = endpoint.updateMeta(paperId,
                new PaperEndpoint.UpdateMeta("张老师", "赵老师", new BigDecimal("150")), teacher());

        assertThat(paper.getAuthorName()).isEqualTo("张老师");
        assertThat(paper.getReviewerName()).isEqualTo("赵老师");
        assertThat(paper.getTargetTotalPoints()).isEqualByComparingTo("150");
        // 只改这三列：标题、时长、自动合计的 total_points 都不动（AC-4.26④）
        assertThat(paper.getTitle()).isEqualTo("七年级英语月考");
        assertThat(paper.getDefaultDurationMinutes()).isEqualTo(90);
        assertThat(paper.getTotalPoints()).isEqualByComparingTo("148.5");
        assertThat(view.authorName()).isEqualTo("张老师");
        assertThat(view.reviewerName()).isEqualTo("赵老师");
        assertThat(view.targetTotalPoints()).isEqualByComparingTo("150");
        assertThat(view.totalPoints()).isEqualByComparingTo("148.5");
    }

    @Test
    void metaClearsFieldsWhenTheyAreNull() {
        PaperEndpoint.PaperView view = endpoint.updateMeta(paperId,
                new PaperEndpoint.UpdateMeta(null, null, null), teacher());

        assertThat(paper.getAuthorName()).isNull();
        assertThat(paper.getReviewerName()).isNull();
        assertThat(paper.getTargetTotalPoints()).isNull();
        assertThat(view.authorName()).isNull();
        assertThat(view.reviewerName()).isNull();
        assertThat(view.targetTotalPoints()).isNull();
    }

    @Test
    void metaTreatsBlankNamesAsCleared() {
        endpoint.updateMeta(paperId, new PaperEndpoint.UpdateMeta("   ", "", null), teacher());

        assertThat(paper.getAuthorName()).isNull();
        assertThat(paper.getReviewerName()).isNull();
    }

    @Test
    void metaOnPublishedPaperIsAllowedAndAudited() {
        paper.publish();

        endpoint.updateMeta(paperId, new PaperEndpoint.UpdateMeta("张老师", null, new BigDecimal("150")),
                teacher());

        assertThat(paper.getAuthorName()).isEqualTo("张老师");
        assertThat(paper.getStatus().name()).isEqualTo("PUBLISHED");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(audit).record(event.capture());
        assertThat(event.getValue().action()).isEqualTo("paper:meta_updated");
        assertThat(event.getValue().targetId()).isEqualTo(paperId.toString());
        assertThat(event.getValue().detail()).containsEntry("status", "PUBLISHED");
    }

    @Test
    void metaRejectsNegativeTargetTotal() {
        assertThatThrownBy(() -> endpoint.updateMeta(paperId,
                new PaperEndpoint.UpdateMeta(null, null, new BigDecimal("-1")), teacher()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("error.paper.targetNegative"));
    }

    @Test
    void metaIsForbiddenForAnotherTeacher() {
        CurrentUser other = new CurrentUser(UUID.randomUUID(), "TEACHER", "别的老师");

        assertThatThrownBy(() -> endpoint.updateMeta(paperId,
                new PaperEndpoint.UpdateMeta("张老师", null, null), other))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ------------------------------------------------------------- publish

    @Test
    void publishRejectsZeroTotal() {
        paper.setTotalPoints(BigDecimal.ZERO);

        ResponseEntity<?> response = endpoint.publish(paperId, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiError error = (ApiError) response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.message()).as("消息走 key，不是写死的中文").doesNotContain("error.paper.");
        assertThat(paper.getStatus().name()).as("被拦下的卷子不许变成已发布").isEqualTo("DRAFT");
    }

    @Test
    void publishRejectsMissingAnswerKeyAndListsNumbersInDetail() {
        paper.setTotalPoints(new BigDecimal("4"));
        item(1, QuestionType.SINGLE_CHOICE, "A", "2");
        item(2, QuestionType.SINGLE_CHOICE, null, "2");

        ResponseEntity<?> response = endpoint.publish(paperId, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiError error = (ApiError) response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.detail()).as("缺答案的题号进 detail，前端拿它定位").isEqualTo("2");
        assertThat(paper.getStatus().name()).isEqualTo("DRAFT");
    }

    @Test
    void publishIgnoresEssaysWithoutAnswerKey() {
        paper.setTotalPoints(new BigDecimal("30"));
        paper.setTargetTotalPoints(null);
        item(76, QuestionType.ESSAY, null, "15");
        item(77, QuestionType.ESSAY, null, "15");

        ResponseEntity<?> response = endpoint.publish(paperId, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(paper.getStatus().name()).isEqualTo("PUBLISHED");
    }

    @Test
    void publishRejectsTotalThatDoesNotMatchTargetTotal() {
        paper.setTotalPoints(new BigDecimal("148.5"));
        paper.setTargetTotalPoints(new BigDecimal("150"));
        item(1, QuestionType.SINGLE_CHOICE, "A", "148.5");

        ResponseEntity<?> response = endpoint.publish(paperId, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiError error = (ApiError) response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.detail()).isEqualTo("total=148.5,target=150");
        assertThat(paper.getStatus().name()).isEqualTo("DRAFT");
    }

    @Test
    void publishAllowsDifferenceWithinTolerance() {
        paper.setTotalPoints(new BigDecimal("150.0005"));
        paper.setTargetTotalPoints(new BigDecimal("150"));
        item(1, QuestionType.SINGLE_CHOICE, "A", "150.0005");

        ResponseEntity<?> response = endpoint.publish(paperId, teacher());

        assertThat(response.getStatusCode().value()).as("差 0.0005 < 0.001 容差").isEqualTo(200);
        assertThat(paper.getStatus().name()).isEqualTo("PUBLISHED");
    }

    @Test
    void publishSkipsTargetCheckWhenTargetIsEmpty() {
        paper.setTargetTotalPoints(null);
        paper.setTotalPoints(new BigDecimal("2"));
        item(1, QuestionType.SINGLE_CHOICE, "A", "2");

        ResponseEntity<?> response = endpoint.publish(paperId, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        PaperEndpoint.PaperView view = (PaperEndpoint.PaperView) response.getBody();
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.totalPoints()).isEqualByComparingTo("2");
    }

    /** 单测里没注入 MessageSource 时给的是 key；生产由 GlobalExceptionHandler 解析（T130）。 */
    @Test
    void publishFailureCarriesStableMessageKeyWithoutMessageSource() {
        paper.setTotalPoints(new BigDecimal("4"));
        item(1, QuestionType.SINGLE_CHOICE, "A", "2");
        item(2, QuestionType.SINGLE_CHOICE, " ", "2");

        ApiError error = (ApiError) plainEndpoint.publish(paperId, teacher()).getBody();

        assertThat(error).isNotNull();
        assertThat(error.message()).isEqualTo("error.paper.missingAnswers");
        assertThat(error.detail()).isEqualTo("2");
    }

    // -------------------------------------------------------------- create

    @Test
    void createFillsAuthorNameFromCurrentTeacherWhenBlank() {
        PaperEndpoint.PaperView view = endpoint.create(
                new PaperEndpoint.CreatePaper("八年级英语期中", 100, null, "赵老师", new BigDecimal("120")),
                teacher());

        Paper created = savedPapers.stream().filter(p -> !p.getId().equals(paperId)).findFirst().orElseThrow();
        assertThat(created.getAuthorName()).as("AC-4.27②：命题人默认当前教师显示名").isEqualTo("王老师");
        assertThat(created.getReviewerName()).isEqualTo("赵老师");
        assertThat(created.getTargetTotalPoints()).isEqualByComparingTo("120");
        assertThat(view.authorName()).isEqualTo("王老师");
        assertThat(view.targetTotalPoints()).isEqualByComparingTo("120");
    }

    @Test
    void createKeepsExplicitAuthorName() {
        endpoint.create(new PaperEndpoint.CreatePaper("高一英语月考", null, "李璐", null, null), teacher());

        Paper created = savedPapers.stream().filter(p -> !p.getId().equals(paperId)).findFirst().orElseThrow();
        assertThat(created.getAuthorName()).isEqualTo("李璐");
        assertThat(created.getReviewerName()).isNull();
        assertThat(created.getTargetTotalPoints()).isNull();
    }

    @Test
    void createRejectsNegativeTargetTotal() {
        assertThatThrownBy(() -> endpoint.create(
                new PaperEndpoint.CreatePaper("坏目标", null, null, null, new BigDecimal("-0.01")), teacher()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo("error.paper.targetNegative"));
        assertThat(savedPapers).isEmpty();
    }

    @Test
    void detailExposesContentMarkdownForTheEditor() {
        PaperEndpoint.PaperDetail detail = endpoint.detail(paperId);

        assertThat(detail.contentMarkdown()).isEqualTo("# 原卷\n1. 甲题");
        assertThat(detail.paper().authorName()).isEqualTo("李璐");
        assertThat(detail.paper().targetTotalPoints()).isEqualByComparingTo("150");
    }

    // ---------------------------------------------------------------- copy

    @Test
    void copyCarriesContentAndTargetButResetsAuthorAndReviewer() {
        paper.setTotalPoints(new BigDecimal("150"));
        item(1, QuestionType.SINGLE_CHOICE, "A", "2");

        PaperEndpoint.PaperView view = endpoint.copy(paperId, teacher());

        Paper copy = savedPapers.stream().filter(p -> !p.getId().equals(paperId)).findFirst().orElseThrow();
        assertThat(copy.getId().toString()).isEqualTo(view.id());
        assertThat(copy.getTitle()).isEqualTo("七年级英语月考（副本）");
        assertThat(copy.getContentMarkdown()).as("原文跟着复制，新卷才能再生成").isEqualTo("# 原卷\n1. 甲题");
        assertThat(copy.getTargetTotalPoints()).isEqualByComparingTo("150");
        assertThat(copy.getAuthorName()).as("命题人 = 当前教师").isEqualTo("王老师");
        assertThat(copy.getReviewerName()).as("审题人不继承").isNull();
        assertThat(copy.getStatus().name()).isEqualTo("DRAFT");
        assertThat(view.authorName()).isEqualTo("王老师");
        assertThat(view.reviewerName()).isNull();
        assertThat(view.targetTotalPoints()).isEqualByComparingTo("150");
    }
}
