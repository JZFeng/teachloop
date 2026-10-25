package com.teachloop.vocab.importer;

/** 导入/粘贴/预览的请求体（label 与 listName 只对提交生效；listName 见 AC-69.5）。 */
public record ImportTextRequest(String text, String language, String label, String listName) {
}
