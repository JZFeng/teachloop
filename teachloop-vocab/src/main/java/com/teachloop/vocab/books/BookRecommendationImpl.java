package com.teachloop.vocab.books;

import com.teachloop.shared.port.Ports;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 词书推荐端口的 v1 实现（T573 / AC-104.3 / FR-250）。
 *
 * <p>规则只有一条，且**只写在 {@link BookRepository#listForRecommendation} 的 SQL 里**：
 * 「已发布 → position 升序 → 加入人数降序」，不足 `limit` 时有几本给几本。控制器只调
 * {@link Ports.BookRecommendation}，不出现任何排序或 SQL——以后换「按水平/按班级推荐」时
 * 换掉这个类即可，接口形状（`List<BookRef>`）不变。
 *
 * <p>覆盖率实时算（FR-240）：`BookRef.readyCount` 在内容索引不可用时回 0（端口契约是 int，
 * 不能表达 null；界面上「—」由详情页的 nullable 字段负责）。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class BookRecommendationImpl implements Ports.BookRecommendation {

    /** 推荐位一次最多取多少本：防止调用方传一个大 limit 把整张表拉出来。 */
    private static final int MAX_LIMIT = 20;

    private final BookRepository books;
    private final BookCoverageService coverage;

    public BookRecommendationImpl(BookRepository books, BookCoverageService coverage) {
        this.books = books;
        this.coverage = coverage;
    }

    @Override
    public List<Ports.BookRef> recommended(UUID userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<BookRepository.BookRow> rows = books.listForRecommendation(Math.min(limit, MAX_LIMIT));
        Map<UUID, BookCoverageService.Coverage> coverages = coverage.coverageFor(rows);
        List<Ports.BookRef> refs = new ArrayList<>(rows.size());
        for (BookRepository.BookRow row : rows) {
            BookCoverageService.Coverage bookCoverage =
                    coverages.getOrDefault(row.id(), new BookCoverageService.Coverage(0, 0));
            int ready = bookCoverage.ready() == null ? 0 : bookCoverage.ready();
            refs.add(new Ports.BookRef(row.id(), row.name(), bookCoverage.total(), ready));
        }
        return refs;
    }
}
