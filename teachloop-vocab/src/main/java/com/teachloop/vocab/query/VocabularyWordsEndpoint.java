package com.teachloop.vocab.query;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.security.CurrentUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 词库列表接口（T396 / US-46 / US-59）。
 *
 * <p>全部筛选都走查询参数（重复参数表示多选），因此前端可以把 URL 原样还原成筛选条件；
 * 分页与排序由服务端做（排序跨分页生效）。
 *
 * <p>T450：新增可重复参数 {@code list=<词单 id>}（多份词单取并集），与来源/标签/状态/收藏/
 * 暂停/到期取交集；词单不属于本人时 404（AC-69.9）。
 */
@RestController
@RequestMapping("/api/vocab/words")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class VocabularyWordsEndpoint {

    private static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 50, 100);

    private final VocabularyQueryRepository repository;

    public VocabularyWordsEndpoint(VocabularyQueryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public VocabularyQueryRepository.WordPage list(
            @RequestParam(required = false) String language,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) List<String> state,
            @RequestParam(required = false) List<String> source,
            @RequestParam(required = false) List<String> tag,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) Boolean paused,
            @RequestParam(required = false) Boolean due,
            @RequestParam(required = false) UUID deckId,
            @RequestParam(required = false) List<UUID> list,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, name = "dir") String direction,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestAttribute("currentUser") CurrentUser me) {
        if (!PAGE_SIZES.contains(size)) {
            throw ApiException.badRequest("size must be one of 10, 20, 50, 100");
        }
        if (page < 1) {
            throw ApiException.badRequest("page must be >= 1");
        }
        return repository.query(new VocabularyQueryRepository.WordQuery(
                me.id(), language, search, state, source, tag, favorite, paused, due, deckId,
                list, sort, direction, page, size));
    }
}
