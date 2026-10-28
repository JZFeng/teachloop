package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.identity.auth.channel.ChannelRegistry;
import com.teachloop.identity.auth.channel.IdentityChannelRepository;
import com.teachloop.identity.auth.channel.MessageChannel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 009 T521：只有「开关打开 + 配置齐全」的通道才对用户可见。 */
class ChannelRegistryTest {

    private static final class FakeRepo extends IdentityChannelRepository {
        private final List<Row> rows;

        FakeRepo(List<Row> rows) {
            super(null);
            this.rows = rows;
        }

        @Override
        public List<Row> findAll() {
            return rows;
        }

        @Override
        public Optional<Row> find(String channel) {
            return rows.stream().filter(r -> r.channel().equals(channel)).findFirst();
        }
    }

    private static final class FakeChannel implements MessageChannel {
        private final String name;
        private final boolean configured;

        FakeChannel(String name, boolean configured) {
            this.name = name;
            this.configured = configured;
        }

        public String channel() { return name; }
        public boolean configured() { return configured; }
        public String label() { return name; }
        public String targetHint() { return name; }
        public void send(String target, String subject, String body) { }
    }

    private static IdentityChannelRepository.Row row(String ch, boolean enabled) {
        return new IdentityChannelRepository.Row(ch, enabled, "driver", "f@x", "host:465", "cipher", null, null);
    }

    @Test
    @DisplayName("关掉的通道不出现；开了但没配好的也不出现")
    void onlyEnabledAndConfigured() {
        var repo = new FakeRepo(List.of(
                row("email", true),
                row("sms", false),
                row("wechat", true)));
        var registry = new ChannelRegistry(repo, List.of(
                new FakeChannel("email", true),
                new FakeChannel("sms", true),
                new FakeChannel("wechat", false)));

        assertThat(registry.available()).extracting(ChannelRegistry.Available::channel)
                .containsExactly("email");
        assertThat(registry.usable("sms")).isEmpty();
        assertThat(registry.usable("email")).isPresent();
    }

    @Test
    @DisplayName("顺序固定 email → sms → wechat，与数据库返回顺序无关")
    void fixedOrder() {
        var repo = new FakeRepo(List.of(row("wechat", true), row("sms", true), row("email", true)));
        var registry = new ChannelRegistry(repo, List.of(
                new FakeChannel("wechat", true), new FakeChannel("sms", true), new FakeChannel("email", true)));
        assertThat(registry.available()).extracting(ChannelRegistry.Available::channel)
                .containsExactly("email", "sms", "wechat");
    }
}
