package com.teachloop.vocab.importer;

import com.teachloop.shared.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 粘贴加词入口（T393 / plan §7）：与导入提交共用 {@link WordImportService}，只改变输入方式，
 * 入库结果与导入完全一致（FR-109 / AC-48.2）。
 */
@RestController
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class WordsPasteEndpoint {

    private final WordImportService importService;

    public WordsPasteEndpoint(WordImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/api/vocab/words/paste")
    public WordImportService.CommitResult paste(@RequestBody(required = false) ImportTextRequest request,
                                                @RequestAttribute("currentUser") CurrentUser me) {
        String text = request == null ? "" : request.text();
        String language = request == null || request.language() == null || request.language().isBlank()
                ? "en" : request.language();
        return importService.commit(me.id(), language, text, request == null ? null : request.label());
    }
}
