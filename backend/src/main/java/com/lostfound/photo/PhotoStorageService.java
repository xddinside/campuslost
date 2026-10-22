package com.lostfound.photo;

import com.lostfound.dto.PhotoResponse;
import com.lostfound.exception.ApiException;
import com.lostfound.model.Item;
import com.lostfound.model.Photo;
import com.lostfound.model.User;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorageService.class);
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final int MAX_DIMENSION = 12_000;

    private final PhotoRepository photoRepository;
    private final ItemRepository itemRepository;
    private final Path root;
    private final long maxBytes;
    private final int maxUploadsPerDay;
    private final boolean available;

    public PhotoStorageService(PhotoRepository photoRepository,
                               ItemRepository itemRepository,
                               @Value("${app.photos.directory:uploads}") String directory,
                               @Value("${app.photos.max-bytes:5242880}") long maxBytes,
                               @Value("${app.photos.max-uploads-per-day:20}") int maxUploadsPerDay) {
        this.photoRepository = photoRepository;
        this.itemRepository = itemRepository;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.maxUploadsPerDay = maxUploadsPerDay;
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

    @Transactional
    public PhotoResponse upload(User user, MultipartFile file) {
        if (!available) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PHOTO_STORAGE_UNAVAILABLE",
                    "Photo storage is unavailable. Ask an administrator to check the server.");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO", "Choose a photo to upload.");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "Photos must be 5 MiB or smaller.");
        }
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO_TYPE",
                    "Use a JPEG, PNG, or WebP photo.");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO_TYPE",
                    "Use a JPEG, PNG, or WebP photo.");
        }
        if (photoRepository.countByUploaderIdAndCreatedAtAfter(user.getId(),
                Instant.now().minus(1, ChronoUnit.DAYS)) >= maxUploadsPerDay) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PHOTO_LIMIT",
                    "You reached today's photo upload limit. Try again tomorrow.");
        }

        BufferedImage image;
        try (InputStream in = file.getInputStream()) {
            image = ImageIO.read(in);
        } catch (IOException | RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                    "That file is not a readable photo.");
        }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                    "That file is not a readable photo.");
        }
        if (image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                    "Photos must be 12000 pixels or smaller on each side.");
        }

        // Re-encode decoded pixels so EXIF metadata, GPS, and embedded payloads
        // never reach storage.
        boolean jpeg = "image/jpeg".equals(contentType);
        String storageExtension = jpeg ? "jpg" : "png";
        String storedType = jpeg ? "image/jpeg" : "image/png";
        String id = UUID.randomUUID().toString();
        String storageName = id + "." + storageExtension;
        Path target = root.resolve(storageName);
        try {
            boolean written = ImageIO.write(image, storageExtension, target.toFile());
            if (!written) {
                throw new IOException("No image writer for " + storageExtension);
            }
            long size = Files.size(target);
            if (size > maxBytes) {
                Files.deleteIfExists(target);
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                        "The processed photo is larger than 5 MiB. Choose a smaller image.");
            }
            Photo photo = new Photo();
            photo.setId(id);
            photo.setUploader(user);
            photo.setStorageName(storageName);
            photo.setContentType(storedType);
            photo.setSizeBytes(size);
            photo.setWidth(image.getWidth());
            photo.setHeight(image.getHeight());
            photoRepository.save(photo);
            return new PhotoResponse(id, url(id));
        } catch (ApiException ex) {
            deleteQuietly(target);
            throw ex;
        } catch (IOException ex) {
            deleteQuietly(target);
            log.error("Could not store photo", ex);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PHOTO_STORAGE_FAILED",
                    "The photo could not be saved. Try again.");
        }
    }

    /** Attach an upload to an item the actor owns, rolling back on any mismatch. */
    @Transactional
    public void attach(User actor, Item item, String photoId) {
        if (photoId == null) {
            return;
        }
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                        "That photo no longer exists. Upload it again."));
        if (!photo.getUploader().getId().equals(actor.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PHOTO_FORBIDDEN",
                    "You can only attach photos you uploaded.");
        }
        if (photo.getAttachedItemId() != null && !photo.getAttachedItemId().equals(item.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "PHOTO_IN_USE",
                    "That photo is already attached to another report.");
        }
        photo.setAttachedItemId(item.getId());
        photoRepository.save(photo);
        item.setPhotoId(photo.getId());
    }

    /** Detach and delete a photo that the item no longer references. */
    @Transactional
    public void detachAndDelete(String photoId) {
        if (photoId == null) {
            return;
        }
        photoRepository.findById(photoId).ifPresent(photo -> {
            Path file = root.resolve(photo.getStorageName()).normalize();
            if (file.startsWith(root)) {
                deleteQuietly(file);
            }
            photoRepository.delete(photo);
        });
    }

    @Transactional(readOnly = true)
    public PhotoFile publicFile(String photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Photo not found."));
        // Only photos attached to a visible report are public. Unattached uploads
        // and photos of soft-deleted reports stay private.
        if (photo.getAttachedItemId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Photo not found.");
        }
        boolean visible = itemRepository.findByIdAndDeletedAtIsNull(photo.getAttachedItemId())
                .filter(item -> photoId.equals(item.getPhotoId()))
                .isPresent();
        if (!visible) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Photo not found.");
        }
        Path file = root.resolve(photo.getStorageName()).normalize();
        if (!file.startsWith(root) || !Files.isReadable(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Photo not found.");
        }
        return new PhotoFile(file, photo.getContentType(), photo.getSizeBytes());
    }

    @Transactional
    public int cleanupUnattached(int olderThanHours) {
        int removed = 0;
        for (Photo photo : photoRepository.findByAttachedItemIdIsNullAndCreatedAtBefore(
                Instant.now().minus(olderThanHours, ChronoUnit.HOURS))) {
            Path file = root.resolve(photo.getStorageName()).normalize();
            if (file.startsWith(root)) {
                deleteQuietly(file);
            }
            photoRepository.delete(photo);
            removed++;
        }
        return removed;
    }

    public String url(String photoId) {
        return photoId == null ? null : "/api/photos/" + photoId;
    }

    public Path root() {
        return root;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort; the maintenance path reports leftovers later.
        }
    }

    public record PhotoFile(Path path, String contentType, long size) {
    }
}
