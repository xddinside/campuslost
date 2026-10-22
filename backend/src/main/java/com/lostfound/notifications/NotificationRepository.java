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

    @Query("""
            select n from Notification n
            where n.id = :id
              and n.recipientId = :recipientId
              and n.active = true
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
    Optional<Notification> findVisibleByIdAndRecipientId(@Param("id") Long id,
                                                          @Param("recipientId") Long recipientId,
                                                          @Param("matchType") NotificationType matchType,
                                                          @Param("openStatus") ItemStatus openStatus);

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

    @Query(value = """
            update notifications
            set active = false
            where id = :id and active = true
            """, nativeQuery = true)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    int deactivate(@Param("id") Long id);

    @Query(value = """
            update notifications
            set read_at = coalesce(read_at, :readAt)
            where id = :id and recipient_id = :recipientId
            """, nativeQuery = true)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    int markReadIfUnread(@Param("id") Long id,
                         @Param("recipientId") Long recipientId,
                         @Param("readAt") Instant readAt);

    @Query(value = """
            update notifications
            set read_at = :readAt
            where recipient_id = :recipientId
              and active = true
              and read_at is null
              and created_at <= :cutoff
              and (type <> 'MATCH_FOUND'
                   or (exists (select 1 from items l
                               where l.id = notifications.lost_item_id
                                 and l.deleted_at is null
                                 and l.status = 'OPEN')
                       and exists (select 1 from items f
                                   where f.id = notifications.found_item_id
                                     and f.deleted_at is null
                                     and f.status = 'OPEN')))
            """, nativeQuery = true)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    int markAllRead(@Param("recipientId") Long recipientId,
                    @Param("readAt") Instant readAt,
                    @Param("cutoff") Instant cutoff);
}
