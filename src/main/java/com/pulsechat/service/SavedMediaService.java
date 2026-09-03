package com.pulsechat.service;

import com.pulsechat.model.Message;
import com.pulsechat.model.MessageType;
import com.pulsechat.model.SavedMedia;
import com.pulsechat.model.User;
import com.pulsechat.repo.MessageRepository;
import com.pulsechat.repo.SavedMediaRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SavedMediaService {
    private final SavedMediaRepository repo;
    private final MessageRepository messages;

    public SavedMediaService(SavedMediaRepository repo, MessageRepository messages) {
        this.repo = repo;
        this.messages = messages;
    }

    /**
     * Adds media to the shared site-wide library.
     * The original direct URL is retained; no proxy, download, or media host
     * is introduced. The URL is the unique key for the shared item.
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

    /** Returns the shared media library for every authenticated user. */
    public List<SavedMedia> list() {
        backfillLegacyHistory();
        return repo.findAllByOrderBySentCountDescLastSentAtDesc();
    }

    /** Returns a shared item without restricting it to its original sender. */
    public SavedMedia get(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Saved media not found."));
    }

    /**
     * Imports GIF/sticker messages created before the shared-media change.
     * Existing direct URLs are kept exactly as they were; no network request
     * is made by the server. The browser will validate usability locally.
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

            repo.save(SavedMedia.builder()
                    .senderId(latest.getSenderId())
                    .senderName(latest.getSenderName())
                    .kind(latest.getType().name())
                    .provider(media.getProvider())
                    .providerId(media.getProviderId())
                    .title(media.getTitle())
                    .url(url)
                    .previewUrl(media.getPreviewUrl() == null || media.getPreviewUrl().isBlank() ? url : media.getPreviewUrl())
                    .publicId(null)
                    .mimeType(media.getMimeType())
                    .width(media.getWidth())
                    .height(media.getHeight())
                    .sentCount(group.size())
                    .createdAt(group.stream().map(Message::getCreatedAt).min(Instant::compareTo).orElse(Instant.now()))
                    .lastSentAt(latest.getCreatedAt())
                    .build());
        });
    }
}
