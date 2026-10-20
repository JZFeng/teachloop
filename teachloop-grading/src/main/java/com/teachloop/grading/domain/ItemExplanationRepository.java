package com.teachloop.grading.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ItemExplanationRepository extends JpaRepository<ItemExplanation, UUID> {

    Optional<ItemExplanation> findByItemIdAndLanguageAndPromptVersion(
            UUID itemId, String language, String promptVersion);

    boolean existsByItemIdAndLanguageAndPromptVersion(UUID itemId, String language, String promptVersion);

    /**
     * 幂等写入：唯一键冲突时什么都不做，返回 0。
     *
     * <p>刻意不用 {@code save()} + 捕获唯一键异常：Postgres 里失败语句会让当前事务进入
     * aborted 状态，捕获后继续写会把事务打成 rollback-only。{@code ON CONFLICT DO NOTHING}
     * 让并发重复生成只有一个赢家，输家返回 0 并计为 skippedExisting。
     *
     * <p>每个方法自己开一个短事务（不加在调用方上），单题失败不影响其他题。
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into item_explanations (id, item_id, language, prompt_version, text, state, created_at)
            values (:id, :itemId, :language, :promptVersion, :text, 'done', :createdAt)
            on conflict (item_id, language, prompt_version) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("itemId") UUID itemId,
                       @Param("language") String language,
                       @Param("promptVersion") String promptVersion,
                       @Param("text") String text,
                       @Param("createdAt") Instant createdAt);
}
