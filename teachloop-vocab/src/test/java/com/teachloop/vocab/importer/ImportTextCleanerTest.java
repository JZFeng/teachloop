package com.teachloop.vocab.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T391：每类清洗规则一条用例，并验证记录可追溯（行号/规则/前后值）。 */
class ImportTextCleanerTest {

    private static List<ParseResult.Cleanup> cleanups() {
        return new ArrayList<>();
    }

    @Test
    @DisplayName("全角逗号/分号/顿号转半角，并逐条留痕")
    void fullWidthSeparators() {
        ImportTextCleaner.CleanedLine cleaned = ImportTextCleaner.cleanLine("apple，brave；clever、dream", 3);
        assertThat(cleaned.tokens()).containsExactly("apple", "brave", "clever", "dream");
        assertThat(cleaned.cleanups()).hasSize(3);
        assertThat(cleaned.cleanups()).allSatisfy(cleanup -> {
            assertThat(cleanup.lineNumber()).isEqualTo(3);
            assertThat(cleanup.rule()).isEqualTo("全角标点转半角");
        });
        assertThat(cleaned.cleanups()).extracting(ParseResult.Cleanup::after)
                .containsExactly(",", ";", ",");
    }

    @Test
    @DisplayName("去引号：成对引号被去掉，短语内的空格保留")
    void quotes() {
        ImportTextCleaner.CleanedLine cleaned = ImportTextCleaner.cleanLine("\"take off\", “give up”", 5);
        assertThat(cleaned.tokens()).containsExactly("take off", "give up");
        assertThat(cleaned.cleanups()).extracting(ParseResult.Cleanup::rule)
                .containsExactly("去引号", "去引号");
        assertThat(cleaned.cleanups().get(0).before()).isEqualTo("\"take off\"");
        assertThat(cleaned.cleanups().get(0).after()).isEqualTo("take off");
    }

    @Test
    @DisplayName("去音标：/ˈæpl/ 与 [ˈæpl] 整个 token 丢弃，apple/ˈæpl/ 保留词形")
    void phonetics() {
        ImportTextCleaner.CleanedLine cleaned = ImportTextCleaner.cleanLine("apple /ˈæpl/ [ˈæpl]", 2);
        assertThat(cleaned.tokens()).containsExactly("apple");
        assertThat(cleaned.cleanups()).extracting(ParseResult.Cleanup::rule)
                .containsExactly("去音标", "去音标");
        assertThat(cleaned.cleanups()).extracting(ParseResult.Cleanup::after).containsExactly("", "");

        ImportTextCleaner.CleanedLine inline = ImportTextCleaner.cleanLine("apple/ˈæpl/", 2);
        assertThat(inline.tokens()).containsExactly("apple");
    }

    @Test
    @DisplayName("去词性标注：n. / vt. / (adj.) 整 token 丢弃，普通单词不动")
    void posTags() {
        ImportTextCleaner.CleanedLine cleaned = ImportTextCleaner.cleanLine("apple n. brave vt. clever (adj.)", 7);
        assertThat(cleaned.tokens()).containsExactly("apple", "brave", "clever");
        assertThat(cleaned.cleanups()).extracting(ParseResult.Cleanup::rule)
                .containsExactly("去词性标注", "去词性标注", "去词性标注");
        // 不带点的 art 是正常单词，不能当词性标注吞掉
        assertThat(ImportTextCleaner.cleanLine("art", 1).tokens()).containsExactly("art");
    }
}
