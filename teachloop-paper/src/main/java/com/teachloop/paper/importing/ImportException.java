package com.teachloop.paper.importing;

/**
 * 导入过程中的可解释失败（T186）。
 *
 * <p>消息直接给人看，含行号（能定位时）；API 层把它映射成 400 + detail。</p>
 */
public class ImportException extends RuntimeException {

    public ImportException(String detail) {
        super(detail);
    }

    public ImportException(String detail, Throwable cause) {
        super(detail, cause);
    }

    /** 带源文档行号的失败，例如「第 12 行：题号 3 重复」。 */
    public static ImportException atLine(int line, String reason) {
        return new ImportException("第 " + line + " 行：" + reason);
    }
}
