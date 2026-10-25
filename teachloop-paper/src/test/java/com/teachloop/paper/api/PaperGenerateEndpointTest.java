package com.teachloop.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teachloop.paper.domain.ObjectiveItem;
import com.teachloop.paper.domain.ObjectiveItemRepository;
import com.teachloop.paper.domain.Paper;
import com.teachloop.paper.domain.PaperContentSnapshot;
import com.teachloop.paper.domain.PaperContentSnapshotRepository;
import com.teachloop.paper.domain.PaperRepository;
import com.teachloop.paper.domain.PaperSection;
import com.teachloop.paper.domain.PaperSectionRepository;
import com.teachloop.paper.domain.QuestionType;
import com.teachloop.paper.domain.SectionCriteria;
import com.teachloop.paper.domain.SectionCriteriaRepository;
import com.teachloop.paper.importing.DocumentTextExtractor;
import com.teachloop.paper.importing.PaperTextParser;
import com.teachloop.shared.audit.AuditPort;
import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.security.CurrentUser;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/**
 * T459 / AC-4.24：「生成考题」的替换重建（`replace=true`）。
 *
 * <p>不启 Spring 容器：沿用 {@link PaperImportEndpointTest} 的构造重载 + Mockito 仓储，
 * 断言的都是「这次请求对库做了什么」：快照、删除、重建、原文、409。</p>
 */
class PaperGenerateEndpointTest {

    private final PaperRepository papers = mock(PaperRepository.class);
    private final PaperSectionRepository sectionRepo = mock(PaperSectionRepository.class);
    private final ObjectiveItemRepository itemRepo = mock(ObjectiveItemRepository.class);
    private final SectionCriteriaRepository criteriaRepo = mock(SectionCriteriaRepository.class);
    private final PaperContentSnapshotRepository snapshotRepo = mock(PaperContentSnapshotRepository.class);
    private final AuditPort audit = mock(AuditPort.class);

    private PaperEndpoint endpoint;
    private UUID paperId;
    private UUID teacherId;
    private Paper paper;

    /** 三个「库表」的内存替身：save 进去、deleteAll 清空，端点读到的就是它写过的。 */
    private final List<PaperSection> sectionRows = new ArrayList<>();
    private final List<ObjectiveItem> itemRows = new ArrayList<>();
    private final List<SectionCriteria> criteriaRows = new ArrayList<>();
    private final List<PaperContentSnapshot> snapshotRows = new ArrayList<>();

    private PaperSection oldSection;

    @BeforeEach
    void setUp() {
        endpoint = new PaperEndpoint(papers, sectionRepo, itemRepo, criteriaRepo, audit,
                Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC),
                id -> 0, (id, keys) -> 0, snapshotRepo, null);
        paperId = UUID.randomUUID();
        teacherId = UUID.randomUUID();
        paper = new Paper(paperId, "七年级英语月考", 90, teacherId, Instant.now());
        oldSection = new PaperSection(UUID.randomUUID(), paperId, "一、单项选择", 1, new BigDecimal("2"));
        sectionRows.add(oldSection);
        itemRows.add(new ObjectiveItem(UUID.randomUUID(), paperId, oldSection.getId(), 1, 1,
                QuestionType.SINGLE_CHOICE, "旧题一", "A. 旧\nB. 旧", "A", new BigDecimal("2")));
        itemRows.add(new ObjectiveItem(UUID.randomUUID(), paperId, oldSection.getId(), 2, 2,
                QuestionType.SINGLE_CHOICE, "旧题二", "A. 旧\nB. 旧", "B", new BigDecimal("2")));
        criteriaRows.add(new SectionCriteria(UUID.randomUUID(), paperId, oldSection.getId(), "内容要点",
                new BigDecimal("5"), null, 1));
        paper.setTotalPoints(new BigDecimal("9"));

