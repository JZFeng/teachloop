package com.teachloop.identity.auth.channel;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 009 T521：把「通道配置」与「通道实现」合成前端可见的可用集合。
 *
 * <p>判定只有一条：**开关打开 且 配置齐全**。两者缺一，通道就不存在——
 * 界面上不会出现一个点进去才报错的入口（AC-74.1 / AC-75.6）。
 */
@Component
public class ChannelRegistry {

    public record Available(String channel, String label, String targetHint) {
    }

    private final IdentityChannelRepository channels;
    private final Map<String, MessageChannel> implementations = new LinkedHashMap<>();

    public ChannelRegistry(IdentityChannelRepository channels, List<MessageChannel> channelBeans) {
        this.channels = channels;
        channelBeans.stream()
                .sorted(Comparator.comparing(MessageChannel::channel))
                .forEach(c -> implementations.put(c.channel(), c));
    }

    /** 当前对用户可见的通道；顺序固定 email → sms → wechat。 */
    public List<Available> available() {
        return channels.findAll().stream()
                .filter(IdentityChannelRepository.Row::enabled)
                .sorted(Comparator.comparingInt(r -> order(r.channel())))
                .map(r -> implementations.get(r.channel()))
                .filter(c -> c != null && c.configured())
                .map(c -> new Available(c.channel(), c.label(), c.targetHint()))
                .toList();
    }

    public Optional<MessageChannel> usable(String channel) {
        return available().stream()
                .filter(a -> a.channel().equals(channel))
                .findFirst()
                .flatMap(a -> Optional.ofNullable(implementations.get(a.channel())));
    }

    private static int order(String channel) {
        return switch (channel) {
            case "email" -> 0;
            case "sms" -> 1;
            case "wechat" -> 2;
            default -> 9;
        };
    }
}
