package com.lostfound.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "photos")
@Getter
@Setter
@NoArgsConstructor
public class Photo {

    /** Opaque random identifier exposed in URLs. */
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id")
    private User uploader;

    @Column(nullable = false, length = 120)
    private String storageName;

    @Column(nullable = false, length = 40)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    private Integer width;

    private Integer height;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "attached_item_id")
    private Long attachedItemId;
}
