package com.teachloop.vocab.generation;

/**
 * 词条内容生成的可替换边界（plan §4 / FR-113）。
 *
 * <p>实现只接收「词形 + 学习语言」，返回写进 {@code vocab_words.content} 的 JSON 字符串；
 * 不接触用户身份、不写库、不读词库，方便用 stub 做确定性测试。
 */
public interface VocabularyContentGenerator {

    /**
     * 生成释义/例句/构词/词族/音标。
     *
     * @throws ContentGenerationException AI 未配置、超时、输出不合法
     */
    String generate(String form, String language);
}
