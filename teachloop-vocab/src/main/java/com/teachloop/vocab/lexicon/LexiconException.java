package com.teachloop.vocab.lexicon;

/** 词表索引文件缺失、损坏或自相矛盾。调用方按「词族区块降级隐藏」处理，不让启动失败。 */
public class LexiconException extends RuntimeException {

    public LexiconException(String message) {
        super(message);
    }
}
