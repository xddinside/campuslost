package com.lostfound.notifications;

import com.lostfound.model.ItemStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    /**
     * A match alert is visible only while both reports are still public and
     * OPEN. Claim alerts remain as authorised history.
     */
    @Query("""
            select n from Notification n
            where n.recipientId = :recipientId
              and n.active = true
              and (:beforeId is null or n.id < :beforeId)
              and (n.type <> :matchType
                   or (exists (select 1 from Item l
                               where l.id = n.lostItemId
                                 and l.deletedAt is null
                                 and l.status = :openStatus)
                       and exists (select 1 from Item f
                                   where f.id = n.foundItemId
                                     and f.deletedAt is null
                                     and f.status = :openStatus)))
            order by n.id desc
            """)
    List<Notification> findVisiblePage(@Param("recipientId") Long recipientId,
                                       @Param("beforeId") Long beforeId,
                                       @Param("matchType") NotificationType matchType,
                                       @Param("openStatus") ItemStatus openStatus,
                                       Pageable pageable);

    @Query("""
            select count(n) from Notification n
            where n.recipientId = :recipientId
              and n.active = true
              and n.readAt is null
              and (n.type <> :matchType
                   or (exists (select 1 from Item l
                               where l.id = n.lostItemId
                                 and l.deletedAt is null
                                 and l.status = :openStatus)
                       and exists (select 1 from Item f
                                   where f.id = n.foundItemId
                                     and f.deletedAt is null
                                     and f.status = :openStatus)))
            """)
    long countVisibleUnread(@Param("recipientId") Long recipientId,
                            @Param("matchType") NotificationType matchType,
                            @Param("openStatus") ItemStatus openStatus);

    @Query("""
            select n from Notification n
            where n.type = :type
              and (n.lostItemId = :itemId or n.foundItemId = :itemId)
            """)
    List<Notification> findMatchRowsForSource(@Param("type") NotificationType type,
                                              @Param("itemId") Long itemId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.readAt = :readAt
            where n.recipientId = :recipientId
              and n.readAt is null
              and n.createdAt <= :cutoff
            """)
    int markAllRead(@Param("recipientId") Long recipientId,
                    @Param("readAt") Instant readAt,
                    @Param("cutoff") Instant cutoff);
}
