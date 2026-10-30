package com.teachloop.vocab.review;

import com.teachloop.shared.port.Ports;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 011 / T558 / AC-94.1：今日页的复习切片（{@link Ports.ReviewSlicePort} 的 vocab 实现）。
 *
 * <p>口径与 {@link ReviewQueueEndpoint} 完全一致，不另写一份：
 * <ul>
 *   <li>{@code due}：到期卡数扣掉「超出每日新卡上限、今天不会出」的那部分（= 队列的 {@code remaining}）；</li>
 *   <li>{@code reviewedToday}：当日 0 点（学生时区）起的评分条数；</li>
 *   <li>{@code newToday}：今天会进队的新卡数（受上限约束）；</li>
 *   <li>{@code newLimit}：偏好里的每日新卡上限（今日页展示「上限 Z」用）。</li>
 * </ul>
 *
 * <p>语言与队列默认值一致取 {@code en}：今日页点「开始复习」进的就是同一队列，
 * 两处数字必须能对上，否则学生会看到「24 张待复习」点进去只有 12 张。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewSliceAdapter implements Ports.ReviewSlicePort {

    /** 与 {@code ReviewQueueEndpoint} 的缺省 language 参数保持一致。 */
    private static final String DEFAULT_LANGUAGE = "en";

    private final ReviewQueueStore store;
    private final ReviewPrefsStore prefs;
    private final VocabZones zones;
    private final Clock clock;

    public ReviewSliceAdapter(ReviewQueueStore store, ReviewPrefsStore prefs, VocabZones zones,
                              Clock clock) {
        this.store = store;
        this.prefs = prefs;
        this.zones = zones;
        this.clock = clock;
    }

    @Override
    public Ports.ReviewSlice today(UUID userId, String iana) {
        ReviewPrefs pref = prefs.load(userId);
        int newCap = Math.max(0, Math.min(pref.dailyNewCards(), prefs.maxCards()));

        ReviewQueueStore.DueCounts counts = store.countDue(userId, DEFAULT_LANGUAGE, clock.instant());
        int newToday = (int) Math.min(counts.newDue(), newCap);
        // 与队列一致：超上限的新卡今天不出，所以它们不算「待复习」。
        long due = Math.max(0, counts.due() - Math.max(0, counts.newDue() - newCap));

        long reviewed = store.countReviewedToday(userId, zones.dayStartOf(iana));
        return new Ports.ReviewSlice((int) due, (int) reviewed, newToday, pref.dailyNewCards());
    }
}
