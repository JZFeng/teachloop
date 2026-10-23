package com.teachloop.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.teachloop.paper.domain.ObjectiveItem;
import com.teachloop.paper.domain.ObjectiveItemRepository;
import com.teachloop.paper.domain.Paper;
import com.teachloop.paper.domain.PaperRepository;
import com.teachloop.paper.domain.PaperSection;
import com.teachloop.paper.domain.PaperSectionRepository;
import com.teachloop.paper.domain.QuestionType;
import com.teachloop.paper.domain.SectionCriteriaRepository;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiError;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/** T186：导入端点的创建、跳过、缺答案与权限行为（不启动 Spring 容器）。 */
class PaperImportEndpointTest {

    private final PaperRepository papers = mock(PaperRepository.class);
    private final PaperSectionRepository sections = mock(PaperSectionRepository.class);
    private final ObjectiveItemRepository items = mock(ObjectiveItemRepository.class);
    private final SectionCriteriaRepository criteria = mock(SectionCriteriaRepository.class);
    private final AuditPort audit = mock(AuditPort.class);

    private PaperEndpoint endpoint;
    private UUID paperId;
    private UUID sectionId;
    private UUID teacherId;
    private Paper paper;
    private final List<ObjectiveItem> persisted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        endpoint = new PaperEndpoint(papers, sections, items, criteria, audit, Clock.systemUTC());
        paperId = UUID.randomUUID();
        sectionId = UUID.randomUUID();
        teacherId = UUID.randomUUID();
        paper = new Paper(paperId, "七年级英语月考", 90, teacherId, Instant.now());
        PaperSection section = new PaperSection(sectionId, paperId, "一、单项选择", 1, new BigDecimal("2"));

