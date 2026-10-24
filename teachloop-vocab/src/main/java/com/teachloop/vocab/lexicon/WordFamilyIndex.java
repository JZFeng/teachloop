package com.teachloop.vocab.lexicon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内存词表索引（T388）：精确查 {@code normalized_form}，未命中再查去变音符索引。
 *
 * <p>规模：28,276 族 / 80,579 形；查询 O(1)，不查数据库。装载由
 * {@link LexiconBootstrap} 在启动时完成；装载期间到达的查询最多等
 * {@code teachloop.vocab.lexicon.load-timeout-ms}，之后按「词表不可用」降级返回空。
 * 词表缺失或损坏不影响应用启动（spec §8：词族区块降级隐藏）。
 */
@Component
public class WordFamilyIndex {

    private static final Logger log = LoggerFactory.getLogger(WordFamilyIndex.class);

    private final int maxMembers;
    private final long loadTimeoutMs;
    private final CountDownLatch loadFinished = new CountDownLatch(1);

    private volatile boolean loadStarted;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public WordFamilyIndex(
            @Value("${teachloop.vocab.word-family.max-members:8}") int maxMembers,
            @Value("${teachloop.vocab.lexicon.load-timeout-ms:5000}") long loadTimeoutMs) {
        this.maxMembers = Math.max(0, maxMembers);
        this.loadTimeoutMs = Math.max(0, loadTimeoutMs);
    }

    /** 查一个词形：先精确，再按去变音符形态兜底。未命中返回空，由调用方走异步内容生成。 */
    public Optional<WordFamily> lookup(String form) {
        if (form == null || form.isBlank()) {
            return Optional.empty();
        }
        awaitReady();
        Snapshot current = snapshot;
        if (!current.available()) {
            return Optional.empty();
        }
        String key = FormKey.normalized(form);
        FormRow matched = current.forms().get(key);
        boolean foldedHit = false;
        if (matched == null) {
            matched = current.folded().get(FormKey.folded(key));
            foldedHit = matched != null;
        }
        if (matched == null) {
            return Optional.empty();
        }
        FamilyRow family = current.families().get(matched.familyId());
        if (family == null) {
            return Optional.empty();
        }
        FormRow hit = matched;
        List<WordFamily.Member> members = current.members()
                .getOrDefault(hit.familyId(), List.of()).stream()
                .filter(member -> !member.form().equals(hit.normalizedForm()))
                .limit(maxMembers)
                .map(member -> new WordFamily.Member(member.form(), member.freq(), member.head()))
                .toList();
        return Optional.of(new WordFamily(family.head(), family.band(), family.familyFreq(),
                hit.normalizedForm(), hit.formFreq(), foldedHit, members));
    }

    public boolean available() {
        return snapshot.available();
    }

    public int familyCount() {
        return snapshot.families().size();
    }

    public int formCount() {
        return snapshot.forms().size();
    }

    /** 全部词形（T392 拼写校验只对未命中词做受限扫描时用）。 */
    public java.util.List<FormRow> allForms() {
        return snapshot.formList();
    }

    /** 族所属频段；族不存在返回 -1。 */
    public int bandOf(long familyId) {
        FamilyRow family = snapshot.families().get(familyId);
        return family == null ? -1 : family.band();
    }

    void beginLoading() {
        loadStarted = true;
    }

    void install(LexiconData data) {
        snapshot = Snapshot.build(data, maxMembers);
        loadFinished.countDown();
        log.debug("词表内存索引就绪：{} 族 / {} 形", familyCount(), formCount());
    }

    /** 装载失败或没有产物：保留上一次可用的快照（重载失败不该把线上词族清空）。 */
    void markUnavailable() {
        loadFinished.countDown();
    }

    private void awaitReady() {
        if (!loadStarted || loadFinished.getCount() == 0) {
            return;
        }
        try {
            if (!loadFinished.await(loadTimeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("词表索引装载超过 {}ms，本次查询按不可用处理", loadTimeoutMs);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record MemberRow(String form, long freq, boolean head) {
    }

    private record Snapshot(
            boolean available,
            Map<Long, FamilyRow> families,
            Map<String, FormRow> forms,
            Map<String, FormRow> folded,
            Map<Long, List<MemberRow>> members,
            List<FormRow> formList) {

        static final Snapshot EMPTY = new Snapshot(false, Map.of(), Map.of(), Map.of(), Map.of(), List.of());

        static Snapshot build(LexiconData data, int maxMembers) {
            Map<Long, FamilyRow> families = new HashMap<>(data.familyCount() * 2);
            for (FamilyRow family : data.families()) {
                families.put(family.id(), family);
            }
            Map<String, FormRow> forms = new HashMap<>(data.formCount() * 2);
            Map<Long, List<FormRow>> grouped = new HashMap<>();
            for (FormRow form : data.forms()) {
                forms.put(form.normalizedForm(), form);
                grouped.computeIfAbsent(form.familyId(), key -> new ArrayList<>()).add(form);
            }
            Map<Long, List<MemberRow>> members = new HashMap<>(grouped.size() * 2);
            for (Map.Entry<Long, List<FormRow>> entry : grouped.entrySet()) {
                List<FormRow> sorted = new ArrayList<>(entry.getValue());
                sorted.sort(Comparator.comparingLong(FormRow::formFreq).reversed()
                        .thenComparing(FormRow::normalizedForm));
                members.put(entry.getKey(), sorted.stream()
                        .map(row -> new MemberRow(row.normalizedForm(), row.formFreq(), row.head()))
                        .toList());
            }
            Map<String, FormRow> folded = new HashMap<>();
            for (FormRow form : data.forms()) {
                if (!form.foldedForm().equals(form.normalizedForm())) {
                    folded.merge(form.foldedForm(), form,
                            (left, right) -> left.formFreq() >= right.formFreq() ? left : right);
                }
            }
            return new Snapshot(true, Map.copyOf(families), Map.copyOf(forms),
                    Map.copyOf(folded), Map.copyOf(members), List.copyOf(data.forms()));
        }
    }
}
