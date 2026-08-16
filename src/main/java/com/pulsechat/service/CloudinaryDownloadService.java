package com.pulsechat.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.pulsechat.model.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class CloudinaryDownloadService {
    private final Cloudinary cloud;

    public CloudinaryDownloadService(
            @Value("${cloudinary.cloud-name}") String name,
            @Value("${cloudinary.api-key}") String key,
            @Value("${cloudinary.api-secret}") String secret
    ) {
        if (name == null || name.isBlank() || key == null || key.isBlank() || secret == null || secret.isBlank()) {
            cloud = null;
        } else {
            cloud = new Cloudinary(ObjectUtils.asMap(
                    "cloud_name", name,
                    "api_key", key,
                    "api_secret", secret
            ));
        }
    }

    /**
     * Generates a short-lived Cloudinary authenticated download URL.
     * PDFs and raw files are uploaded as private assets, so public PDF/ZIP
     * delivery restrictions do not affect their downloads.
     */
    public String createDownloadUrl(Message.FileInfo file) {
        if (cloud == null) {
            throw new IllegalStateException("Cloudinary is not configured.");
        }
        if (file == null || file.getPublicId() == null || file.getPublicId().isBlank()) {
            throw new IllegalArgumentException("File metadata is missing.");
        }

        String resourceType = resolveResourceType(file.getMimeType());
        String format = resolveFormat(file.getOriginalName(), file.getPublicId());
        boolean restricted = "raw".equals(resourceType)
                || "application/pdf".equalsIgnoreCase(file.getMimeType());

        long expiresAt = (System.currentTimeMillis() / 1000L) + 300L;

        return cloud.privateDownload(
                file.getPublicId(),
                format,
                ObjectUtils.asMap(
                        "resource_type", resourceType,
                        "type", restricted ? "private" : "upload",
                        "attachment", true,
                        "expires_at", expiresAt
                )
        );
    }

    private String resolveResourceType(String mime) {
        String value = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (value.startsWith("image/") || "application/pdf".equals(value)) return "image";
        if (value.startsWith("video/") || value.startsWith("audio/")) return "video";
        return "raw";
    }

    private String resolveFormat(String originalName, String publicId) {
        String source = originalName != null && originalName.contains(".")
                ? originalName
                : publicId;
        if (source != null) {
            int dot = source.lastIndexOf('.');
            if (dot >= 0 && dot < source.length() - 1) {
                return source.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        return "bin";
    }
}
