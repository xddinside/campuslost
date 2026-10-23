package com.lostfound.photo;

import com.lostfound.dto.PhotoResponse;
import com.lostfound.exception.ApiException;
import com.lostfound.model.Item;
import com.lostfound.model.Photo;
import com.lostfound.model.PhotoUpload;
import com.lostfound.model.User;
import com.lostfound.repository.ItemRepository;
import com.lostfound.repository.PhotoRepository;
import com.lostfound.repository.PhotoUploadRepository;
import com.lostfound.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class PhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorageService.class);
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final int MAX_DIMENSION = 12_000;
    /** Decoded-pixel budget: a few bytes per pixel keep uploads off the heap cliff. */
    private static final long MAX_PIXELS = 40_000_000L;
    /** Bounds concurrent decodes so the aggregate decoded size stays small. */
    private static final Semaphore DECODE_PERMITS = new Semaphore(4);

    private final PhotoRepository photoRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final PhotoUploadRepository photoUploadRepository;
    private final PhotoBlobStore store;
    private final long maxBytes;
    private final int maxUploadsPerDay;

    public PhotoStorageService(PhotoRepository photoRepository,
                               ItemRepository itemRepository,
                               UserRepository userRepository,
                               PhotoUploadRepository photoUploadRepository,
                               PhotoBlobStore store,
                               @Value("${app.photos.max-bytes:5242880}") long maxBytes,
                               @Value("${app.photos.max-uploads-per-day:20}") int maxUploadsPerDay) {
        this.photoRepository = photoRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.photoUploadRepository = photoUploadRepository;
        this.store = store;
        this.maxBytes = maxBytes;
        this.maxUploadsPerDay = maxUploadsPerDay;
    }

    @Transactional
    public PhotoResponse upload(User user, MultipartFile file) {
        if (!store.available()) {
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
        // Serialize the quota check and charge on the uploader's row, and
        // count the append-only ledger rather than surviving photos: deleting
        // a photo can never hand allowance back.
        userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Your session is no longer valid."));
        if (photoUploadRepository.countByUploaderIdAndCreatedAtAfter(user.getId(),
                Instant.now().minus(1, ChronoUnit.DAYS)) >= maxUploadsPerDay) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PHOTO_LIMIT",
                    "You reached today's photo upload limit. Try again tomorrow.");
        }

        // Read dimensions from the image header before allocating any decoded
        // pixels; a compressed file can declare huge dimensions otherwise.
        // The decode permit is held through re-encoding and disposal, since a
        // decoded image stays live until flushed.
        boolean jpeg = "image/jpeg".equals(contentType);
        String storageExtension = jpeg ? "jpg" : "png";
        String storedType = jpeg ? "image/jpeg" : "image/png";
        String id = UUID.randomUUID().toString();
        String storageName = id + "." + storageExtension;
        int imageWidth;
        int imageHeight;
        byte[] data;
        try {
            DECODE_PERMITS.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PHOTO_BUSY",
                    "Photo processing is busy. Try again.");
        }
        BufferedImage image = null;
        try (InputStream in = file.getInputStream();
             ImageInputStream iis = ImageIO.createImageInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (iis == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                        "That file is not a readable photo.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                        "That file is not a readable photo.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, false);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                            "That file is not a readable photo.");
                }
                if (width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                            "That photo is too large to process safely.");
                }
                image = reader.read(0);
                if (image == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                            "That file is not a readable photo.");
                }
                // Re-encode decoded pixels so EXIF metadata, GPS, and embedded
                // payloads never reach storage — still under the permit.
                if (!ImageIO.write(image, storageExtension, out)) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PHOTO_STORAGE_FAILED",
                            "The photo could not be saved. Try again.");
                }
                imageWidth = image.getWidth();
                imageHeight = image.getHeight();
                data = out.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                    "That file is not a readable photo.");
        } finally {
            if (image != null) {
                image.flush();
            }
            DECODE_PERMITS.release();
        }

        try {
            if (data.length > maxBytes) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                        "The processed photo is larger than 5 MiB. Choose a smaller image.");
            }
            Photo photo = new Photo();
            photo.setId(id);
            photo.setUploader(user);
            photo.setStorageName(storageName);
            photo.setContentType(storedType);
            photo.setSizeBytes(data.length);
            photo.setWidth(imageWidth);
            photo.setHeight(imageHeight);
            // Flush so the photos row exists before a blob insert that
            // references it; filesystem storage is order-insensitive.
            photoRepository.saveAndFlush(photo);
            store.write(photo, data);
            PhotoUpload charge = new PhotoUpload();
            charge.setUploader(user);
            charge.setPhotoId(id);
            photoUploadRepository.save(charge);
            return new PhotoResponse(id, url(id));
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
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
        // The row lock makes single-item attachment atomic: two concurrent
        // attach requests cannot both observe an unattached photo.
        Photo photo = photoRepository.findByIdForUpdate(photoId)
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
        photoRepository.findByIdForUpdate(photoId).ifPresent(photo -> {
            store.delete(photo);
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
        Resource resource;
        try {
            resource = store.open(photo);
        } catch (IOException ex) {
            resource = null;
        }
        if (resource == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Photo not found.");
        }
        return new PhotoFile(resource, photo.getContentType(), photo.getSizeBytes());
    }

    @Transactional
    public int cleanupUnattached(int olderThanHours) {
        int removed = 0;
        Instant cutoff = Instant.now().minus(olderThanHours, ChronoUnit.HOURS);
        // Select ids only: loading entities first would cache them, and the
        // lock query can then return a row whose attachedItemId is already
        // stale against a concurrent attach. Loading under the lock always
        // reads the committed state.
        for (String id : photoRepository.findUnattachedIdsCreatedBefore(cutoff)) {
            Photo photo = photoRepository.findByIdForUpdate(id).orElse(null);
            if (photo == null || photo.getAttachedItemId() != null) {
                continue;
            }
            store.delete(photo);
            photoRepository.delete(photo);
            removed++;
        }
        return removed;
    }

    public String url(String photoId) {
        return photoId == null ? null : "/api/photos/" + photoId;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record PhotoFile(Resource resource, String contentType, long size) {
    }
}
