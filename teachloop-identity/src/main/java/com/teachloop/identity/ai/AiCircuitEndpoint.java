package com.teachloop.identity.ai;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.port.Ports;
import com.teachloop.shared.security.CurrentUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-31：批改页的熔断状态。
 *
 * <p>教师与管理员可读；学生 403（学生不看 AI 运维状态）。没有配置 provider 时返回 CLOSED。
 */
@RestController
@RequestMapping("/api/ai-circuit")
public class AiCircuitEndpoint {

    public record View(String state, String providerKey, String providerDisplayName,
                       int consecutiveFailures, Instant openedAt, Instant nextProbeAt,
                       String lastErrorKind) {
    }

    private final AiSettingsService settings;
    private final AiProviderRepository providers;
    private final Ports.AiCircuit circuit;

    public AiCircuitEndpoint(AiSettingsService settings, AiProviderRepository providers,
                             Ports.AiCircuit circuit) {
        this.settings = settings;
        this.providers = providers;
        this.circuit = circuit;
    }

    @GetMapping
    public View view(@RequestAttribute("currentUser") CurrentUser me) {
        if (me == null) {
            throw ApiException.unauthorized("error.UNAUTHORIZED");
        }
        if (me.is("STUDENT")) {
            throw ApiException.forbidden("error.FORBIDDEN");
        }
        UUID activeId = settings.activeProviderId();
        AiProviderEntity active = activeId == null ? null : providers.findById(activeId).orElse(null);
        if (active == null) {
            return new View("CLOSED", null, null, 0, null, null, null);
        }
        Ports.CircuitSnapshot snapshot = circuit.snapshot(active.getProviderKey());
        return new View(snapshot.state(), active.getProviderKey(), active.getDisplayName(),
                snapshot.consecutiveFailures(), snapshot.openedAt(), snapshot.nextProbeAt(),
                snapshot.lastErrorKind());
    }
}
