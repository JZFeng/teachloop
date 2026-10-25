package com.teachloop.vocab.content;

/**
 * 全局共享词内容的一条记录（decisions/017 / AC-48.14）。
 *
 * <p>`json` 是原样保存的内容 JSON：只读内容包里是生成脚本产出的 v2 结构，
 * delta 里可能是 AI 现场生成的结构。ContentStore 不解释字段，读取侧通过
 * {@link ContentView} 适配成前端当前使用的形状，等 T432 切到 v2 后可以去掉这一层。
 */
public record StoredContent(long id, String language, String normalizedForm, String promptVersion,
                            String json, String source) {
}
