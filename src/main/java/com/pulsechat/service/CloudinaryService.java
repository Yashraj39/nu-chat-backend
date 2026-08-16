package com.pulsechat.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Service
public class CloudinaryService {
    private final Cloudinary cloud;
    private final long max;

    public CloudinaryService(
            @Value("${cloudinary.cloud-name}") String n,
            @Value("${cloudinary.api-key}") String k,
            @Value("${cloudinary.api-secret}") String s,
            @Value("${app.max-file-size}") long max
    ) {
        this.max = max;
        if (n.isBlank() || k.isBlank() || s.isBlank()) {
            cloud = null;
        } else {
            cloud = new Cloudinary(ObjectUtils.asMap(
                    "cloud_name", n,
                    "api_key", k,
                    "api_secret", s
            ));
        }
    }

    /**
     * Upload arbitrary file types. MIME type is metadata, not an allow-list,
     * because browsers can report application/octet-stream for valid files.
     */
    public UploadResult upload(MultipartFile f) throws IOException {
        if (cloud == null) {
            throw new IllegalStateException("Cloudinary is not configured.");
        }

        if (f == null || f.isEmpty()) {
            throw new IllegalArgumentException("Empty file.");
        }

        if (f.getSize() > max) {
            throw new IllegalArgumentException(
                    "File is too large. Maximum size is " + formatBytes(max) + "."
            );
        }

        String name = sanitizeFilename(f.getOriginalFilename());
        String mime = normalizeMime(f.getContentType());

        // Cloudinary decides whether the upload is an image, video, audio,
        // or raw resource. This removes the old hard-coded file-type list.
        Map<?, ?> result = (Map<?, ?>) cloud.uploader().upload(
                f.getBytes(),
                ObjectUtils.asMap(
                        "resource_type", "auto",
                        "folder", "pulsechat",
                        "use_filename", true,
                        "unique_filename", true
                )
        );

        return new UploadResult(
                (String) result.get("secure_url"),
                (String) result.get("public_id"),
                name,
                mime,
                f.getSize()
        );
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "file";
        }

        String name = original
                .replace('\\', '_')
                .replace('/', '_')
                .replaceAll("[\\p{Cntrl}]", "_")
                .replaceAll("[^a-zA-Z0-9._ -]", "_")
                .trim();

        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "file";
        }

        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String normalizeMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }

        String mime = contentType.toLowerCase(Locale.ROOT).trim();
        return mime.isBlank() ? "application/octet-stream" : mime;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + " MB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return bytes + " bytes";
    }

    public record UploadResult(
            String url,
            String publicId,
            String originalName,
            String mimeType,
            long size
    ) {
    }
}