        when(papers.findById(paperId)).thenReturn(Optional.of(paper));
        when(papers.save(any(Paper.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(sectionRepo.findByPaperIdOrderByPosition(paperId)).thenAnswer(inv -> List.copyOf(sectionRows));
        when(sectionRepo.findById(any())).thenAnswer(inv -> sectionRows.stream()
                .filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        when(sectionRepo.save(any(PaperSection.class))).thenAnswer(inv -> {
            PaperSection saved = inv.getArgument(0);
            sectionRows.removeIf(existing -> existing.getId().equals(saved.getId()));
            sectionRows.add(saved);
            return saved;
        });

        when(itemRepo.findByPaperIdOrderByNumber(paperId)).thenAnswer(inv -> List.copyOf(itemRows));
        when(itemRepo.findByPaperSectionIdOrderByPosition(any())).thenAnswer(inv -> itemRows.stream()
                .filter(item -> item.getPaperSectionId().equals(inv.getArgument(0))).toList());
        when(itemRepo.findByPaperIdAndNumber(any(), any(Integer.class))).thenAnswer(inv -> {
            int number = inv.getArgument(1);
            return itemRows.stream().filter(item -> item.getNumber() == number).findFirst();
        });
        when(itemRepo.save(any(ObjectiveItem.class))).thenAnswer(inv -> {
            ObjectiveItem saved = inv.getArgument(0);
            itemRows.removeIf(existing -> existing.getId().equals(saved.getId()));
            itemRows.add(saved);
            return saved;
        });

        when(criteriaRepo.findByPaperIdOrderByPosition(paperId)).thenAnswer(inv -> List.copyOf(criteriaRows));
        when(criteriaRepo.findByPaperSectionIdOrderByPosition(any())).thenAnswer(inv -> criteriaRows.stream()
                .filter(c -> c.getPaperSectionId().equals(inv.getArgument(0))).toList());

        when(snapshotRepo.save(any(PaperContentSnapshot.class))).thenAnswer(inv -> {
            PaperContentSnapshot saved = inv.getArgument(0);
            snapshotRows.add(saved);
            return saved;
        });
        when(snapshotRepo.findByPaperIdOrderByCreatedAtDesc(paperId)).thenAnswer(inv -> {
            List<PaperContentSnapshot> newestFirst = new ArrayList<>(snapshotRows);
            Collections.reverse(newestFirst);
            return newestFirst;
        });
    }

    /** deleteAll 的替身：真正把这些行从内存「表」里删掉，端点后续读到的就是删过的。 */
    @BeforeEach
    void stubDeletes() {
        org.mockito.Mockito.doAnswer(inv -> {
            sectionRows.clear();
            return null;
        }).when(sectionRepo).deleteAll(any());
        org.mockito.Mockito.doAnswer(inv -> {
            itemRows.clear();
            return null;
        }).when(itemRepo).deleteAll(any());
        org.mockito.Mockito.doAnswer(inv -> {
            criteriaRows.clear();
            return null;
        }).when(criteriaRepo).deleteAll(any());
        org.mockito.Mockito.doAnswer(inv -> {
            List<UUID> removed = new ArrayList<>();
            for (PaperContentSnapshot snapshot : (Iterable<PaperContentSnapshot>) inv.getArgument(0)) {
                removed.add(snapshot.getId());
            }
            snapshotRows.removeIf(snapshot -> removed.contains(snapshot.getId()));
            return null;
        }).when(snapshotRepo).deleteAll(any());
    }

    private CurrentUser teacher() {
        return new CurrentUser(teacherId, "TEACHER", "王老师");
    }

    private static String singlePartText() {
        return """
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
    }

    private static byte[] realExam() throws IOException {
        try (InputStream in = PaperGenerateEndpointTest.class
                .getResourceAsStream("/exam/english-grade10-2026-with-answers.md")) {
            assertThat(in).as("找不到真实卷 fixture").isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void replaceCreatesDefaultSectionWhenDocumentHasNoParts() {
        String text = singlePartText();

        ResponseEntity<?> response = endpoint.importText(paperId,
                new PaperEndpoint.ImportTextRequest(text, null), false, true, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        PaperEndpoint.ImportResult body = (PaperEndpoint.ImportResult) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imported()).isEqualTo(2);

        // 老部分被删掉，单部分文档拿「综合」当容器（否则 resolveSection 会抛「请先创建部分」）
        assertThat(sectionRows).hasSize(1);
        PaperSection rebuilt = sectionRows.get(0);
        assertThat(rebuilt.getTitle()).isEqualTo("综合");
        assertThat(rebuilt.getPosition()).isEqualTo(1);
        assertThat(rebuilt.getPointsPerItem()).as("没有分值提示时按 1 分").isEqualByComparingTo("1");

        assertThat(itemRows).hasSize(2);
        assertThat(itemRows).allSatisfy(item -> {
            assertThat(item.getPaperSectionId()).isEqualTo(rebuilt.getId());
            assertThat(item.getPoints()).isEqualByComparingTo("1");
        });
        assertThat(body.sections()).extracting(PaperEndpoint.ImportSection::title).containsExactly("综合");
        assertThat(paper.getTotalPoints()).isEqualByComparingTo("2");
    }

    @Test
    void replaceWritesSnapshotBeforeDeletingAndStoresSubmittedText() {
        String text = singlePartText();

        endpoint.importText(paperId, new PaperEndpoint.ImportTextRequest(text, null), false, true, teacher());

        // AC-4.24⑥：重建前把将被删除的内容序列化成一条快照
        assertThat(snapshotRows).hasSize(1);
        PaperContentSnapshot snapshot = snapshotRows.get(0);
        assertThat(snapshot.getPaperId()).isEqualTo(paperId);
        assertThat(snapshot.getReason()).isEqualTo("import_text");
        assertThat(snapshot.getPayloadJson())
                .as("快照存的是重建前的旧内容")
                .contains("旧题一")
                .contains("一、单项选择")
                .contains("内容要点")
                .contains("\"items\"")
                .contains("\"criteria\"");

        // 旧的部分 / 题项 / 评分项都清了（评分项列保留但新流程不再写，AC-4.24③）
        assertThat(criteriaRows).isEmpty();
        verify(criteriaRepo).deleteAll(any());
        verify(itemRepo).deleteAll(any());
        verify(sectionRepo).deleteAll(any());

        // AC-4.24④：原文落到 papers.content_markdown，编辑模式靠它回填
        assertThat(paper.getContentMarkdown()).isEqualTo(text);
    }

    @Test
    void replaceRebuildsMultiPartStructureFromRealExam() throws IOException {
        String text = new String(realExam(), StandardCharsets.UTF_8);

        ResponseEntity<?> response = endpoint.importText(paperId,
                new PaperEndpoint.ImportTextRequest(text, null), true, true, teacher());

        PaperEndpoint.ImportResult body = (PaperEndpoint.ImportResult) response.getBody();
        assertThat(body).isNotNull();
        // 真实卷：4 个部分、77 题（写作 76/77）、合计 150 分（RealExamWithAnswersTest 的同一口径）
        assertThat(body.imported()).isEqualTo(77);
        assertThat(sectionRows).extracting(PaperSection::getTitle)
                .containsExactly("第一部分 听力", "第二部分 阅读理解", "第三部分 英语知识运用", "第四部分 写作");
        assertThat(itemRows).hasSize(77);
        assertThat(paper.getTotalPoints()).isEqualByComparingTo("150");

        // 头部提示：命题人 / 审题人 / 总分（AC-4.27④）
        assertThat(body.metaHints()).isNotNull();
        assertThat(body.metaHints().authorName()).isEqualTo("李璐");
        assertThat(body.metaHints().reviewerName()).isEqualTo("刘嘉伦");
        assertThat(body.metaHints().targetTotalPoints()).isEqualByComparingTo("150");

        // 写作题按原文预填 rubric / 范文（AC-4.24⑦ 的存储面）
        assertThat(itemRows).filteredOn(item -> item.getQuestionType() == QuestionType.ESSAY)
                .extracting(ObjectiveItem::getNumber).containsExactly(76, 77);
    }

    @Test
    void replaceKeepsOnlyThreeSnapshotsPerPaper() {
        for (int i = 0; i < 3; i++) {
            snapshotRows.add(new PaperContentSnapshot(UUID.randomUUID(), paperId,
                    Instant.parse("2026-10-2" + (i + 1) + "T10:00:00Z"), "generate", "{}"));
        }
        UUID oldest = snapshotRows.get(0).getId();

        endpoint.importText(paperId, new PaperEndpoint.ImportTextRequest(singlePartText(), null),
                false, true, teacher());

        assertThat(snapshotRows).as("每卷只留最近 3 条").hasSize(3);
        assertThat(snapshotRows).extracting(PaperContentSnapshot::getId).doesNotContain(oldest);
    }

    @Test
    void rejectsGenerateOnPublishedPaperEvenWithConfirm() {
        paper.publish();

        assertThatThrownBy(() -> endpoint.importFile(paperId, fileOf(singlePartText()), null, true, true, teacher()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo("error.paper.publishedLocked");
                });
        assertThatThrownBy(() -> endpoint.importText(paperId,
                new PaperEndpoint.ImportTextRequest(singlePartText(), null), true, true, teacher()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(itemRows).hasSize(2);
        assertThat(snapshotRows).isEmpty();
        assertThat(sectionRows).extracting(PaperSection::getTitle).containsExactly("一、单项选择");
    }

    @Test
    void withoutReplaceOldRowsStayAndNothingIsSnapshotted() {
        endpoint.importText(paperId, new PaperEndpoint.ImportTextRequest(singlePartText(), null),
                false, false, teacher());

        // 旧行为：题号已存在就跳过、不覆盖（decision 021「未采纳」里明确不改这条）
        assertThat(itemRows).hasSize(2);
        assertThat(itemRows.get(0).getStem()).isEqualTo("旧题一");
        assertThat(snapshotRows).isEmpty();
        verify(snapshotRepo, never()).save(any());
        verify(criteriaRepo, never()).deleteAll(any());
        // 但原文照样落库：老卷也能被回填成「可再次生成」的卷子
        assertThat(paper.getContentMarkdown()).contains("中国的首都");
    }

    @Test
    void fileImportAlsoStoresExtractedTextAndHints() {
        // 一张还没生成过内容的草稿卷：不 replace 也不会撞上「题号已存在就跳过」
        itemRows.clear();
        String text = """
                命题人：李璐　审题人：刘嘉伦　总分：150 分

                1. 中国的首都是哪里？
                A. 北京
                B. 上海
                答案
                1. A
                """;

        ResponseEntity<?> response = endpoint.importFile(paperId, fileOf(text), null, false, false, teacher());

        PaperEndpoint.ImportResult body = (PaperEndpoint.ImportResult) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imported()).isEqualTo(1);
        assertThat(body.contentMarkdown()).contains("中国的首都");
        assertThat(body.metaHints().authorName()).isEqualTo("李璐");
        // 文件入口存的是「实际进解析器的文本」：回填编辑器再生成得到同样的题项
        assertThat(paper.getContentMarkdown()).isEqualTo(body.contentMarkdown());
        assertThat(snapshotRows).isEmpty();
    }

    @Test
    void returnsEmptyHintsWhenHeaderHasNoLabels() {
        ResponseEntity<?> response = endpoint.importText(paperId,
                new PaperEndpoint.ImportTextRequest(singlePartText(), null), false, false, teacher());

        PaperEndpoint.ImportResult body = (PaperEndpoint.ImportResult) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.metaHints().authorName()).isNull();
        assertThat(body.metaHints().reviewerName()).isNull();
        assertThat(body.metaHints().targetTotalPoints()).isNull();
    }

    private static MockMultipartFile fileOf(String text) {
        return new MockMultipartFile("file", "paper.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8));
    }
}
