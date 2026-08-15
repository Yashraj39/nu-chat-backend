package com.pulsechat.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document("users")
public class User {
    @Id private String id;
    @Indexed(unique = true) private String sessionKey;
    private String displayName;
    private Role role;
    private Instant createdAt;
    private Instant lastActiveAt;
}
