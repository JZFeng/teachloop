package com.teachloop.identity.ai;

import com.teachloop.shared.port.Ports;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 启动时让阅卷模块读取管理员保存的 AI 配置。 */
@Component
public class AiSettingsLookupImpl implements Ports.AiSettingsLookup {

    private final AiSettingsService service;

    public AiSettingsLookupImpl(AiSettingsService service) {
        this.service = service;
    }

    @Override
    public Optional<Ports.AiSettings> current() {
        return service.lookup();
    }
}
