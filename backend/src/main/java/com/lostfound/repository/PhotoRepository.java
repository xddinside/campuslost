package com.lostfound.repository;

import com.lostfound.model.Photo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, String> {

    long countByUploaderIdAndCreatedAtAfter(Long uploaderId, Instant since);

    List<Photo> findByAttachedItemIdIsNullAndCreatedAtBefore(Instant before);
}
