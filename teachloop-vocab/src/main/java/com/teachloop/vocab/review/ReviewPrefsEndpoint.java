package com.teachloop.vocab.review;

import com.teachloop.shared.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 背单词偏好（US-88 / T552，spec AC-88.7 / AC-88.8）。
 *
 * <p>为什么单独开一个端点，而不是塞进 {@code /api/vocab/prefs}：那一个的契约是
 * 「整份 JSON 原样存取，服务端不解释形状」（列配置）；而这里的字段**会被队列读取**，
 * 一旦前端单方面改 key，队列会静默回落到默认值、用户设的 50 不生效且没有任何报错。
 * 两种所有权不能共用一个契约。
 *
 * <p>所有非法值都纠正（clamp / 白名单 / 归一化），不返回 500——偏好写坏了不该让复习页打不开。
 */
@RestController
@RequestMapping("/api/vocab/review-prefs")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ReviewPrefsEndpoint {

    private final JdbcTemplate jdbc;
    private final ReviewPrefsStore store;

    public ReviewPrefsEndpoint(JdbcTemplate jdbc, ReviewPrefsStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @GetMapping
    public ReviewPrefs get(@RequestAttribute("currentUser") CurrentUser me) {
        return store.load(me.id());
    }

    @PutMapping
    @Transactional
    public ReviewPrefs save(@RequestBody(required = false) JsonNode body,
                            @RequestAttribute("currentUser") CurrentUser me) {
        ReviewPrefs prefs = ReviewPrefs.parse(body, store.defaults().dailyNewCards(), store.maxCards());
        jdbc.update("""
                insert into vocab_user_prefs (user_id, review, updated_at)
                values (?, cast(? as jsonb), now())
                on conflict (user_id) do update set review = excluded.review, updated_at = excluded.updated_at
                """, me.id(), prefs.toJson());
        return prefs;
    }

}
