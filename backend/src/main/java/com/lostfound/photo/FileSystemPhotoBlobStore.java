package com.lostfound.photo;

import com.lostfound.model.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local-disk blob store: the default for development, where the uploads
 * directory survives restarts. Bytes are written inside the caller's
 * transaction but a file outlives it only if the transaction commits, so a
 * rollback from the metadata save or commit never leaves an orphan.
 */
@Component
@ConditionalOnProperty(name = "app.photos.storage", havingValue = "filesystem", matchIfMissing = true)
class FileSystemPhotoBlobStore implements PhotoBlobStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemPhotoBlobStore.class);

    private final Path root;
    private final boolean available;

    FileSystemPhotoBlobStore(@Value("${app.photos.directory:uploads}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        boolean ok;
        try {
            Files.createDirectories(root);
            ok = true;
        } catch (IOException ex) {
            log.error("Photo directory {} is unavailable: {}", root, ex.getMessage());
            ok = false;
        }
        this.available = ok;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void write(Photo photo, byte[] data) throws IOException {
        Path target = resolve(photo.getStorageName());
        Files.write(target, data);
        registerRollbackCleanup(target);
    }

    @Override
    public Resource open(Photo photo) {
        Path file = resolve(photo.getStorageName());
        if (!file.startsWith(root) || !Files.isReadable(file)) {
            return null;
        }
        return new FileSystemResource(file);
    }

    @Override
    public void delete(Photo photo) {
        Path file = resolve(photo.getStorageName());
        // The file disappears only after the row removal commits, so a
        // rollback can never leave a database reference to a deleted file.
        if (file.startsWith(root)) {
            deleteAfterCommit(file);
        }
    }

    private Path resolve(String storageName) {
        return root.resolve(storageName).normalize();
    }

    /**
     * A written file outlives its transaction only if the transaction commits.
     * Registered the moment the file exists, so a rollback from the metadata
     * save or commit never leaves an orphan the cleanup job cannot find.
     */
    private static void registerRollbackCleanup(Path path) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                deleteQuietly(path);
                            }
                        }
                    });
        }
    }

    private static void deleteAfterCommit(Path path) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deleteQuietly(path);
                        }
                    });
        } else {
            deleteQuietly(path);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort; the maintenance path reports leftovers later.
        }
    }
}
