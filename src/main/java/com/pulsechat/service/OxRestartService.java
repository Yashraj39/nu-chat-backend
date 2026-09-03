package com.pulsechat.service;

import com.pulsechat.model.GameRoom;
import com.pulsechat.model.GameState;
import com.pulsechat.model.GameType;
import com.pulsechat.model.RoomStatus;
import com.pulsechat.model.User;
import com.pulsechat.repo.GameRoomRepository;
import com.pulsechat.repo.GameStateRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OxRestartService {
    private final GameRoomRepository rooms;
    private final GameStateRepository states;

    public OxRestartService(GameRoomRepository rooms, GameStateRepository states) {
        this.rooms = rooms;
        this.states = states;
    }

    public synchronized GameState restart(User user, String roomId) {
        GameRoom room = rooms.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found."));

        if (room.getGameType() != GameType.OX) {
            throw new IllegalArgumentException("Restart is only available for Tic-Tac-Toe.");
        }

        boolean isPlayer = room.getPlayers() != null && room.getPlayers().stream()
                .anyMatch(player -> player.getUserId().equals(user.getId()));

        if (!isPlayer) {
            throw new SecurityException("Not a room player.");
        }

        if (room.getPlayers() == null || room.getPlayers().size() < 2) {
            throw new IllegalStateException("Both players must be in the room to restart the game.");
        }

        GameState gameState = states.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Game state not found."));

        Map<String, Object> state = new HashMap<>();
        state.put("board", new ArrayList<>(Arrays.asList("", "", "", "", "", "", "", "", "")));
        state.put("turn", "X");
        state.put("winner", null);

        gameState.setState(state);
        gameState.setGameType(GameType.OX);
        gameState.setUpdatedAt(Instant.now());
        GameState saved = states.save(gameState);

        room.setStatus(RoomStatus.PLAYING);
        room.setUpdatedAt(Instant.now());
        rooms.save(room);

        return saved;
    }
}
