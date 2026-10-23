package com.lostfound.photo;

import com.lostfound.model.Photo;
import org.springframework.core.io.Resource;

import java.io.IOException;

/**
 * Where re-encoded photo bytes live. The photos row is always written by
 * {@link PhotoStorageService}; implementations only move bytes, so hosts with
 * ephemeral disks can swap the filesystem for the database.
 */
interface PhotoBlobStore {

    boolean available();

    /** Persist bytes for a photo whose row is being saved in this transaction. */
    void write(Photo photo, byte[] data) throws IOException;

    /** Open the stored bytes, or null when they are gone. */
    Resource open(Photo photo) throws IOException;

    /** Remove the stored bytes for a photo whose row is being deleted. */
    void delete(Photo photo);
}