        when(papers.findById(paperId)).thenReturn(Optional.of(paper));
        when(sections.findByPaperIdOrderByPosition(paperId)).thenReturn(List.of(section));
        when(criteria.findByPaperIdOrderByPosition(paperId)).thenReturn(List.of());
        when(items.findByPaperIdOrderByNumber(paperId)).thenAnswer(invocation -> List.copyOf(persisted));
        when(items.findByPaperSectionIdOrderByPosition(sectionId)).thenAnswer(invocation ->
                persisted.stream().filter(item -> sectionId.equals(item.getPaperSectionId())).toList());
        when(items.save(any(ObjectiveItem.class))).thenAnswer(invocation -> {
            ObjectiveItem item = invocation.getArgument(0);
            persisted.add(item);
            return item;
        });
    }

    private static CurrentUser teacher(UUID id) {
        return new CurrentUser(id, "TEACHER", "王老师");
    }

    private MockMultipartFile markdownFile() {
        String text = """
                1. 中国的首都是哪里？
                A. 北京
                B. 上海
                2. 水的化学式是什么？
                A. H2O
                B. CO2
                答案
                1. A
                2. B
                """;
        return new MockMultipartFile("file", "paper.md", "text/markdown",
                text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void importsItemsAndRecalculatesTotalPoints() {
        ResponseEntity<?> response =
                endpoint.importFile(paperId, markdownFile(), null, teacher(teacherId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        PaperEndpoint.ImportResult body = (PaperEndpoint.ImportResult) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imported()).isEqualTo(2);
        assertThat(body.missingAnswers()).isEmpty();
        assertThat(body.items()).extracting(PaperEndpoint.ImportedItem::answerKey)
                .containsExactly("A", "B");
        assertThat(body.items().get(0).options()).isEqualTo("A. 北京\nB. 上海");

        assertThat(persisted).hasSize(2);
        assertThat(persisted).allSatisfy(item -> {
            assertThat(item.getPoints()).isEqualByComparingTo("2");
            assertThat(item.getQuestionType()).isEqualTo(QuestionType.SINGLE_CHOICE);
        });
        assertThat(persisted).extracting(ObjectiveItem::getNumber).containsExactly(1, 2);
        assertThat(paper.getTotalPoints()).isEqualByComparingTo("4");
    }

    @Test
    void importsWritingTasksAsEssayItemsWithReference() {
        String text = """
                ## 第四部分 写作（共两节，满分 30 分）
                15. 应用文写作（满分 15 分）

                写一封回信，给出建议。
                **Dear Chen Ming:**

                # 试卷参考答案
                15. 应用文写作示范范文：
                Dear Chen Ming,
                I understand your feeling.
                """;
        MockMultipartFile file = new MockMultipartFile("file", "writing.md", "text/markdown",
                text.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> response =
                endpoint.importFile(paperId, file, sectionId, teacher(teacherId));

        PaperEndpoint.ImportResult body = (PaperEndpoint.ImportResult) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imported()).isEqualTo(1);
        assertThat(body.missingAnswers()).as("写作题不算缺答案").isEmpty();
        assertThat(persisted).hasSize(1);
        ObjectiveItem essay = persisted.get(0);
        assertThat(essay.getQuestionType()).isEqualTo(QuestionType.ESSAY);
        assertThat(essay.getNumber()).isEqualTo(15);
        assertThat(essay.getPoints()).isEqualByComparingTo("15");
        assertThat(essay.getReferenceMarkdown()).contains("I understand your feeling.");
        assertThat(essay.getRubricMarkdown()).as("题面要点没有分值，不预填 rubric").isNull();
        assertThat(paper.getTotalPoints()).isEqualByComparingTo("15");
    }

    @Test
    void rejectsRubricOnObjectiveItem() {
        PaperEndpoint.CreateItem body = new PaperEndpoint.CreateItem(3, QuestionType.SINGLE_CHOICE,
                "题干", "A. 一\nB. 二", "A", new BigDecimal("2"), sectionId, "ALL_OR_NOTHING",
                "- 内容要点（2 分）", null);

        assertThatThrownBy(() -> endpoint.addItem(paperId, body, false, teacher(teacherId)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(persisted).isEmpty();
    }

    @Test
    void skipsExistingNumbersWithWarningInsteadOfOverwriting() {
        persisted.add(new ObjectiveItem(UUID.randomUUID(), paperId, sectionId, 1, 1,
                QuestionType.SINGLE_CHOICE, "旧题", "A. 旧\nB. 旧", "A", new BigDecimal("2")));

        ResponseEntity<?> response =
                endpoint.importFile(paperId, markdownFile(), null, teacher(teacherId));

        PaperEndpoint.ImportResult body = (PaperEndpoint.ImportResult) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imported()).isEqualTo(1);
        assertThat(body.items()).extracting(PaperEndpoint.ImportedItem::number).containsExactly(2);
        assertThat(body.warnings()).anyMatch(w -> w.contains("题号 1 已存在") && w.contains("已跳过"));
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).getStem()).isEqualTo("旧题");
        assertThat(paper.getTotalPoints()).isEqualByComparingTo("4");
    }

    @Test
    void listsMissingAnswersInsteadOfFailing() {
        String text = """
                1. 甲题
                A. 一
                2. 乙题
                A. 一
                答案
                1. A
                """;
        MockMultipartFile file = new MockMultipartFile("file", "paper.md", "text/markdown",
                text.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> response =
                endpoint.importFile(paperId, file, sectionId, teacher(teacherId));

        assertThat(response.getBody()).isNotNull();
        assertThat(((PaperEndpoint.ImportResult) response.getBody()).imported()).isEqualTo(2);
        assertThat(((PaperEndpoint.ImportResult) response.getBody()).missingAnswers()).containsExactly(2);
        assertThat(persisted.get(1).getAnswerKey()).isNull();
    }

    @Test
    void rejectsStudentWithForbidden() {
        CurrentUser student = new CurrentUser(UUID.randomUUID(), "STUDENT", "小李");

        assertThatThrownBy(() -> endpoint.importFile(paperId, markdownFile(), null, student))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(persisted).isEmpty();
    }

    @Test
    void returnsBadRequestWithDetailForBrokenZip() {
        MockMultipartFile file = new MockMultipartFile("file", "broken.docx",
                "application/octet-stream", "not-a-zip".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> response =
                endpoint.importFile(paperId, file, null, teacher(teacherId));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiError error = (ApiError) response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.message()).isEqualTo("导入失败");
        assertThat(error.detail()).contains("docx");
        assertThat(persisted).isEmpty();
    }

    @Test
    void returnsBadRequestWithLineNumberForDuplicateQuestion() {
        String text = """
                1. 甲
                A. 一
                1. 乙
                A. 一
                """;
        MockMultipartFile file = new MockMultipartFile("file", "paper.md", "text/markdown",
                text.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> response =
                endpoint.importFile(paperId, file, null, teacher(teacherId));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiError error = (ApiError) response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.detail()).contains("第 3 行").contains("重复");
        assertThat(persisted).isEmpty();
    }

    /**
     * T235：校对页「粘贴答案表」走的契约 —— 补完答案后 {@code GET /papers/{id}} 的题项里要有 answerKey，
     * 前端就是靠它把「缺答案」清掉的。
     */
    @Test
    void fillsAnswersThroughSeparateAnswerTableAndDetailReflectsThem() {
        persisted.add(new ObjectiveItem(UUID.randomUUID(), paperId, sectionId, 1, 1,
                QuestionType.SINGLE_CHOICE, "甲题", "A. 一\nB. 二", null, new BigDecimal("2")));
        persisted.add(new ObjectiveItem(UUID.randomUUID(), paperId, sectionId, 2, 2,
                QuestionType.SINGLE_CHOICE, "乙题", "A. 一\nB. 二", null, new BigDecimal("2")));
        when(items.findByPaperIdAndNumber(any(), any(Integer.class))).thenAnswer(invocation -> {
            int number = invocation.getArgument(1);
            return persisted.stream().filter(item -> item.getNumber() == number).findFirst();
        });
        when(items.save(any(ObjectiveItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperEndpoint.AnswerTableResult result = endpoint.fillAnswers(paperId,
                new PaperEndpoint.AnswerTableRequest("1 A\n2 B\n9 C"), false, teacher(teacherId));

        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.unknownNumbers()).containsExactly(9);

        PaperEndpoint.PaperDetail detail = endpoint.detail(paperId);
        assertThat(detail.sections()).hasSize(1);
        assertThat(detail.sections().get(0).items())
                .extracting(PaperEndpoint.ItemView::answerKey)
                .containsExactly("A", "B");
    }

    /**
     * T236：填空题的答案不是字母。端点要按题项类型把题号交给文本通道，
     * 否则 {@code challenging} 会被读成 {@code C}。
     */
    @Test
    void fillsFillBlankAnswerAsText() {
        persisted.add(new ObjectiveItem(UUID.randomUUID(), paperId, sectionId, 1, 1,
                QuestionType.SINGLE_CHOICE, "选择题", "A. 一\nB. 二", null, new BigDecimal("2")));
        persisted.add(new ObjectiveItem(UUID.randomUUID(), paperId, sectionId, 2, 2,
                QuestionType.FILL_BLANK, "语法填空 ______", null, null, new BigDecimal("2")));
        when(items.findByPaperIdAndNumber(any(), any(Integer.class))).thenAnswer(invocation -> {
            int number = invocation.getArgument(1);
            return persisted.stream().filter(item -> item.getNumber() == number).findFirst();
        });
        when(items.save(any(ObjectiveItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperEndpoint.AnswerTableResult result = endpoint.fillAnswers(paperId,
                new PaperEndpoint.AnswerTableRequest("1 A\n2. challenging"), false, teacher(teacherId));

        assertThat(result.updated()).isEqualTo(2);
        assertThat(persisted.get(0).getAnswerKey()).isEqualTo("A");
        assertThat(persisted.get(1).getAnswerKey()).isEqualTo("challenging");
    }
}
