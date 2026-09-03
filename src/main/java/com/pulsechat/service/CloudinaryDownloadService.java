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
     * Creates a server-to-server Cloudinary delivery URL.
     * The browser never receives this URL; ChatController fetches it from
     * Render and streams the bytes to the client through /api/files/content.
     */
    public String createDownloadUrl(Message.FileInfo file) {
        if (cloud == null) {
            throw new IllegalStateException("Cloudinary is not configured.");
        }
        if (file == null || file.getPublicId() == null || file.getPublicId().isBlank()) {
            throw new IllegalArgumentException("File metadata is missing.");
        }

        String resourceType = resolveResourceType(file.getMimeType());
        String deliveryType = resolveDeliveryType(file.getUrl());
        String format = resolveFormat(file.getOriginalName(), file.getPublicId());

        // sign_url makes private/authenticated resources accessible to the
        // backend without exposing Cloudinary credentials to the browser.
        return cloud.url(file.getPublicId(), ObjectUtils.asMap(
                "secure", true,
                "resource_type", resourceType,
                "type", deliveryType,
                "sign_url", true,
                "format", format
        ));
    }

    private String resolveDeliveryType(String storedUrl) {
        if (storedUrl != null) {
            if (storedUrl.contains("/private/")) return "private";
            if (storedUrl.contains("/authenticated/")) return "authenticated";
        }
        return "upload";
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
        return null;
    }
}
