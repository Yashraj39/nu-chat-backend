package com.pulsechat.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document("gameRooms")
public class GameRoom {
    @Id private String id;
    @Indexed private GameType gameType;
    @Indexed private RoomStatus status;
    private String hostId;
    private List<Player> players;
    private Instant createdAt;
    private Instant updatedAt;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Player {
        private String userId;
        private String name;
        private String symbol;
    }
}
