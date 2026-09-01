package com.pulsechat.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document("messages")
public class Message {
    @Id private String id;
    @Indexed private String senderId;
    private String senderName;
    private MessageType type;
    private String content;
    private FileInfo file;
    private MediaInfo media;
    private ReplyReference replyTo;
    @Indexed private boolean deleted;
    private Instant deletedAt;
    @Indexed private Instant createdAt;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FileInfo {
        private String url;
        private String publicId;
        private String originalName;
        private String mimeType;
        private long size;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MediaInfo {
        private String provider;
        private String providerId;
        private String title;
        private String url;
        private String previewUrl;
        private int width;
        private int height;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ReplyReference {
        private String messageId;
        private String senderId;
        private String senderName;
        private MessageType type;
        private String content;
        private String fileName;
        private String mimeType;
        private boolean deleted;
    }
}
