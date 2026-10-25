package com.teachloop.vocab.content;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 存量内容回填（T431 / decisions/017 P3）：V063 把 `vocab_words.content` 搬进
 * `vocab_words_content_legacy` 后删除原列，这里把 legacy 表里的内容逐行写进全局 delta，
 * 并把 {@code content_id} 回填到用户行；全部成功才 drop legacy 表。
 *
 * <p>为什么在启动时做：迁移本身不能调用 Java 的 ContentStore（Flyway 只跑 SQL），
 * 而内容必须进入 delta 才能被读路径按 id 解析。回填是幂等的——没有 legacy 表就直接跳过。
 *
 * <p>失败策略：任何一行写入或回填失败都抛出 {@link IllegalStateException}，让应用启动失败
 * 并保留 legacy 表（下次启动重试）。宁可起不来，也不要带着「内容丢失但界面显示未准备」的半成品上线。
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class ContentBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContentBackfillRunner.class);

    private static final String LEGACY_TABLE = "vocab_words_content_legacy";
    private static final String LEGACY_SOURCE = "BACKFILL";

    private final JdbcTemplate jdbc;
    private final ContentStore contentStore;
    private final boolean enabled;

    public ContentBackfillRunner(
            JdbcTemplate jdbc,
            ContentStore contentStore,
            @Value("${teachloop.vocab.content.backfill-on-startup:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.contentStore = contentStore;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("存量内容回填已关闭（teachloop.vocab.content.backfill-on-startup=false）");
            return;
        }
        if (!legacyTableExists()) {
            log.debug("没有存量内容待回填（{} 不存在）", LEGACY_TABLE);
            return;
        }

        List<LegacyRow> rows = jdbc.query("""
                select id, language, normalized_form, cast(content as text)
                  from vocab_words_content_legacy
                 order by id
                """, (rs, rowNumber) -> new LegacyRow(
                rs.getObject(1, java.util.UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4)));

        int succeeded = 0;
        int failed = 0;
        String firstError = null;
        for (LegacyRow row : rows) {
            try {
                long contentId = contentStore.put(row.language(), row.normalizedForm(),
                        row.contentJson(), LEGACY_SOURCE);
                jdbc.update("""
                        update vocab_words
                           set content_id = ?, updated_at = now()
                         where id = ? and content_id is null
                        """, contentId, row.id());
                succeeded++;
            } catch (RuntimeException ex) {
                failed++;
                if (firstError == null) {
                    firstError = row.id() + ": " + ex.getMessage();
                }
                log.warn("存量内容回填失败 id={} form={}：{}",
                        row.id(), row.normalizedForm(), ex.toString());
            }
        }

        log.info("存量内容回填完成 total={} succeeded={} failed={}", rows.size(), succeeded, failed);
        if (failed > 0) {
            throw new IllegalStateException("存量内容回填失败 " + failed + " 行（legacy 表已保留，"
                    + "下次启动重试）：" + firstError);
        }
        jdbc.execute("drop table if exists " + LEGACY_TABLE);
        log.info("存量内容回填全部成功，已删除 {}", LEGACY_TABLE);
    }

    private boolean legacyTableExists() {
        String name = jdbc.queryForObject(
                "select to_regclass(?)", String.class, "public." + LEGACY_TABLE);
        return name != null;
    }

    private record LegacyRow(java.util.UUID id, String language, String normalizedForm,
                             String contentJson) {
    }
}
