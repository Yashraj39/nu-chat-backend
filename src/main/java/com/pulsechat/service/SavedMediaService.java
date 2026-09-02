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

    public void recordSent(User user, String kind, String provider, String providerId,
                           String title, String url, String previewUrl, String publicId,
                           String mimeType, int width, int height) {
        if (user == null || url == null || url.isBlank()) return;

        String normalizedUrl = url.trim();
        SavedMedia item = repo.findBySenderIdAndUrl(user.getId(), normalizedUrl)
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

        item.setSenderName(user.getDisplayName());
        item.setKind(kind);
        item.setProvider(provider);
        item.setProviderId(providerId);
        item.setTitle(title);
        item.setPreviewUrl(previewUrl == null || previewUrl.isBlank() ? normalizedUrl : previewUrl);
        item.setPublicId(publicId == null || publicId.isBlank() ? item.getPublicId() : publicId);
        item.setMimeType(mimeType);
        item.setWidth(width);
        item.setHeight(height);
        item.setSentCount(item.getSentCount() + 1);
        item.setLastSentAt(Instant.now());
        repo.save(item);
    }

    public List<SavedMedia> list(User user) {
        backfillExistingGifHistory(user);
        return repo.findBySenderIdOrderBySentCountDescLastSentAtDesc(user.getId());
    }

    public SavedMedia getForUser(User user, String id) {
        return repo.findById(id)
                .filter(x -> user.getId().equals(x.getSenderId()))
                .orElseThrow(() -> new IllegalArgumentException("Saved media not found."));
    }

    private void backfillExistingGifHistory(User user) {
        if (repo.countBySenderId(user.getId()) > 0) return;

        List<Message> existing = messages.findBySenderIdAndTypeIn(
                user.getId(), List.of(MessageType.GIF, MessageType.STICKER));

        if (existing.isEmpty()) return;

        Map<String, List<Message>> grouped = existing.stream()
                .filter(m -> m.getMedia() != null && m.getMedia().getUrl() != null && !m.getMedia().getUrl().isBlank())
                .collect(Collectors.groupingBy(m -> m.getMedia().getUrl()));

        grouped.forEach((url, group) -> {
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
                    // Keep the legacy URL as a fallback; one failed media must not break the library.
                }
            }

            SavedMedia item = SavedMedia.builder()
                    .senderId(user.getId())
                    .senderName(user.getDisplayName())
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
                    .build();
            repo.save(item);
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
