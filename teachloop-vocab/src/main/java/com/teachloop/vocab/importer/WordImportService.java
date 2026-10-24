package com.teachloop.vocab.importer;

import com.teachloop.shared.error.ApiException;
import com.teachloop.vocab.generation.VocabularyGenerationService;
import com.teachloop.vocab.lexicon.WordFamily;
import com.teachloop.vocab.lexicon.WordFamilyIndex;
import com.teachloop.vocab.word.VocabWordRepository;
import com.teachloop.vocab.word.WordNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 加词 / 导入的唯一入库管线（T393 / FR-109 / US-48）。
 *
 * <p>「粘贴加词」与「导入文件」都调这里：解析 → 规范化去重（大小写 / 全角 / 变音符）→
 * 已存在只追加来源（软删除的恢复原词条与原进度）→ 返回「新建 / 重复 / 失败」与行号级错误。
 *
 * <p>预览与提交共用同一份扫描逻辑；预览只查库不写库。
 */
@Service
public class WordImportService {

    private static final int MAX_LABEL_LENGTH = 80;

    public record LineError(int line, String raw, String reason) {
    }

    public record Counts(int total, int created, int duplicated, int failed) {
    }

    public record PreviewResult(Counts counts, List<LineError> errors) {
    }

    public record CommitResult(Counts counts, UUID batchId, List<LineError> errors) {
    }

    private final DictionaryTextParser parser;
    private final VocabWordRepository words;
    private final WordFamilyIndex lexicon;
    private final VocabularyGenerationService generation;

    public WordImportService(DictionaryTextParser parser, VocabWordRepository words,
                             WordFamilyIndex lexicon, VocabularyGenerationService generation) {
        this.parser = parser;
        this.words = words;
        this.lexicon = lexicon;
        this.generation = generation;
    }

    public PreviewResult preview(UUID userId, String language, String text) {
        ParseResult parsed = parser.parse(text, language);
        List<LineError> errors = new ArrayList<>();
        Map<String, String> unique = new LinkedHashMap<>();
        int total = 0;
        int failed = 0;
        int inFileDuplicates = 0;
        for (ParseResult.ParsedToken token : parsed.tokens()) {
            total++;
            if (!WordNormalizer.valid(token.raw())) {
                failed++;
                errors.add(new LineError(token.lineNumber(), token.raw(), "正面为空或超过 64 字符"));
                continue;
            }
            String normalized = WordNormalizer.normalized(token.raw());
            if (unique.putIfAbsent(normalized, token.raw()) != null) {
                inFileDuplicates++;
            }
        }
        Set<String> existing = words.existingNormalized(userId, language, unique.keySet());
        int created = unique.size() - existing.size();
        int duplicated = inFileDuplicates + existing.size();
        return new PreviewResult(new Counts(total, created, duplicated, failed), errors);
    }

    @Transactional
    public CommitResult commit(UUID userId, String language, String text, String label) {
        String cleanLabel = label == null || label.isBlank() ? null : label.strip();
        if (cleanLabel != null && cleanLabel.length() > MAX_LABEL_LENGTH) {
            throw ApiException.badRequest("import label is longer than " + MAX_LABEL_LENGTH);
        }
        ParseResult parsed = parser.parse(text, language);
        UUID batchId = UUID.randomUUID();
        List<LineError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        int created = 0;
        int duplicated = 0;
        int failed = 0;
        List<UUID> createdIds = new ArrayList<>();
        for (ParseResult.ParsedToken token : parsed.tokens()) {
            total++;
            if (!WordNormalizer.valid(token.raw())) {
                failed++;
                errors.add(new LineError(token.lineNumber(), token.raw(), "正面为空或超过 64 字符"));
                continue;
            }
            String normalized = WordNormalizer.normalized(token.raw());
            if (!seen.add(normalized)) {
                duplicated++;
                continue;
            }
            Optional<VocabWordRepository.ExistingWord> existing = words.find(userId, language, normalized);
            if (existing.isPresent()) {
                if (existing.get().deleted()) {
                    words.restore(existing.get().id());
                }
                words.addImportSource(existing.get().id(), batchId, cleanLabel);
                duplicated++;
                continue;
            }
            String display = WordNormalizer.display(token.raw());
            Optional<WordFamily> family = lexicon.lookup(display);
            UUID wordId = words.insert(userId, language, display, normalized,
                    family.map(WordFamily::familyId).orElse(null),
                    family.map(WordFamily::band).orElse(null));
            words.addImportSource(wordId, batchId, cleanLabel);
            createdIds.add(wordId);
            created++;
        }
        generation.enqueueNewWords(createdIds);
        return new CommitResult(new Counts(total, created, duplicated, failed), batchId, errors);
    }
}
