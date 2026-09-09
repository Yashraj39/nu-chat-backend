package com.pulsechat.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.Url;
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
     * Creates a server-side Cloudinary URL for the proxy to fetch.
     * Private assets use Cloudinary's private-download API. Public assets use
     * a signed delivery URL. The browser only receives the application's proxy URL.
     */
    public String createDownloadUrl(Message.FileInfo file) throws Exception {
        if (cloud == null) throw new IllegalStateException("Cloudinary is not configured.");
        if (file == null || file.getPublicId() == null || file.getPublicId().isBlank()) {
            throw new IllegalArgumentException("File metadata is missing.");
        }

        String resourceType = resolveResourceType(file.getMimeType());
        String deliveryType = resolveDeliveryType(file);
        String format = resolveFormat(file.getOriginalName(), file.getPublicId());

        if ("private".equals(deliveryType) || "authenticated".equals(deliveryType)) {
            String downloadFormat = format == null || format.isBlank() ? "bin" : format;
            return cloud.privateDownload(
                    file.getPublicId(),
                    downloadFormat,
                    ObjectUtils.asMap(
                            "resource_type", resourceType,
                            "type", deliveryType,
                            "attachment", false,
                            "expires_at", (System.currentTimeMillis() / 1000L) + 3600L
                    )
            );
        }

        return cloud.url()
                .secure(true)
                .resourceType(resourceType)
                .type("upload")
                .signed(true)
                .format(format == null || format.isBlank() ? null : format)
                .generate(file.getPublicId());
    }

    /**
     * Creates a small, efficient image delivery URL for chat previews.
     * The original Cloudinary asset remains untouched and full downloads still
     * use createDownloadUrl().
     */
    public String createImagePreviewUrl(Message.FileInfo file) throws Exception {
        if (cloud == null) throw new IllegalStateException("Cloudinary is not configured.");
        if (file == null || file.getPublicId() == null || file.getPublicId().isBlank()) {
            throw new IllegalArgumentException("File metadata is missing.");
        }

        String mime = file.getMimeType() == null ? "" : file.getMimeType().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/") || "image/svg+xml".equals(mime)) {
            return createDownloadUrl(file);
        }

        String format = resolveFormat(file.getOriginalName(), file.getPublicId());
        Url url = cloud.url()
                .secure(true)
                .resourceType("image")
                .type("upload")
                .signed(true)
                .transformation(new Transformation()
                        .width(1200)
                        .crop("limit")
                        .quality("auto")
                        .fetchFormat("auto"));
        if (format != null && !format.isBlank()) url.format(format);
        return url.generate(file.getPublicId());
    }

    private String resolveDeliveryType(Message.FileInfo file) {
        String storedUrl = file.getUrl();
        if (storedUrl != null) {
            if (storedUrl.contains("/private/")) return "private";
            if (storedUrl.contains("/authenticated/")) return "authenticated";
        }

        String mime = file.getMimeType() == null ? "" : file.getMimeType().toLowerCase(Locale.ROOT);
        if ("application/pdf".equals(mime) || (!mime.startsWith("image/") && !mime.startsWith("video/") && !mime.startsWith("audio/"))) {
            return "private";
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
        String source = originalName != null && originalName.contains(".") ? originalName : publicId;
        if (source != null) {
            int dot = source.lastIndexOf('.');
            if (dot >= 0 && dot < source.length() - 1) {
                return source.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
