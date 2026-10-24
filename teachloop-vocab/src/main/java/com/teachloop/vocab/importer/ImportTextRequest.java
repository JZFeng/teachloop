package com.teachloop.vocab.importer;

/** 导入/粘贴/预览的请求体（label 只对提交生效）。 */
public record ImportTextRequest(String text, String language, String label) {
}
