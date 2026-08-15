package com.pulsechat.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document("gameStates")
public class GameState {
    @Id private String roomId;
    private GameType gameType;
    private Map<String,Object> state;
    private Instant updatedAt;
}
