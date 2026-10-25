package com.teachloop.vocab.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动时确认「prompt 契约版本」与「delta 写入用的 prompt 版本」一致（T439）。
 *
 * <p>不一致不会立刻报错，但新生成的内容会以配置里的版本落 delta、与包/旧内容版本对不上，
 * 表现为「学生反复触发 AI 生成」。这类漂移只能靠启动日志发现，所以在这里显式打出来。
 */
@Component
public class PromptVersionConsistencyCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionConsistencyCheck.class);

    private final ContentPromptContract contract;
    private final String configuredVersion;

    public PromptVersionConsistencyCheck(
            ContentPromptContract contract,
            @Value("${teachloop.vocab.content.prompt-version:}") String configuredVersion) {
        this.contract = contract;
        this.configuredVersion = configuredVersion;
    }

    @Override
    public void afterPropertiesSet() {
        String fromContract = contract.promptVersion();
        if (configuredVersion == null || configuredVersion.isBlank()) {
            log.info("词内容 prompt 契约就绪 version={} sha={}（delta 版本未显式配置，用存储默认值）",
                    fromContract, contract.promptSha256());
            return;
        }
        if (!configuredVersion.equals(fromContract)) {
            log.warn("词内容 prompt 版本不一致：teachloop.vocab.content.prompt-version={} 而契约={}；"
                            + "AI 内容会按 {} 落 delta，与包/旧内容对不上，学生会反复触发生成。请同步这两处。",
                    configuredVersion, fromContract, configuredVersion);
            return;
        }
        log.info("词内容 prompt 契约就绪 version={} sha={}，与 delta 版本一致",
                fromContract, contract.promptSha256());
    }
}
