package com.pulsechat.controller;

import com.pulsechat.service.GoogleDriveService;
import com.pulsechat.service.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files/drive")
public class GoogleDriveController {
    private final GoogleDriveService drive;
    private final RateLimiter limiter;
    private final int uploadRateLimit;

    public GoogleDriveController(
            GoogleDriveService drive,
            RateLimiter limiter,
            @Value("${app.upload-rate-limit:10}") int uploadRateLimit
    ) {
        this.drive = drive;
        this.limiter = limiter;
        this.uploadRateLimit = Math.max(1, uploadRateLimit);
    }

    @PostMapping("/prepare")
    public ResponseEntity<Map<String, Object>> prepare(
            @RequestBody PrepareRequest body,
            Authentication authentication
    ) throws Exception {
        if (!limiter.allow("upload:" + authentication.getName(), uploadRateLimit)) {
            throw new IllegalStateException("Too many uploads. Please slow down.");
        }
        if (body == null) throw new IllegalArgumentException("Upload information is required.");
        GoogleDriveService.PreparedUpload prepared = drive.prepareUpload(body.fileName(), body.mimeType(), body.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadUrl", prepared.uploadUrl());
        result.put("originalName", prepared.originalName());
        result.put("mimeType", prepared.mimeType());
        result.put("size", prepared.size());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @RequestBody CompleteRequest body,
            Authentication authentication
    ) throws Exception {
        if (body == null) throw new IllegalArgumentException("Uploaded file information is required.");
        GoogleDriveService.DriveFile file = drive.finalizeUpload(
                body.fileId(), body.originalName(), body.mimeType(), body.size()
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("driveFileId", file.fileId());
        result.put("url", file.downloadUrl());
        result.put("viewUrl", file.viewUrl());
        result.put("thumbnailUrl", file.thumbnailUrl());
        result.put("originalName", file.originalName());
        result.put("mimeType", file.mimeType());
        result.put("size", file.size());
        return ResponseEntity.ok(result);
    }

    public record PrepareRequest(String fileName, String mimeType, long size) {}
    public record CompleteRequest(String fileId, String originalName, String mimeType, long size) {}
}
