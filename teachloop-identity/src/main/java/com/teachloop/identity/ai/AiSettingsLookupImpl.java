package com.teachloop.identity.ai;

import com.teachloop.shared.port.Ports;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 启动时让阅卷模块读取管理员保存的 AI 配置。 */
@Component
public class AiSettingsLookupImpl implements Ports.AiSettingsLookup {

    private final AiSettingsService service;
    private final AiReadinessService readiness;

    public AiSettingsLookupImpl(AiSettingsService service, AiReadinessService readiness) {
        this.service = service;
        this.readiness = readiness;
    }

    @Override
    public Optional<Ports.AiSettings> current() {
        return service.lookup();
    }

    @Override
    public Optional<Ports.AiSettings> resolve(java.util.UUID teacherId) {
        return service.resolve(teacherId);
    }

    /** 完整的部署级判断（provider / 环境变量 / stub 替身）；其它模块经端口只看这个结果。 */
    @Override
    public Ports.AiReadiness readiness() {
        return readiness.readiness();
    }
}
