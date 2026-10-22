package com.lostfound.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only upload ledger. Quota counts come from this table rather than
 * surviving photo rows, so deleting a photo can never restore allowance and
 * the count survives report detach and cleanup.
 */
@Entity
@Table(name = "photo_uploads")
@Getter
@Setter
@NoArgsConstructor
public class PhotoUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id")
    private User uploader;

    @Column(name = "photo_id", nullable = false, length = 36)
    private String photoId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
