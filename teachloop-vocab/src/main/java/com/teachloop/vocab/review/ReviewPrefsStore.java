package com.teachloop.vocab.review;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 偏好的唯一读取点（US-88 / T552）：设置页与复习队列共用，避免两处各自解析出现口径差。
 *
 * <p>缺行、JSON 写坏、字段非法一律回落到默认（20 张 / 混合 / 全显示），不抛异常——
 * 偏好坏了不能把复习页一起弄挂。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewPrefsStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int defaultCards;
    private final int maxCards;

    public ReviewPrefsStore(JdbcTemplate jdbc,
                            @Value("${teachloop.vocab.review.daily-new-cards:20}") int defaultCards,
                            @Value("${teachloop.vocab.review.daily-new-cards-max:200}") int maxCards) {
        this.jdbc = jdbc;
        this.defaultCards = defaultCards;
        this.maxCards = Math.max(ReviewPrefs.MIN_CARDS, maxCards);
    }

    public ReviewPrefs load(UUID userId) {
        List<String> rows = jdbc.query("""
                select cast(review as text) from vocab_user_prefs where user_id = ?
                """, (rs, n) -> rs.getString(1), userId);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return defaults();
        }
        return ReviewPrefs.parse(mapper.readTree(rows.get(0)), defaultCards, maxCards);
    }

    public ReviewPrefs defaults() {
        return ReviewPrefs.defaults(defaultCards);
    }

    public int maxCards() {
        return maxCards;
    }
}
