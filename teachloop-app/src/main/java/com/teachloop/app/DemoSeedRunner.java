package com.teachloop.app;

import com.teachloop.classroom.domain.ClassEntity;
import com.teachloop.classroom.domain.ClassMember;
import com.teachloop.classroom.domain.ClassMemberRepository;
import com.teachloop.classroom.domain.ClassRepository;
import com.teachloop.identity.domain.User;
import com.teachloop.identity.domain.UserRepository;
import com.teachloop.identity.domain.UserRole;
import com.teachloop.paper.domain.ObjectiveItem;
import com.teachloop.paper.domain.ObjectiveItemRepository;
import com.teachloop.paper.domain.Paper;
import com.teachloop.paper.domain.PaperRepository;
import com.teachloop.paper.domain.PaperSection;
import com.teachloop.paper.domain.PaperSectionRepository;
import com.teachloop.paper.domain.QuestionType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T021：可重复执行的演示数据。1 个班 + 30 个学生 + 1 份带标准答案的试卷。
 * 固定 UUID / 邀请码 / 邮箱，重复执行只补缺，不重复插入；两次结果一致。
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "teachloop.seed.demo", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final String PASSWORD = "teachloop123";
    private static final UUID CLASS_ID = UUID.nameUUIDFromBytes("teachloop-demo-class".getBytes(StandardCharsets.UTF_8));
    private static final UUID PAPER_ID = UUID.nameUUIDFromBytes("teachloop-demo-paper".getBytes(StandardCharsets.UTF_8));
    private static final UUID SECTION_ID = UUID.nameUUIDFromBytes("teachloop-demo-section".getBytes(StandardCharsets.UTF_8));

    private final UserRepository users;
    private final ClassRepository classes;
    private final ClassMemberRepository members;
    private final PaperRepository papers;
    private final PaperSectionRepository sections;
    private final ObjectiveItemRepository items;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public DemoSeedRunner(UserRepository users, ClassRepository classes, ClassMemberRepository members,
                          PaperRepository papers, PaperSectionRepository sections, ObjectiveItemRepository items,
                          PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.classes = classes;
        this.members = members;
        this.papers = papers;
        this.sections = sections;
        this.items = items;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
        if (args.containsOption("seed-exit")) {
            System.exit(0);
        }
    }

    @Transactional
    public void seed() {
        User teacher = user("teacher@teachloop.local", "示例老师", UserRole.TEACHER);
        for (int i = 1; i <= 30; i++) {
            String email = String.format("student%02d@teachloop.local", i);
            user(email, String.format("学生%02d", i), UserRole.STUDENT);
        }

        ClassEntity clazz = classes.findById(CLASS_ID).orElseGet(() -> classes.save(
                new ClassEntity(CLASS_ID, "演示班", teacher.getId(), "DEMO2026", Instant.now(clock))));
        for (int i = 1; i <= 30; i++) {
            User student = users.findByEmail(String.format("student%02d@teachloop.local", i)).orElseThrow();
            if (!members.existsByClassIdAndStudentId(clazz.getId(), student.getId())) {
                members.save(new ClassMember(UUID.randomUUID(), clazz.getId(), student.getId(), Instant.now(clock)));
            }
        }

        if (papers.findById(PAPER_ID).isEmpty()) {
            Paper paper = papers.save(new Paper(PAPER_ID, "演示试卷", 90, teacher.getId(), Instant.now(clock)));
            PaperSection section = sections.save(new PaperSection(SECTION_ID, paper.getId(), "综合", 1, BigDecimal.TEN));
            items.save(new ObjectiveItem(UUID.randomUUID(), paper.getId(), section.getId(), 1, 1,
                    QuestionType.SINGLE_CHOICE, "Which sentence is correct?",
                    "A. He go.\nB. He goes.\nC. He going.", "B", BigDecimal.TEN));
            items.save(new ObjectiveItem(UUID.randomUUID(), paper.getId(), section.getId(), 2, 2,
                    QuestionType.FILL_BLANK, "The school life may be hard, but you are not ______.",
                    null, "alone", new BigDecimal("5")));
            items.save(new ObjectiveItem(UUID.randomUUID(), paper.getId(), section.getId(), 3, 3,
                    QuestionType.ESSAY, "Write a letter of advice to a new student (about 80 words).",
                    null, null, new BigDecimal("15")));
            paper.publish();
            papers.save(paper);
        }
        log.info("demo seed ready: class={} students=30 paper={}", clazz.getId(), PAPER_ID);
    }

    private User user(String email, String name, UserRole role) {
        return users.findByEmail(email).orElseGet(() -> users.save(new User(UUID.randomUUID(), role, email,
                encoder.encode(PASSWORD), name, Instant.now(clock))));
    }
}
