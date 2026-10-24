package com.teachloop.vocab.srs;

/**
 * 内部四档评分（spec §5）。界面只有两档：认识 → {@link #GOOD}，不认识 → {@link #AGAIN}
 * （映射固定，见 AC-53.8）；困难/容易留给将来的界面或策略实验，不暴露按钮。
 */
public enum Rating {
    AGAIN,
    HARD,
    GOOD,
    EASY
}
