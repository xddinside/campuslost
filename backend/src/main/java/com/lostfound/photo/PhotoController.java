package com.lostfound.photo;

import com.lostfound.dto.PhotoResponse;
import com.lostfound.model.User;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/photos")
public class PhotoController {

    private final PhotoStorageService photoStorageService;

    public PhotoController(PhotoStorageService photoStorageService) {
        this.photoStorageService = photoStorageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoResponse upload(@AuthenticationPrincipal User user,
                                @RequestPart("file") MultipartFile file) {
        return photoStorageService.upload(user, file);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> serve(@PathVariable String id) {
        PhotoStorageService.PhotoFile photo = photoStorageService.publicFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .contentLength(photo.size())
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(photo.resource());
    }
}
