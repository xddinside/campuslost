package com.lostfound.photo;

import com.lostfound.model.Photo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Database blob store for hosts with ephemeral disks (Render, containers
 * without a volume). Bytes live in the photo_blobs table, so they survive
 * deploys and take part in the same transaction as the photos row — a
 * rollback can never leave an orphan or a dangling reference.
 */
@Component
@ConditionalOnProperty(name = "app.photos.storage", havingValue = "database")
class DatabasePhotoBlobStore implements PhotoBlobStore {

    private final JdbcTemplate jdbc;

    DatabasePhotoBlobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void write(Photo photo, byte[] data) {
        jdbc.update("INSERT INTO photo_blobs (photo_id, data) VALUES (?, ?)",
                photo.getId(), data);
    }

    @Override
    public Resource open(Photo photo) {
        try {
            byte[] data = jdbc.queryForObject(
                    "SELECT data FROM photo_blobs WHERE photo_id = ?",
                    byte[].class, photo.getId());
            return data == null ? null : new ByteArrayResource(data);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @Override
    public void delete(Photo photo) {
        jdbc.update("DELETE FROM photo_blobs WHERE photo_id = ?", photo.getId());
    }
}
