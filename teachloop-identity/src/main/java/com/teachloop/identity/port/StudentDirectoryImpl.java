package com.teachloop.identity.port;

import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.shared.port.Ports;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 只读显示名查询：教师端提交表需要姓名，但不允许跨模块查 users 表。 */
@Component
public class StudentDirectoryImpl implements Ports.StudentDirectory {

    private final UserRepository users;

    public StudentDirectoryImpl(UserRepository users) {
        this.users = users;
    }

    @Override
    public Map<UUID, String> displayNames(Collection<UUID> userIds) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (User u : users.findAllById(userIds)) {
            out.put(u.getId(), u.getDisplayName());
        }
        return out;
    }
}
