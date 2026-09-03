package com.pulsechat.service;

import com.pulsechat.model.Message;
import com.pulsechat.model.MessageType;
import com.pulsechat.model.SavedMedia;
import com.pulsechat.model.User;
import com.pulsechat.repo.MessageRepository;
import com.pulsechat.repo.SavedMediaRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SavedMediaService {
    private final SavedMediaRepository repo;
    private final MessageRepository messages;
    private final CloudinaryService cloud;

    public SavedMediaService(SavedMediaRepository repo, MessageRepository messages, CloudinaryService cloud) {
        this.repo = repo;
        this.messages = messages;
        this.cloud = cloud;
    }

    /**
     * Adds media to the shared site-wide library. The media is keyed by URL,
     * so when another user sends the same GIF/link it increases the global
     * usage count instead of creating a private copy.
     */
    public void recordSent(User user, String kind, String provider, String providerId,
                           String title, String url, String previewUrl, String publicId,
                           String mimeType, int width, int height) {
        if (user == null || url == null || url.isBlank()) return;

        String normalizedUrl = url.trim();
        SavedMedia item = repo.findByUrl(normalizedUrl)
                .orElseGet(() -> SavedMedia.builder()
                        .senderId(user.getId())
                        .senderName(user.getDisplayName())
                        .kind(kind)
                        .provider(provider)
                        .providerId(providerId)
                        .title(title)
                        .url(normalizedUrl)
                        .previewUrl(previewUrl)
                        .publicId(publicId)
                        .mimeType(mimeType)
                        .width(width)
                        .height(height)
                        .sentCount(0)
                        .createdAt(Instant.now())
                        .build());

        // Keep the first uploader as the original contributor, while the
        // count and last-used timestamp are shared across the whole site.
        if (item.getSenderId() == null || item.getSenderId().isBlank()) {
            item.setSenderId(user.getId());
            item.setSenderName(user.getDisplayName());
        }
        if (item.getKind() == null || item.getKind().isBlank()) item.setKind(kind);
        if (item.getProvider() == null || item.getProvider().isBlank()) item.setProvider(provider);
        if (item.getProviderId() == null || item.getProviderId().isBlank()) item.setProviderId(providerId);
        if (item.getTitle() == null || item.getTitle().isBlank()) item.setTitle(title);
        item.setPreviewUrl(previewUrl == null || previewUrl.isBlank() ? normalizedUrl : previewUrl);
        if (publicId != null && !publicId.isBlank()) item.setPublicId(publicId);
        if (mimeType != null && !mimeType.isBlank()) item.setMimeType(mimeType);
        if (width > 0) item.setWidth(width);
        if (height > 0) item.setHeight(height);
        item.setSentCount(item.getSentCount() + 1);
        item.setLastSentAt(Instant.now());
        repo.save(item);
    }

    /** Returns the shared media library for all authenticated users. */
    public List<SavedMedia> list() {
        return repo.findAllByOrderBySentCountDescLastSentAtDesc();
    }

    /** Returns any shared library item; it is intentionally not user-owned. */
    public SavedMedia get(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Saved media not found."));
    }

    /**
     * One-time compatibility migration for legacy per-user GIF history.
     * It imports old message history into the new global library when the
     * corresponding URL is not already present.
     */
    public void backfillLegacyHistory() {
        List<Message> existing = messages.findByTypeIn(List.of(MessageType.GIF, MessageType.STICKER));
        if (existing.isEmpty()) return;

        Map<String, List<Message>> grouped = existing.stream()
                .filter(m -> m.getMedia() != null && m.getMedia().getUrl() != null && !m.getMedia().getUrl().isBlank())
                .collect(Collectors.groupingBy(m -> m.getMedia().getUrl().trim()));

        grouped.forEach((url, group) -> {
            if (repo.findByUrl(url).isPresent()) return;

            Message latest = group.stream()
                    .max(Comparator.comparing(Message::getCreatedAt))
                    .orElse(group.get(0));
            Message.MediaInfo media = latest.getMedia();
            String storedUrl = url;
            String storedPreview = media.getPreviewUrl();
            String storedPublicId = null;
            String storedMime = media.getMimeType();
            int storedWidth = media.getWidth();
            int storedHeight = media.getHeight();

            if ("KLIPY".equalsIgnoreCase(media.getProvider()) && !isCloudinaryUrl(url)) {
                try {
                    var remote = cloud.uploadRemoteUrl(url);
                    storedUrl = remote.url();
                    storedPreview = remote.url();
                    storedPublicId = remote.publicId();
                    if (storedMime == null || storedMime.isBlank()) storedMime = remote.mimeType();
                    if (storedWidth <= 0) storedWidth = remote.width();
                    if (storedHeight <= 0) storedHeight = remote.height();
                } catch (Exception ignored) {
                    // Keep the legacy URL as a fallback; one failed media must not break migration.
                }
            }

            repo.save(SavedMedia.builder()
                    .senderId(latest.getSenderId())
                    .senderName(latest.getSenderName())
                    .kind(latest.getType().name())
                    .provider(media.getProvider())
                    .providerId(media.getProviderId())
                    .title(media.getTitle())
                    .url(storedUrl)
                    .previewUrl(storedPreview)
                    .publicId(storedPublicId)
                    .mimeType(storedMime)
                    .width(storedWidth)
                    .height(storedHeight)
                    .sentCount(group.size())
                    .createdAt(group.stream().map(Message::getCreatedAt).min(Instant::compareTo).orElse(Instant.now()))
                    .lastSentAt(latest.getCreatedAt())
                    .build());
        });
    }

    private boolean isCloudinaryUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith("res.cloudinary.com");
        } catch (Exception e) {
            return false;
        }
    }
}
