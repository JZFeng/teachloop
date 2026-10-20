package com.teachloop.classroom.port;

import com.teachloop.classroom.domain.ClassMemberRepository;
import com.teachloop.shared.port.Ports;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClassMembershipImpl implements Ports.ClassMembership {

    private final ClassMemberRepository members;

    public ClassMembershipImpl(ClassMemberRepository members) {
        this.members = members;
    }

    @Override
    public List<UUID> classIdsOf(UUID studentId) {
        return members.findByStudentId(studentId).stream().map(m -> m.getClassId()).toList();
    }

    @Override
    public List<UUID> membersOf(UUID classId) {
        return members.findByClassId(classId).stream().map(m -> m.getStudentId()).toList();
    }
}
