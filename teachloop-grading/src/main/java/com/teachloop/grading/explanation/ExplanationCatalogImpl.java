package com.teachloop.grading.explanation;

import com.teachloop.grading.domain.ItemExplanation;
import com.teachloop.grading.domain.ItemExplanationRepository;
import com.teachloop.shared.port.Ports;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * T087 / T115：学生反馈读解析的唯一入口（查表复用，零额外模型调用）。
 *
 * <p>只认当前 {@link ExplanationEngine#PROMPT_VERSION} 与 {@code done} 状态；
 * 没有解析就返回空，由 sitting 显示「解析未生成」，绝不编造内容（AC-18.8）。
 */
@Component
public class ExplanationCatalogImpl implements Ports.ExplanationCatalog {

    private final ItemExplanationRepository explanations;

    public ExplanationCatalogImpl(ItemExplanationRepository explanations) {
        this.explanations = explanations;
    }

    @Override
    public Optional<String> explanationOf(UUID itemId, String language) {
        if (itemId == null) {
            return Optional.empty();
        }
        return explanations
                .findByItemIdAndLanguageAndPromptVersion(itemId,
                        ExplanationLanguage.normalize(language), ExplanationEngine.PROMPT_VERSION)
                .filter(e -> ItemExplanation.STATE_DONE.equals(e.getState()))
                .map(ItemExplanation::getText)
                .filter(text -> text != null && !text.isBlank());
    }
}
