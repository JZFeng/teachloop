package com.teachloop.identity.api;

import com.teachloop.identity.auth.channel.ChannelRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 009 T529：公开的通道发现端点。未启用或未配好的通道**不下发**。 */
@RestController
public class AuthChannelEndpoint {

    private final ChannelRegistry channels;

    public AuthChannelEndpoint(ChannelRegistry channels) {
        this.channels = channels;
    }

    @GetMapping("/api/auth/channels")
    public List<Map<String, String>> channels() {
        return channels.available().stream()
                .map(c -> Map.of("channel", c.channel(), "label", c.label(), "targetHint", c.targetHint()))
                .toList();
    }
}
