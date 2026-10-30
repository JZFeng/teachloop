package com.teachloop.identity.profile;

import com.teachloop.identity.domain.UserRepository;
import com.teachloop.shared.port.Ports;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 011 / T557 / FR-227：{@code users.timezone} 的只读实现。
 *
 * <p>跨模块（vocab、app）要知道「这个学生自己设的时区」只能走
 * {@link Ports.UserZonePort}，不查 identity 的表、也不反向依赖 identity 的实体。
 * 空值表示未设置，由调用方的回落链继续（{@code ZoneResolution}：头 &gt; 资料 &gt; 产品默认）。
 */
@Component
public class UserZoneAdapter implements Ports.UserZonePort {

    private final UserRepository users;

    public UserZoneAdapter(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<Ports.UserZone> of(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return users.findById(userId)
                .map(user -> user.getTimezone())
                .filter(value -> value != null && !value.isBlank())
                .map(Ports.UserZone::new);
    }
}
