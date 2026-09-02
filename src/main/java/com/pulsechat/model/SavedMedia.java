package com.pulsechat.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("saved_media")
public class SavedMedia {
    @Id
    private String id;
    private String senderId;
    private String senderName;
    private String kind;
    private String provider;
    private String providerId;
    private String title;
    private String url;
    private String previewUrl;
    private String publicId;
    private String mimeType;
    private int width;
    private int height;
    private long sentCount;
    private Instant createdAt;
    private Instant lastSentAt;
}
