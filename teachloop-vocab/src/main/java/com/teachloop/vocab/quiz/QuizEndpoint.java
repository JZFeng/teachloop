package com.teachloop.vocab.quiz;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import com.teachloop.vocab.content.ContentStore;
import com.teachloop.vocab.content.ContentView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 复习小测（US-87 / T548，spec §26 AC-87.1…AC-87.11）。
 *
 * <p>小测是**练习**，不是考试：不写 {@code vocab_progress}、不写 {@code vocab_review_logs}，
 * 因此不改变 SM-2 排期，也不计入「今日已复习」（AC-87.7）。判分只在服务端做，
 * 答案键永远不下发（未交卷时 {@code answer}/{@code correct} 都是 null）。
 *
 * <p>开关（AC-87.10）：Controller **始终注册**，请求时判 {@code teachloop.vocab.quiz.enabled}。
 * 这里刻意不用 {@code @ConditionalOnProperty}——那种开关在启动期决定 bean 是否存在，
 * 将来要按用户分组做 AB 就做不到了（用户要求的实验能力）。关闭时端点返回 404，
 * 与 TTS 关闭时的做法一致（AC-57.8）。
 */
@RestController
@RequestMapping("/api/vocab/quizzes")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class QuizEndpoint {

    private static final Logger log = LoggerFactory.getLogger(QuizEndpoint.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 少于 3 个词不出卷：一道题的小测没有意义，也容易被当成页面出错。 */
    private static final int MIN_WORDS = 3;

    private final QuizStore store;
    private final ContentStore contentStore;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean quizEnabled;
    private final int sizeToday;
    private final int sizeSession;

    public QuizEndpoint(QuizStore store, ContentStore contentStore, Clock clock,
                        @Value("${teachloop.vocab.quiz.enabled:false}") boolean quizEnabled,
                        @Value("${teachloop.vocab.quiz.size.today:10}") int sizeToday,
                        @Value("${teachloop.vocab.quiz.size.session:20}") int sizeSession) {
        this.store = store;
        this.contentStore = contentStore;
        this.clock = clock;
        this.quizEnabled = quizEnabled;
        this.sizeToday = Math.max(MIN_WORDS, sizeToday);
        this.sizeSession = Math.max(MIN_WORDS, sizeSession);
    }

    // ---------------------------------------------------------------- DTO

    public record CreateRequest(String kind, UUID sessionId) {
    }

    public record AnswerRequest(UUID itemId, String given) {
    }

    public record SubmitRequest(List<AnswerRequest> answers) {
    }

    public record OptionView(String key, String text) {
    }

    /** 一条题。<b>未交卷时</b> {@code given/correct/answer} 必须为 null（AC-87.4：答案不下发）。 */
    public record ItemView(UUID id, int position, String type, String stem, List<OptionView> options,
                           String given, Boolean correct, String answer) {
    }

    public record QuizView(UUID id, String kind, String status, int itemCount, Integer correctCount,
                           Instant createdAt, Instant submittedAt, UUID sourceSessionId,
                           List<ItemView> items) {
    }

    // ---------------------------------------------------------------- 接口

    @PostMapping
    @Transactional
    public QuizView create(@RequestBody(required = false) CreateRequest request,
                           @RequestAttribute("currentUser") CurrentUser me) {
        requireEnabled();
        String kind = request == null || request.kind() == null ? "TODAY"
                : request.kind().trim().toUpperCase(Locale.ROOT);
        if (!kind.equals("TODAY") && !kind.equals("SESSION")) {
            throw ApiException.badRequest("kind must be TODAY or SESSION");
        }
        List<UUID> wordIds = kind.equals("SESSION")
                ? sessionWords(me.id(), request == null ? null : request.sessionId())
                : todayWords(me.id());
        return buildQuiz(me.id(), kind, wordIds,
                kind.equals("SESSION") ? sizeSession : sizeToday,
                kind.equals("SESSION") ? (request == null ? null : request.sessionId()) : null, null);
    }

    @PostMapping("/{id}/retry")
    @Transactional
    public QuizView retry(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireEnabled();
        QuizStore.QuizRow quiz = requireQuiz(id, me.id());
        List<UUID> wrong = store.wrongWordIds(quiz.id());
        if (wrong.isEmpty()) {
            throw ApiException.badRequest("no wrong answers to retry");
        }
        return buildQuiz(me.id(), "RETRY", wrong, Math.min(sizeSession, wrong.size()), null, quiz.id());
    }

    @GetMapping("/{id}")
    public QuizView get(@PathVariable UUID id, @RequestAttribute("currentUser") CurrentUser me) {
        requireEnabled();
        return view(requireQuiz(id, me.id()));
    }

    @PostMapping("/{id}/submit")
    @Transactional
    public QuizView submit(@PathVariable UUID id, @RequestBody(required = false) SubmitRequest request,
                           @RequestAttribute("currentUser") CurrentUser me) {
        requireEnabled();
        QuizStore.QuizRow quiz = requireQuiz(id, me.id());
        if ("SUBMITTED".equals(quiz.status())) {
            return view(quiz);   // 幂等：重复交卷不重算、不改写（AC-87.4）
        }
        Map<UUID, String> given = new LinkedHashMap<>();
        if (request != null && request.answers() != null) {
            for (AnswerRequest answer : request.answers()) {
                if (answer != null && answer.itemId() != null) {
                    given.put(answer.itemId(), answer.given() == null ? "" : answer.given().strip());
                }
            }
        }

        List<QuizStore.ItemRow> items = store.items(quiz.id());
        int correctCount = 0;
        Instant now = clock.instant();
        for (QuizStore.ItemRow item : items) {
            String text = given.get(item.id());
            boolean correct = !text.isBlank() && judge(item, text);
            if (correct) {
                correctCount++;
            }
            store.updateItemAnswer(item.id(), text.isBlank() ? null : text, correct, now);
        }
        store.markSubmitted(quiz.id(), correctCount, now);
        log.debug("quiz submitted user={} quiz={} correct={}/{}", me.id(), quiz.id(), correctCount, items.size());
        return view(requireQuiz(id, me.id()));
    }

    // ---------------------------------------------------------------- 判分

    private boolean judge(QuizStore.ItemRow item, String given) {
        if ("SINGLE_CHOICE".equals(item.questionType())) {
            return given.trim().equalsIgnoreCase(item.answerKey());
        }
        String normalized = normalize(given);
        for (String accepted : acceptedForms(item.wordId(), item.answerKey())) {
            if (normalize(accepted).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 填空题接受「同词族任意词形」（AC-87.4）：句子里是 {@code struggled}，学生填 {@code struggle}
     * 也算对——屈折不是词汇问题。词形集合来自词条原形 + 词族 + 句中词形。
     */
    private LinkedHashSet<String> acceptedForms(UUID wordId, String answerKey) {
        LinkedHashSet<String> forms = new LinkedHashSet<>();
        forms.add(answerKey);
        store.word(wordId).ifPresent(row -> {
            forms.add(row.form());
            JsonNode view = ContentView.resolve(contentStore, row.contentId(),
                    row.language(), row.form());
            JsonNode family = view == null ? null : view.path("family");
            if (family != null && family.isArray()) {
                for (JsonNode member : family) {
                    String word = member.asString("");
                    if (!word.isBlank()) {
                        forms.add(word);
                    }
                }
            }
        });
        return forms;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ---------------------------------------------------------------- 出卷

    private QuizView buildQuiz(UUID userId, String kind, List<UUID> wordIds, int size,
                               UUID sourceSessionId, UUID sourceQuizId) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(wordIds);
        if (unique.size() < MIN_WORDS) {
            throw ApiException.badRequest("not enough words for a quiz");
        }
        List<QuizBuilder.SourceWord> sources = new ArrayList<>(unique.size());
        for (UUID wordId : unique) {
            store.ownedWord(wordId, userId).ifPresent(row -> sources.add(
                    new QuizBuilder.SourceWord(wordId, row.form(),
                            ContentView.resolve(contentStore, row.contentId(),
                                    row.language(), row.form()))));
        }

        long seed = Math.abs(new java.util.Random().nextLong());
        List<QuizBuilder.Item> built = QuizBuilder.build(sources, Math.min(size, sources.size()), seed);
        if (built.size() < MIN_WORDS) {
            throw ApiException.badRequest("not enough example sentences for a quiz");
        }

        UUID quizId = UUID.randomUUID();
        Instant now = clock.instant();
        store.insertQuiz(quizId, userId, kind, sourceSessionId, sourceQuizId, built.size(), seed, now);

        for (int i = 0; i < built.size(); i++) {
            QuizBuilder.Item item = built.get(i);
            store.insertItem(UUID.randomUUID(), quizId, userId, item.wordId(), i,
                    item.type().name(), item.stem(), mapper.writeValueAsString(item.options()),
                    item.answerKey());
        }
        return view(requireQuiz(quizId, userId));
    }

    // ---------------------------------------------------------------- 取词

    /** 今天复习过的词（不含小测自己：小测不写 review_logs）。 */
    private List<UUID> todayWords(UUID userId) {
        Instant dayStart = LocalDate.ofInstant(clock.instant(), ZONE).atStartOfDay(ZONE).toInstant();
        return store.todayReviewedWordIds(userId, dayStart);
    }

    /** 本次会话「看过的」词 = scope.wordIds[0..cursor)。 */
    private List<UUID> sessionWords(UUID userId, UUID sessionId) {
        if (sessionId == null) {
            throw ApiException.badRequest("sessionId is required for SESSION quiz");
        }
        QuizStore.SessionRow row = store.session(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("session not found"));

        JsonNode scope = mapper.readTree(row.scope());
        int cursor = Math.min(row.cursor(), scope.path("wordIds").size());
        List<UUID> out = new ArrayList<>(cursor);
        for (int i = 0; i < cursor; i++) {
            JsonNode node = scope.path("wordIds").get(i);
            if (node != null && !node.isNull()) {
                out.add(UUID.fromString(node.asString()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 读

    private QuizStore.QuizRow requireQuiz(UUID id, UUID userId) {
        QuizStore.QuizRow row = store.findQuiz(id)
                .orElseThrow(() -> ApiException.notFound("quiz not found"));
        // 他人卷子一律 404：不泄露存在性（AC-87.11）
        if (!row.userId().equals(userId)) {
            throw ApiException.notFound("quiz not found");
        }
        return row;
    }

    private QuizView view(QuizStore.QuizRow quiz) {
        store.touch(quiz.id(), clock.instant());
        boolean submitted = "SUBMITTED".equals(quiz.status());
        List<ItemView> items = new ArrayList<>();
        for (QuizStore.ItemRow item : store.items(quiz.id())) {
            List<OptionView> options = new ArrayList<>();
            JsonNode parsed = mapper.readTree(item.options() == null ? "[]" : item.options());
            if (parsed.isArray()) {
                for (JsonNode option : parsed) {
                    options.add(new OptionView(option.path("key").asString(""),
                            option.path("text").asString("")));
                }
            }
            String answer = null;
            if (submitted) {
                answer = "SINGLE_CHOICE".equals(item.questionType())
                        ? optionText(options, item.answerKey())
                        : item.answerKey();
            }
            items.add(new ItemView(item.id(), item.position(), item.questionType(), item.stem(),
                    options, submitted ? item.given() : null, submitted ? item.correct() : null, answer));
        }
        return new QuizView(quiz.id(), quiz.kind(), quiz.status(), quiz.itemCount(),
                submitted ? quiz.correctCount() : null, quiz.createdAt(), quiz.submittedAt(),
                quiz.sourceSessionId(), items);
    }

    private static String optionText(List<OptionView> options, String key) {
        for (OptionView option : options) {
            if (option.key().equalsIgnoreCase(key == null ? "" : key)) {
                return option.text();
            }
        }
        return key;
    }

    private void requireEnabled() {
        if (!quizEnabled) {
            throw ApiException.notFound("quiz is not enabled");
        }
    }
}
