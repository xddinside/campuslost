package com.lostfound.notifications;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRetryRepository extends JpaRepository<NotificationRetry, Long> {

    @Query("""
            select r from NotificationRetry r
            where r.nextAttemptAt <= :now
            order by r.id
            """)
    List<NotificationRetry> findDue(@Param("now") Instant now, Pageable pageable);

    /** Only the newest failed snapshot for a report is worth replaying. */
    void deleteByKindAndItemId(NotificationRetry.Kind kind, Long itemId);

    @Query("""
            update NotificationRetry r
            set r.attempts = r.attempts + 1,
                r.lastError = :error,
                r.nextAttemptAt = :next
            where r.id = :id
            """)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    void markAttempt(@Param("id") Long id,
                     @Param("error") String error,
                     @Param("next") Instant next);
}
