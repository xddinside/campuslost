package com.lostfound.repository;

import com.lostfound.model.Photo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PhotoRepository extends JpaRepository<Photo, String> {

    long countByUploaderIdAndCreatedAtAfter(Long uploaderId, Instant since);

    List<Photo> findByAttachedItemIdIsNullAndCreatedAtBefore(Instant before);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.id = :id")
    Optional<Photo> findByIdForUpdate(@Param("id") String id);
}
