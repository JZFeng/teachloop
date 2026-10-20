package com.teachloop.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    List<Notification> findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientId,
                                                                           Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(UUID recipientId);

    /** 只能读自己的：查不到（含别人的通知）就是 404，不泄漏存在性。 */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    /** 全部标已读，重复执行无副作用（已经是已读的行不再改 read_at）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now "
            + "where n.recipientId = :recipientId and n.readAt is null")
    int markAllRead(UUID recipientId, Instant now);
}
