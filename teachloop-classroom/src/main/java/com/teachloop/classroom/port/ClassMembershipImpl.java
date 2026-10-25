package com.teachloop.classroom.port;

import com.teachloop.classroom.domain.ClassEntity;
import com.teachloop.classroom.domain.ClassMemberRepository;
import com.teachloop.classroom.domain.ClassRepository;
import com.teachloop.shared.port.Ports;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ClassMembershipImpl implements Ports.ClassMembership {

    private final ClassMemberRepository members;
    private final ClassRepository classes;

    public ClassMembershipImpl(ClassMemberRepository members, ClassRepository classes) {
        this.members = members;
        this.classes = classes;
    }

    @Override
    public List<UUID> classIdsOf(UUID studentId) {
        return members.findByStudentId(studentId).stream().map(m -> m.getClassId()).toList();
    }

    @Override
    public List<UUID> membersOf(UUID classId) {
        return members.findByClassId(classId).stream().map(m -> m.getStudentId()).toList();
    }

    @Override
    public Set<UUID> classIdsOwnedBy(Collection<UUID> classIds, UUID teacherId) {
        if (classIds == null || classIds.isEmpty() || teacherId == null) {
            return Set.of();
        }
        return Set.copyOf(classes.findOwnedIds(classIds, teacherId));
    }

    @Override
    public Map<UUID, String> classNames(Collection<UUID> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return Map.of();
        }
        return classes.findAllById(classIds).stream()
                .collect(Collectors.toMap(ClassEntity::getId, ClassEntity::getName));
    }
}
