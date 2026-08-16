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
     * Upload files using an explicit Cloudinary resource type.
     *
     * PDFs are kept as image assets because Cloudinary natively supports PDF
     * delivery and browser viewing that way. Video/audio use the video asset
     * type. Everything else is uploaded as a raw asset so archives and other
     * arbitrary files are preserved byte-for-byte instead of being auto-
     * detected as another asset type.
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
        String resourceType = resolveResourceType(name, mime);

        Map<?, ?> result = (Map<?, ?>) cloud.uploader().upload(
                f.getBytes(),
                ObjectUtils.asMap(
                        "resource_type", resourceType,
                        "folder", "pulsechat",
                        "use_filename", true,
                        "unique_filename", true,
                        "filename_override", name
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

    private String resolveResourceType(String filename, String mime) {
        String lowerName = filename.toLowerCase(Locale.ROOT);

        // Cloudinary supports PDFs as image assets, which also enables normal
        // PDF delivery and browser rendering.
        if ("application/pdf".equals(mime) || lowerName.endsWith(".pdf")) {
            return "image";
        }

        if (mime.startsWith("image/")) {
            return "image";
        }

        // Cloudinary treats audio as video resources.
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            return "video";
        }

        // ZIP/RAR/7z/DOCX/APK/JAR/source files/etc. stay raw.
        return "raw";
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
