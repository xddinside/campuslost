package com.lostfound.repository;

import com.lostfound.model.PhotoUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface PhotoUploadRepository extends JpaRepository<PhotoUpload, Long> {

    long countByUploaderIdAndCreatedAtAfter(Long uploaderId, Instant since);
}
