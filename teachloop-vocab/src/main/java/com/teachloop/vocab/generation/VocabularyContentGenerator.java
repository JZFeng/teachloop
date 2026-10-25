package com.teachloop.vocab.generation;

/**
 * 词条内容生成的可替换边界（plan §4 / FR-113）。
 *
 * <p>实现只接收「词形 + 学习语言」，返回内容 JSON 字符串；worker 会把它写进全局
 * {@code ContentStore}（SQLite delta），用户行只存 {@code content_id}。生成器不接触用户身份、
 * 不写库、不读词库，方便用 stub 做确定性测试。
 */
public interface VocabularyContentGenerator {

    /**
     * 生成释义/例句/构词/词族/音标。
     *
     * @throws ContentGenerationException AI 未配置、超时、输出不合法
     */
    String generate(String form, String language);
}
