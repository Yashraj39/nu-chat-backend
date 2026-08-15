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
}
