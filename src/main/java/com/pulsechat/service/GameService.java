package com.pulsechat.service;

import com.pulsechat.model.*;
import com.pulsechat.repo.GameRoomRepository;
import com.pulsechat.repo.GameStateRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class GameService {

    private static final int SNAKE_BOARD_SIZE = 20;
    private static final int SNAKE_TICK_MS = 150;

    /* =========================================================
       LUDO CONFIGURATION
       ========================================================= */

    private static final int LUDO_MAX_PLAYERS = 4;
    private static final int LUDO_PIECES_PER_PLAYER = 4;
    private static final int LUDO_FINISH_PROGRESS = 57;

    private static final List<String> LUDO_COLORS =
            List.of("RED", "GREEN", "YELLOW", "BLUE");

    /*
     * Standard 15 x 15 Ludo main track. The values are the
     * board-cell indexes (row * 15 + column). 52 cells form
     * the shared loop. Each player starts every 13 cells apart.
     */
    private static final List<Integer> LUDO_MAIN_PATH = List.of(
            23, 38, 53, 68, 83,
            99, 100, 101, 102, 103, 104,
            119, 134, 133, 132, 131, 130, 129,
            143, 158, 173, 188, 203, 218, 217, 216,
            201, 186, 171, 156, 141,
            125, 124, 123, 122, 121, 120,
            105, 90, 91, 92, 93, 94, 95,
            81, 66, 51, 36, 21, 6, 7, 22
    );

    private static final Set<Integer> LUDO_SAFE_PATH_INDEXES = Set.of(
            0, 6, 13, 21, 26, 34, 39, 47
    );

    private final GameRoomRepository rooms;
    private final GameStateRepository states;
    private final SimpMessagingTemplate ws;

    public GameService(
            GameRoomRepository rooms,
            GameStateRepository states,
            SimpMessagingTemplate ws
    ) {
        this.rooms = rooms;
        this.states = states;
        this.ws = ws;
    }


    /* =========================================================
       ROOMS
       ========================================================= */

    public List<GameRoom> rooms() {

        return rooms
                .findTop50ByStatusInOrderByUpdatedAtDesc(
                        List.of(
                                RoomStatus.WAITING,
                                RoomStatus.PLAYING,
                                RoomStatus.STARTING
                        )
                )
                .stream()
                .filter(
                        room ->
                                room.getPlayers() != null &&
                                        !room.getPlayers().isEmpty()
                )
                .toList();
    }


    public GameRoom create(
            User user,
            GameType type
    ) {

        GameRoom room =
                GameRoom.builder()
                        .gameType(type)
                        .status(RoomStatus.WAITING)
                        .hostId(user.getId())
                        .players(
                                new ArrayList<>(
                                        List.of(
                                                GameRoom.Player.builder()
                                                        .userId(user.getId())
                                                        .name(user.getDisplayName())
                                                        .symbol(
                                                                type == GameType.OX
                                                                        ? "X"
                                                                        : type == GameType.LUDO
                                                                        ? LUDO_COLORS.get(0)
                                                                        : ""
                                                        )
                                                        .build()
                                        )
                                )
                        )
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        rooms.save(room);

        initialize(room);

        return room;
    }


    private void initialize(
            GameRoom room
    ) {

        Map<String, Object> state =
                new HashMap<>();


        /*
         * TIC TAC TOE
         */
        if (room.getGameType() == GameType.OX) {

            state.put(
                    "board",
                    new ArrayList<>(
                            Arrays.asList(
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    ""
                            )
                    )
            );

            state.put("turn", "X");
            state.put("winner", null);
        }


        /*
         * SNAKE
         */
        else if (room.getGameType() == GameType.SNAKE) {

            state.put(
                    "snakes",
                    new HashMap<String, Object>()
            );

            state.put(
                    "directions",
                    new HashMap<String, Object>()
            );

            state.put(
                    "scores",
                    new HashMap<String, Object>()
            );

            state.put(
                    "gameOvers",
                    new HashMap<String, Object>()
            );

            state.put(
                    "startedPlayers",
                    new HashMap<String, Object>()
            );

            state.put(
                    "food",
                    Arrays.asList(10, 10)
            );

            state.put("running", false);
            state.put("started", false);
            state.put("gameOver", false);
            state.put("score", 0);
            state.put("tick", 0);
        }


        /*
         * LUDO
         */
        else {

            state.put("turnIndex", 0);
            state.put("turnPlayerId", room.getPlayers().isEmpty()
                    ? null
                    : room.getPlayers().get(0).getUserId());
            state.put("playerOrder",
                    room.getPlayers().stream()
                            .map(GameRoom.Player::getUserId)
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
            state.put("started", false);
            state.put("winner", null);
            state.put("dice", 0);
            state.put("consecutiveSixes", 0);
            state.put("message", "Waiting for the host to start the match.");
            state.put("moveSequence", 0);
            state.put("lastMove", null);
            state.put("legalMoves", new HashMap<String, Object>());
            state.put("pieces",
                    new HashMap<String, Object>());

            Map<String, Object> pieces =
                    objectMap(state, "pieces");

            for (GameRoom.Player player : room.getPlayers()) {
                pieces.put(
                        player.getUserId(),
                        ludoNewPieces()
                );
            }
        }


        states.save(
                GameState.builder()
                        .roomId(room.getId())
                        .gameType(room.getGameType())
                        .state(state)
                        .updatedAt(Instant.now())
                        .build()
        );
    }


    public GameRoom join(
            User user,
            String id
    ) {

        GameRoom room =
                rooms.findById(id)
                        .orElseThrow(
                                () -> new NoSuchElementException(
                                        "Room not found."
                                )
                        );


        boolean alreadyPlayer =
                room.getPlayers()
                        .stream()
                        .anyMatch(
                                player ->
                                        player.getUserId()
                                                .equals(user.getId())
                        );

        if (alreadyPlayer) {
            if (room.getGameType() == GameType.LUDO) {
                syncLudoPlayersState(room);
            }
            return room;
        }

        if (room.getStatus() != RoomStatus.WAITING) {

            throw new IllegalStateException(
                    "Room is not accepting players."
            );
        }


        int maxPlayers =
                room.getGameType() == GameType.OX
                        ? 2
                        : 4;


        if (room.getPlayers().size() >= maxPlayers) {

            throw new IllegalStateException(
                    "Room is full."
            );
        }


        String symbol =
                room.getGameType() == GameType.OX
                        ? (
                        room.getPlayers().isEmpty()
                                ? "X"
                                : "O"
                )
                        : room.getGameType() == GameType.LUDO
                        ? ludoNextColor(room)
                        : "";


        room.getPlayers().add(
                GameRoom.Player.builder()
                        .userId(user.getId())
                        .name(user.getDisplayName())
                        .symbol(symbol)
                        .build()
        );


        /*
         * OX requires exactly two players.
         *
         * Snake/Ludo can continue to exist in WAITING
         * until players start playing.
         */
        if (
                room.getGameType() == GameType.OX &&
                        room.getPlayers().size() >= maxPlayers
        ) {
            room.setStatus(RoomStatus.PLAYING);
        }


        room.setUpdatedAt(Instant.now());

        GameRoom saved = rooms.save(room);

        if (saved.getGameType() == GameType.LUDO) {
            syncLudoPlayersState(saved);
        }

        return saved;
    }


    public GameRoom leave(
            User user,
            String id
    ) {

        GameRoom room =
                rooms.findById(id)
                        .orElseThrow(
                                () -> new NoSuchElementException(
                                        "Room not found."
                                )
                        );

        room.getPlayers().removeIf(
                player ->
                        player.getUserId()
                                .equals(user.getId())
        );

        /*
         * Existing Snake cleanup.
         */
        if (room.getGameType() == GameType.SNAKE) {

            try {

                GameState gameState =
                        state(id);

                Map<String, Object> game =
                        gameState.getState();

                removeFromMap(
                        game,
                        "snakes",
                        user.getId()
                );

                removeFromMap(
                        game,
                        "directions",
                        user.getId()
                );

                removeFromMap(
                        game,
                        "scores",
                        user.getId()
                );

                removeFromMap(
                        game,
                        "gameOvers",
                        user.getId()
                );

                removeFromMap(
                        game,
                        "startedPlayers",
                        user.getId()
                );

                gameState.setUpdatedAt(Instant.now());

                states.save(gameState);

            } catch (Exception ignored) {
            }
        }

        /*
         * Existing Ludo cleanup.
         */
        if (room.getGameType() == GameType.LUDO) {

            try {

                GameState gameState =
                        state(id);

                Map<String, Object> game =
                        gameState.getState();

                Map<String, Object> pieces =
                        objectMap(game, "pieces");

                Map<String, Object> legalMoves =
                        objectMap(game, "legalMoves");

                pieces.remove(user.getId());
                legalMoves.remove(user.getId());

                List<String> order =
                        ludoPlayerOrder(
                                gameState,
                                room
                        );

                order.remove(user.getId());

                game.put("playerOrder", order);

                if (order.isEmpty()) {

                    game.put("turnIndex", 0);
                    game.put("turnPlayerId", null);

                } else {

                    String currentId =
                            String.valueOf(
                                    game.get("turnPlayerId")
                            );

                    if (
                            currentId.equals(user.getId()) ||
                                    !order.contains(currentId)
                    ) {

                        int nextIndex =
                                Math.floorMod(
                                        numberValue(
                                                game.get("turnIndex"),
                                                0
                                        ),
                                        order.size()
                                );

                        game.put(
                                "turnIndex",
                                nextIndex
                        );

                        game.put(
                                "turnPlayerId",
                                order.get(nextIndex)
                        );

                    } else {

                        int newIndex =
                                order.indexOf(currentId);

                        game.put(
                                "turnIndex",
                                Math.max(
                                        0,
                                        newIndex
                                )
                        );
                    }
                }

                game.put("dice", 0);
                game.put("consecutiveSixes", 0);
                game.put("legalMoves", legalMoves);
                game.put(
                        "message",
                        "A player left the match."
                );

                gameState.setUpdatedAt(Instant.now());

                states.save(gameState);

            } catch (Exception ignored) {
            }

            /*
             * Transfer host only when players remain.
             */
            if (!room.getPlayers().isEmpty()) {

                String oldHost =
                        room.getHostId();

                boolean hostStillPresent =
                        room.getPlayers()
                                .stream()
                                .anyMatch(
                                        p ->
                                                p.getUserId()
                                                        .equals(oldHost)
                                );

                if (!hostStillPresent) {

                    room.setHostId(
                            room.getPlayers()
                                    .get(0)
                                    .getUserId()
                    );
                }
            }
        }

        /*
         * ========================================================
         * IMPORTANT: EMPTY ROOM
         * ========================================================
         *
         * If nobody remains, permanently remove the room and its
         * persisted game state.
         */
        if (room.getPlayers().isEmpty()) {

            try {
                states.deleteById(id);
            } catch (Exception ignored) {
            }

            ws.convertAndSend(
                    "/topic/game/rooms/remove",
                    id
            );

            rooms.deleteById(id);

            return room;
        }

        /*
         * Existing non-empty room handling.
         */
        if (
                room.getGameType() == GameType.LUDO &&
                        room.getStatus() == RoomStatus.PLAYING &&
                        room.getPlayers().size() < 2
        ) {

            room.setStatus(
                    RoomStatus.FINISHED
            );

        } else if (
                room.getGameType() != GameType.LUDO &&
                        room.getStatus() == RoomStatus.PLAYING
        ) {

            room.setStatus(
                    RoomStatus.FINISHED
            );
        }

        room.setUpdatedAt(Instant.now());

        return rooms.save(room);
    }


    public GameRoom get(
            String id
    ) {

        return rooms.findById(id)
                .orElseThrow(
                        () -> new NoSuchElementException(
                                "Room not found."
                        )
                );
    }


    public GameState state(
            String id
    ) {

        return states.findById(id)
                .orElseThrow(
                        () -> new NoSuchElementException(
                                "Game state not found."
                        )
                );
    }


    /* =========================================================
       TIC TAC TOE
       ========================================================= */

    public synchronized GameState oxMove(
            User user,
            String roomId,
            int cell
    ) {

        GameRoom room =
                get(roomId);


        if (
                room.getGameType() !=
                        GameType.OX
        ) {

            throw new IllegalArgumentException(
                    "Not an OX room."
            );
        }


        GameRoom.Player player =
                room.getPlayers()
                        .stream()
                        .filter(
                                p ->
                                        p.getUserId()
                                                .equals(user.getId())
                        )
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new SecurityException(
                                                "Not a room player."
                                        )
                        );


        if (
                room.getStatus() !=
                        RoomStatus.PLAYING
        ) {

            throw new IllegalStateException(
                    "Game is not active."
            );
        }


        GameState gameState =
                state(roomId);


        Map<String, Object> state =
                gameState.getState();


        List<String> board =
                new ArrayList<>(
                        stringList(
                                state.get("board")
                        )
                );


        String turn =
                String.valueOf(
                        state.get("turn")
                );


        if (
                !turn.equals(
                        player.getSymbol()
                )
        ) {

            throw new IllegalStateException(
                    "It is not your turn."
            );
        }


        if (
                cell < 0 ||
                        cell > 8 ||
                        !board.get(cell).isEmpty()
        ) {

            throw new IllegalArgumentException(
                    "Invalid move."
            );
        }


        board.set(
                cell,
                turn
        );


        state.put(
                "board",
                board
        );


        String winner =
                winner(board);


        if (winner != null) {

            state.put(
                    "winner",
                    winner
            );

            room.setStatus(
                    RoomStatus.FINISHED
            );

        } else if (
                board.stream()
                        .allMatch(
                                value ->
                                        !value.isEmpty()
                        )
        ) {

            state.put(
                    "winner",
                    "DRAW"
            );

            room.setStatus(
                    RoomStatus.FINISHED
            );

        } else {

            state.put(
                    "turn",
                    turn.equals("X")
                            ? "O"
                            : "X"
            );
        }


        gameState.setUpdatedAt(
                Instant.now()
        );

        states.save(
                gameState
        );

        room.setUpdatedAt(
                Instant.now()
        );

        rooms.save(
                room
        );


        return gameState;
    }


    private String winner(
            List<String> board
    ) {

        int[][] winningLines = {
                {0, 1, 2},
                {3, 4, 5},
                {6, 7, 8},
                {0, 3, 6},
                {1, 4, 7},
                {2, 5, 8},
                {0, 4, 8},
                {2, 4, 6}
        };


        for (
                int[] line :
                winningLines
        ) {

            if (
                    !board.get(line[0]).isEmpty() &&
                            board.get(line[0])
                                    .equals(board.get(line[1])) &&
                            board.get(line[1])
                                    .equals(board.get(line[2]))
            ) {

                return board.get(line[0]);
            }
        }


        return null;
    }


    /* =========================================================
       GAME ACTION
       ========================================================= */

    public synchronized GameState genericAction(
            User user,
            String roomId,
            String action,
            Object payload
    ) {

        GameRoom room =
                get(roomId);


        boolean isPlayer =
                room.getPlayers()
                        .stream()
                        .anyMatch(
                                player ->
                                        player.getUserId()
                                                .equals(user.getId())
                        );


        if (!isPlayer) {

            throw new SecurityException(
                    "Not a room player."
            );
        }


        /*
         * OX
         */
        if (
                room.getGameType() ==
                        GameType.OX
        ) {

            if (!"move".equals(action)) {

                throw new IllegalArgumentException(
                        "Invalid Tic-Tac-Toe action."
                );
            }


            return oxMove(
                    user,
                    roomId,
                    Integer.parseInt(
                            String.valueOf(payload)
                    )
            );
        }


        /*
         * SNAKE
         */
        if (
                room.getGameType() ==
                        GameType.SNAKE
        ) {

            return snakeAction(
                    user,
                    roomId,
                    action,
                    payload
            );
        }


        /*
         * LUDO
         */
        return ludoAction(
                user,
                roomId,
                action,
                payload
        );
    }


    /* =========================================================
       SNAKE ACTIONS
       ========================================================= */

    private GameState snakeAction(
            User user,
            String roomId,
            String action,
            Object payload
    ) {

        GameState gameState =
                state(roomId);


        Map<String, Object> state =
                gameState.getState();


        Map<String, Object> snakes =
                objectMap(
                        state,
                        "snakes"
                );


        Map<String, Object> directions =
                objectMap(
                        state,
                        "directions"
                );


        Map<String, Object> scores =
                objectMap(
                        state,
                        "scores"
                );


        Map<String, Object> gameOvers =
                objectMap(
                        state,
                        "gameOvers"
                );


        Map<String, Object> startedPlayers =
                objectMap(
                        state,
                        "startedPlayers"
                );


        String userId =
                user.getId();


        /*
         * START / RESTART
         */
        if ("start".equalsIgnoreCase(action)) {

            GameRoom room =
                    get(roomId);


            int playerIndex =
                    0;


            for (
                    int i = 0;
                    i < room.getPlayers().size();
                    i++
            ) {

                if (
                        room.getPlayers()
                                .get(i)
                                .getUserId()
                                .equals(userId)
                ) {

                    playerIndex = i;
                    break;
                }
            }


            String direction =
                    String.valueOf(
                            directions.getOrDefault(
                                    userId,
                                    initialDirection(playerIndex)
                            )
                    );


            if (
                    !isDirection(direction)
            ) {

                direction =
                        initialDirection(
                                playerIndex
                        );
            }


            snakes.put(
                    userId,
                    createInitialSnake(
                            playerIndex,
                            direction
                    )
            );


            directions.put(
                    userId,
                    direction
            );


            scores.put(
                    userId,
                    0
            );


            gameOvers.put(
                    userId,
                    false
            );


            startedPlayers.put(
                    userId,
                    true
            );


            state.put(
                    "running",
                    true
            );

            state.put(
                    "started",
                    true
            );

            state.put(
                    "gameOver",
                    false
            );

            state.put(
                    "score",
                    0
            );


            /*
             * Make sure food exists.
             */
            if (
                    !validPoint(
                            state.get("food")
                    )
            ) {

                state.put(
                        "food",
                        generateFood(
                                snakes
                        )
                );

            } else {

                List<Integer> food =
                        toPoint(
                                state.get("food")
                        );

                if (
                        food != null &&
                                isOccupied(
                                        food.get(0),
                                        food.get(1),
                                        snakes
                                )
                ) {

                    state.put(
                            "food",
                            generateFood(
                                    snakes
                            )
                    );
                }
            }
        }


        /*
         * CHANGE DIRECTION
         */
        else if (
                "direction".equalsIgnoreCase(action)
        ) {

            String next =
                    String.valueOf(
                                    payload
                            )
                            .trim()
                            .toUpperCase();


            if (
                    !isDirection(next)
            ) {

                throw new IllegalArgumentException(
                        "Invalid snake direction."
                );
            }


            String current =
                    String.valueOf(
                            directions.getOrDefault(
                                    userId,
                                    "RIGHT"
                            )
                    );


            /*
             * Prevent instant 180-degree turns.
             */
            if (
                    !isOpposite(
                            current,
                            next
                    )
            ) {

                directions.put(
                        userId,
                        next
                );
            }
        }


        else {

            throw new IllegalArgumentException(
                    "Unknown Snake action."
            );
        }


        gameState.setUpdatedAt(
                Instant.now()
        );


        states.save(
                gameState
        );


        return gameState;
    }


    /* =========================================================
       SERVER-SIDE SNAKE GAME LOOP
       ========================================================= */

    @Scheduled(
            fixedRate = SNAKE_TICK_MS
    )
    public synchronized void tickSnakeGames() {

        List<GameRoom> gameRooms =
                rooms.findTop50ByStatusInOrderByUpdatedAtDesc(
                        List.of(
                                RoomStatus.WAITING,
                                RoomStatus.STARTING,
                                RoomStatus.PLAYING
                        )
                );


        for (
                GameRoom room :
                gameRooms
        ) {

            if (
                    room.getGameType() !=
                            GameType.SNAKE
            ) {
                continue;
            }


            try {

                tickSnakeRoom(
                        room
                );

            } catch (Exception ignored) {

                /*
                 * One broken room must never stop
                 * the global scheduler.
                 */
            }
        }
    }


    private void tickSnakeRoom(
            GameRoom room
    ) {

        GameState gameState;


        try {

            gameState =
                    state(room.getId());

        } catch (Exception e) {

            return;
        }


        Map<String, Object> state =
                gameState.getState();


        if (
                !Boolean.TRUE.equals(
                        state.get("running")
                )
        ) {

            return;
        }


        Map<String, Object> snakes =
                objectMap(
                        state,
                        "snakes"
                );


        Map<String, Object> directions =
                objectMap(
                        state,
                        "directions"
                );


        Map<String, Object> scores =
                objectMap(
                        state,
                        "scores"
                );


        Map<String, Object> gameOvers =
                objectMap(
                        state,
                        "gameOvers"
                );


        Map<String, Object> startedPlayers =
                objectMap(
                        state,
                        "startedPlayers"
                );


        List<Integer> food =
                toPoint(
                        state.get("food")
                );


        if (food == null) {

            food =
                    toPoint(
                            generateFood(
                                    snakes
                            )
                    );

            state.put(
                    "food",
                    food
            );
        }


        /*
         * Determine all next heads first.
         *
         * This lets us detect head-to-head collisions
         * correctly.
         */
        Map<String, List<Integer>> nextHeads =
                new HashMap<>();


        for (
                GameRoom.Player player :
                room.getPlayers()
        ) {

            String userId =
                    player.getUserId();


            if (
                    !Boolean.TRUE.equals(
                            startedPlayers.get(userId)
                    )
            ) {
                continue;
            }


            if (
                    Boolean.TRUE.equals(
                            gameOvers.get(userId)
                    )
            ) {
                continue;
            }


            List<List<Integer>> snake =
                    toSnake(
                            snakes.get(userId)
                    );


            if (snake.isEmpty()) {
                continue;
            }


            List<Integer> head =
                    snake.get(0);


            String direction =
                    String.valueOf(
                            directions.getOrDefault(
                                    userId,
                                    "RIGHT"
                            )
                    );


            List<Integer> next =
                    movePoint(
                            head,
                            direction
                    );


            /*
             * WRAP AROUND THE BOARD
             *
             * Left  -> Right
             * Right -> Left
             * Top   -> Bottom
             * Bottom -> Top
             *
             * All players continue playing on
             * the same shared board.
             */
            next.set(
                    0,
                    Math.floorMod(
                            next.get(0),
                            SNAKE_BOARD_SIZE
                    )
            );

            next.set(
                    1,
                    Math.floorMod(
                            next.get(1),
                            SNAKE_BOARD_SIZE
                    )
            );


            nextHeads.put(
                    userId,
                    next
            );
        }


        /*
         * Check collisions and move each snake.
         */
        Set<String> eatenFoodBy =
                new HashSet<>();


        for (
                GameRoom.Player player :
                room.getPlayers()
        ) {

            String userId =
                    player.getUserId();


            if (
                    !nextHeads.containsKey(
                            userId
                    )
            ) {
                continue;
            }


            if (
                    Boolean.TRUE.equals(
                            gameOvers.get(userId)
                    )
            ) {
                continue;
            }


            List<List<Integer>> snake =
                    toSnake(
                            snakes.get(userId)
                    );


            List<Integer> nextHead =
                    nextHeads.get(userId);


            int nextX =
                    nextHead.get(0);

            int nextY =
                    nextHead.get(1);


            /*
             * No wall collision anymore.
             *
             * The coordinates were already wrapped
             * above, so every next position is inside
             * the shared 20x20 board.
             */


            /*
             * FOOD
             */
            boolean ateFood =
                    food != null &&
                            nextX == food.get(0) &&
                            nextY == food.get(1);


            /*
             * SELF COLLISION
             */
            if (
                    hitsOwnSnake(
                            nextHead,
                            snake,
                            ateFood
                    )
            ) {

                gameOvers.put(
                        userId,
                        true
                );

                continue;
            }


            /*
             * OTHER SNAKE COLLISION
             */
            if (
                    hitsOtherSnake(
                            userId,
                            nextHead,
                            snakes
                    )
            ) {

                gameOvers.put(
                        userId,
                        true
                );

                continue;
            }


            /*
             * HEAD-TO-HEAD COLLISION
             */
            if (
                    isDuplicateHead(
                            userId,
                            nextHead,
                            nextHeads
                    )
            ) {

                gameOvers.put(
                        userId,
                        true
                );

                continue;
            }


            /*
             * Move the snake.
             */
            List<List<Integer>> newSnake =
                    new ArrayList<>();


            newSnake.add(
                    new ArrayList<>(
                            nextHead
                    )
            );


            newSnake.addAll(
                    snake
            );


            /*
             * If food wasn't eaten,
             * remove the tail.
             */
            if (!ateFood) {

                newSnake.remove(
                        newSnake.size() - 1
                );

            } else {

                /*
                 * Increase score.
                 */
                int oldScore =
                        numberValue(
                                scores.get(
                                        userId
                                ),
                                0
                        );


                scores.put(
                        userId,
                        oldScore + 1
                );


                eatenFoodBy.add(
                        userId
                );
            }


            snakes.put(
                    userId,
                    newSnake
            );
        }


        /*
         * Generate new food if someone ate it.
         */
        if (
                !eatenFoodBy.isEmpty()
        ) {

            state.put(
                    "food",
                    generateFood(
                            snakes
                    )
            );
        }


        /*
         * Determine whether at least one
         * player is still alive.
         */
        boolean someoneRunning =
                false;


        for (
                GameRoom.Player player :
                room.getPlayers()
        ) {

            String userId =
                    player.getUserId();


            if (
                    Boolean.TRUE.equals(
                            startedPlayers.get(userId)
                    ) &&
                            !Boolean.TRUE.equals(
                                    gameOvers.get(userId)
                            ) &&
                            snakes.containsKey(userId)
            ) {

                someoneRunning = true;
                break;
            }
        }


        state.put(
                "running",
                someoneRunning
        );


        /*
         * Global values retained for compatibility
         * with older frontend state.
         */
        state.put(
                "started",
                !startedPlayers.isEmpty()
        );


        state.put(
                "gameOver",
                !someoneRunning &&
                        !startedPlayers.isEmpty()
        );


        state.put(
                "tick",
                numberValue(
                        state.get("tick"),
                        0
                ) + 1
        );


        gameState.setUpdatedAt(
                Instant.now()
        );


        states.save(
                gameState
        );


        /*
         * Publish the updated game state.
         *
         * This is what makes the snake visibly move
         * in React for every player in the same room.
         */
        ws.convertAndSend(
                "/topic/game/" +
                        room.getId() +
                        "/state",
                gameState
        );
    }


    /* =========================================================
       SNAKE HELPERS
       ========================================================= */

    private String initialDirection(
            int playerIndex
    ) {

        return switch (
                playerIndex % 4
                ) {

            case 1, 3 -> "LEFT";

            default -> "RIGHT";
        };
    }


    private List<List<Integer>> createInitialSnake(
            int playerIndex,
            String direction
    ) {

        int[][] starts = {
                {3, 3},
                {16, 3},
                {3, 16},
                {16, 16}
        };


        int index =
                playerIndex % starts.length;


        int headX =
                starts[index][0];

        int headY =
                starts[index][1];


        List<List<Integer>> snake =
                new ArrayList<>();


        snake.add(
                point(
                        headX,
                        headY
                )
        );


        switch (direction) {

            case "LEFT" -> {

                snake.add(
                        point(
                                headX + 1,
                                headY
                        )
                );

                snake.add(
                        point(
                                headX + 2,
                                headY
                        )
                );
            }


            case "UP" -> {

                snake.add(
                        point(
                                headX,
                                headY + 1
                        )
                );

                snake.add(
                        point(
                                headX,
                                headY + 2
                        )
                );
            }


            case "DOWN" -> {

                snake.add(
                        point(
                                headX,
                                headY - 1
                        )
                );

                snake.add(
                        point(
                                headX,
                                headY - 2
                        )
                );
            }


            default -> {

                snake.add(
                        point(
                                headX - 1,
                                headY
                        )
                );

                snake.add(
                        point(
                                headX - 2,
                                headY
                        )
                );
            }
        }


        return snake;
    }


    private List<Integer> movePoint(
            List<Integer> point,
            String direction
    ) {

        int x =
                point.get(0);

        int y =
                point.get(1);


        switch (direction) {

            case "UP":
                y--;
                break;

            case "DOWN":
                y++;
                break;

            case "LEFT":
                x--;
                break;

            case "RIGHT":
            default:
                x++;
                break;
        }


        return point(
                x,
                y
        );
    }


    private List<Integer> point(
            int x,
            int y
    ) {

        return new ArrayList<>(
                Arrays.asList(
                        x,
                        y
                )
        );
    }


    private boolean hitsOwnSnake(
            List<Integer> head,
            List<List<Integer>> snake,
            boolean growing
    ) {

        int limit =
                growing
                        ? snake.size()
                        : Math.max(
                        0,
                        snake.size() - 1
                );


        for (
                int i = 0;
                i < limit;
                i++
        ) {

            if (
                    samePoint(
                            head,
                            snake.get(i)
                    )
            ) {

                return true;
            }
        }


        return false;
    }


    private boolean hitsOtherSnake(
            String userId,
            List<Integer> head,
            Map<String, Object> snakes
    ) {

        for (
                Map.Entry<String, Object> entry :
                snakes.entrySet()
        ) {

            if (
                    entry.getKey()
                            .equals(userId)
            ) {
                continue;
            }


            List<List<Integer>> otherSnake =
                    toSnake(
                            entry.getValue()
                    );


            for (
                    List<Integer> segment :
                    otherSnake
            ) {

                if (
                        samePoint(
                                head,
                                segment
                        )
                ) {

                    return true;
                }
            }
        }


        return false;
    }


    private boolean isDuplicateHead(
            String userId,
            List<Integer> head,
            Map<String, List<Integer>> heads
    ) {

        for (
                Map.Entry<String, List<Integer>> entry :
                heads.entrySet()
        ) {

            if (
                    entry.getKey()
                            .equals(userId)
            ) {
                continue;
            }


            if (
                    samePoint(
                            head,
                            entry.getValue()
                    )
            ) {

                return true;
            }
        }


        return false;
    }


    private boolean samePoint(
            List<Integer> a,
            List<Integer> b
    ) {

        return a != null &&
                b != null &&
                a.size() >= 2 &&
                b.size() >= 2 &&
                Objects.equals(
                        a.get(0),
                        b.get(0)
                ) &&
                Objects.equals(
                        a.get(1),
                        b.get(1)
                );
    }


    private boolean isDirection(
            String direction
    ) {

        return "UP".equals(direction) ||
                "DOWN".equals(direction) ||
                "LEFT".equals(direction) ||
                "RIGHT".equals(direction);
    }


    private boolean isOpposite(
            String current,
            String next
    ) {

        return (
                "UP".equals(current) &&
                        "DOWN".equals(next)
        ) ||
                (
                        "DOWN".equals(current) &&
                                "UP".equals(next)
                ) ||
                (
                        "LEFT".equals(current) &&
                                "RIGHT".equals(next)
                ) ||
                (
                        "RIGHT".equals(current) &&
                                "LEFT".equals(next)
                );
    }


    private List<Integer> generateFood(
            Map<String, Object> snakes
    ) {

        List<Integer> food;


        for (
                int attempt = 0;
                attempt < 500;
                attempt++
        ) {

            int x =
                    (int)
                            (
                                    Math.random()
                                            * SNAKE_BOARD_SIZE
                            );


            int y =
                    (int)
                            (
                                    Math.random()
                                            * SNAKE_BOARD_SIZE
                            );


            if (
                    !isOccupied(
                            x,
                            y,
                            snakes
                    )
            ) {

                food =
                        point(
                                x,
                                y
                        );

                return food;
            }
        }


        /*
         * Fallback.
         */
        return point(
                10,
                10
        );
    }


    private boolean isOccupied(
            int x,
            int y,
            Map<String, Object> snakes
    ) {

        for (
                Object rawSnake :
                snakes.values()
        ) {

            List<List<Integer>> snake =
                    toSnake(
                            rawSnake
                    );


            for (
                    List<Integer> segment :
                    snake
            ) {

                if (
                        segment.size() >= 2 &&
                                segment.get(0) == x &&
                                segment.get(1) == y
                ) {

                    return true;
                }
            }
        }


        return false;
    }


    /* =========================================================
       LUDO
       ========================================================= */

    private GameState ludoAction(
            User user,
            String roomId,
            String action,
            Object payload
    ) {

        GameRoom room = get(roomId);
        GameState gameState = state(roomId);
        Map<String, Object> state = gameState.getState();

        syncLudoPlayersState(room);

        List<String> playerOrder =
                ludoPlayerOrder(gameState, room);

        int playerIndex =
                playerOrder.indexOf(user.getId());

        if (playerIndex < 0) {
            throw new SecurityException("Not a room player.");
        }

        Map<String, Object> pieces =
                objectMap(state, "pieces");

        Map<String, Object> legalMoves =
                objectMap(state, "legalMoves");

        if ("start".equalsIgnoreCase(action)) {
            ludoStart(user, room, gameState);
        } else {
            boolean started = Boolean.TRUE.equals(state.get("started"));

            if (!started || room.getStatus() != RoomStatus.PLAYING) {
                throw new IllegalStateException(
                        "The Ludo match has not started yet."
                );
            }

            String turnPlayerId =
                    String.valueOf(state.get("turnPlayerId"));

            if (!user.getId().equals(turnPlayerId)) {
                throw new IllegalStateException("It is not your turn.");
            }

            switch (action.toLowerCase(Locale.ROOT)) {
                case "roll" -> ludoRoll(user, room, gameState);
                case "move" -> ludoMove(user, room, gameState, payload);
                case "pass" -> ludoPass(user, room, gameState);
                default -> throw new IllegalArgumentException(
                        "Unknown Ludo action."
                );
            }
        }

        gameState.setUpdatedAt(Instant.now());
        GameState saved = states.save(gameState);
        room.setUpdatedAt(Instant.now());
        rooms.save(room);
        return saved;
    }


    private void ludoStart(
            User user,
            GameRoom room,
            GameState gameState
    ) {

        if (!room.getHostId().equals(user.getId())) {
            throw new SecurityException("Only the room host can start Ludo.");
        }

        if (room.getPlayers().size() < 2) {
            throw new IllegalStateException(
                    "Ludo needs at least 2 players to start."
            );
        }

        Map<String, Object> state = gameState.getState();

        List<String> order = room.getPlayers().stream()
                .map(GameRoom.Player::getUserId)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Map<String, Object> pieces = objectMap(state, "pieces");
        Map<String, Object> legalMoves = objectMap(state, "legalMoves");

        pieces.clear();
        legalMoves.clear();

        for (String playerId : order) {
            pieces.put(playerId, ludoNewPieces());
        }

        state.put("playerOrder", order);
        state.put("turnIndex", 0);
        state.put("turnPlayerId", order.get(0));
        state.put("started", true);
        state.put("winner", null);
        state.put("dice", 0);
        state.put("consecutiveSixes", 0);
        state.put("message", "Game started. " + ludoPlayerName(room, order.get(0)) + " rolls first.");
        state.put("moveSequence", 0);
        state.put("lastMove", null);

        room.setStatus(RoomStatus.PLAYING);
    }


    private void ludoRoll(
            User user,
            GameRoom room,
            GameState gameState
    ) {

        Map<String, Object> state =
                gameState.getState();

        int existingDice =
                numberValue(
                        state.get("dice"),
                        0
                );

        /*
         * A dice value already exists when the player has
         * rolled but has not selected a token yet.
         */
        if (existingDice != 0) {

            throw new IllegalStateException(
                    "Choose a highlighted piece first."
            );
        }

        int consecutiveSixes =
                numberValue(
                        state.get("consecutiveSixes"),
                        0
                );

        int dice =
                1 + new Random().nextInt(6);

        /*
         * Track consecutive sixes.
         */
        if (dice == 6) {
            consecutiveSixes++;
        } else {
            consecutiveSixes = 0;
        }

        /*
         * Three consecutive sixes:
         *
         * - roll is cancelled
         * - turn is lost
         * - next player gets the turn
         */
        if (consecutiveSixes >= 3) {

            state.put(
                    "dice",
                    0
            );

            state.put(
                    "consecutiveSixes",
                    0
            );

            objectMap(
                    state,
                    "legalMoves"
            ).clear();

            String currentPlayer =
                    ludoPlayerName(
                            room,
                            user.getId()
                    );

            ludoAdvanceTurn(
                    room,
                    state
            );

            state.put(
                    "message",
                    currentPlayer +
                            " rolled three 6s. Turn lost. " +
                            ludoPlayerName(
                                    room,
                                    String.valueOf(
                                            state.get(
                                                    "turnPlayerId"
                                            )
                                    )
                            ) +
                            " is up."
            );

            return;
        }

        /*
         * Store the roll temporarily.
         */
        state.put(
                "dice",
                dice
        );

        state.put(
                "consecutiveSixes",
                consecutiveSixes
        );

        state.put(
                "lastMove",
                null
        );

        /*
         * Find every token that can legally move.
         */
        List<Integer> moves =
                ludoLegalMoves(
                        room,
                        state,
                        user.getId(),
                        dice
                );

        Map<String, Object> legalMoves =
                objectMap(
                        state,
                        "legalMoves"
                );

        legalMoves.clear();

        /*
         * ========================================================
         * NO LEGAL MOVE
         * ========================================================
         *
         * This was the bug.
         *
         * Previously dice remained non-zero here, which made
         * the Roll button disabled forever while there was no
         * token that could actually be selected.
         *
         * Now the server immediately resolves the turn.
         */
        if (moves.isEmpty()) {

            state.put(
                    "dice",
                    0
            );

            legalMoves.clear();

            String playerName =
                    ludoPlayerName(
                            room,
                            user.getId()
                    );

            /*
             * A six normally grants another roll.
             *
             * Even if the player cannot move anything with
             * that six, they still receive their next roll.
             */
            if (dice == 6) {

                state.put(
                        "message",
                        playerName +
                                " rolled 6 but has no legal move. " +
                                "Roll again."
                );

                /*
                 * Keep consecutiveSixes.
                 *
                 * This is important because another 6 immediately
                 * afterward must count toward the three-six rule.
                 */

            } else {

                /*
                 * Any non-six with no legal move ends the turn.
                 */
                state.put(
                        "consecutiveSixes",
                        0
                );

                ludoAdvanceTurn(
                        room,
                        state
                );

                state.put(
                        "message",
                        playerName +
                                " rolled " +
                                dice +
                                " but has no legal move. " +
                                ludoPlayerName(
                                        room,
                                        String.valueOf(
                                                state.get(
                                                        "turnPlayerId"
                                                )
                                        )
                                ) +
                                " is up."
                );
            }

            return;
        }

        /*
         * ========================================================
         * LEGAL MOVES EXIST
         * ========================================================
         */

        legalMoves.put(
                user.getId(),
                new ArrayList<>(
                        moves
                )
        );

        state.put(
                "message",
                ludoPlayerName(
                        room,
                        user.getId()
                ) +
                        " rolled " +
                        dice +
                        ". Choose a token."
        );
    }


    private void ludoPass(
            User user,
            GameRoom room,
            GameState gameState
    ) {

        Map<String, Object> state = gameState.getState();
        int dice = numberValue(state.get("dice"), 0);

        if (dice == 0) {
            throw new IllegalStateException("There is no roll to pass.");
        }

        List<Integer> moves =
                ludoLegalMoves(room, state, user.getId(), dice);

        if (!moves.isEmpty()) {
            throw new IllegalStateException("A legal move is available.");
        }

        state.put("dice", 0);
        state.put("consecutiveSixes", 0);

        if (dice == 6) {
            state.put(
                    "message",
                    ludoPlayerName(room, user.getId()) +
                            " gets another roll."
            );
        } else {
            ludoAdvanceTurn(room, state);
            state.put(
                    "message",
                    "No legal move. " +
                            ludoPlayerName(room, String.valueOf(state.get("turnPlayerId"))) +
                            " is up."
            );
        }

        objectMap(state, "legalMoves").clear();
    }


    private void ludoMove(
            User user,
            GameRoom room,
            GameState gameState,
            Object payload
    ) {

        Map<String, Object> state = gameState.getState();
        int dice = numberValue(state.get("dice"), 0);

        if (dice == 0) {
            throw new IllegalStateException("Roll the dice first.");
        }

        int pieceIndex = numberValue(payload, -1);
        List<Integer> legal = ludoLegalMoves(room, state, user.getId(), dice);

        if (!legal.contains(pieceIndex)) {
            throw new IllegalArgumentException("That token cannot move with this roll.");
        }

        Map<String, Object> pieces = objectMap(state, "pieces");
        List<Integer> ownPieces = ludoPieces(pieces, user.getId());
        int from = ownPieces.get(pieceIndex);
        int to = from == -1 ? 0 : from + dice;

        List<String> captures = ludoApplyCapture(
                room,
                state,
                user.getId(),
                to
        );

        ownPieces.set(pieceIndex, to);
        pieces.put(user.getId(), ownPieces);

        int moveSequence = numberValue(state.get("moveSequence"), 0) + 1;
        Map<String, Object> lastMove = new LinkedHashMap<>();
        lastMove.put("moveSequence", moveSequence);
        lastMove.put("playerId", user.getId());
        lastMove.put("pieceIndex", pieceIndex);
        lastMove.put("from", from);
        lastMove.put("to", to);
        lastMove.put("captures", captures);
        state.put("moveSequence", moveSequence);
        state.put("lastMove", lastMove);

        objectMap(state, "legalMoves").clear();
        state.put("dice", 0);

        boolean reachedFinish = to == LUDO_FINISH_PROGRESS;
        boolean won = ownPieces.stream()
                .allMatch(position -> position == LUDO_FINISH_PROGRESS);

        if (won) {
            state.put("winner", user.getId());
            state.put("message", ludoPlayerName(room, user.getId()) + " won the match!");
            room.setStatus(RoomStatus.FINISHED);
            return;
        }

        boolean extraTurn =
                dice == 6 ||
                        !captures.isEmpty() ||
                        reachedFinish;

        if (extraTurn) {
            state.put(
                    "message",
                    ludoPlayerName(room, user.getId()) +
                            " gets another turn."
            );
        } else {
            state.put("consecutiveSixes", 0);
            ludoAdvanceTurn(room, state);
            state.put(
                    "message",
                    ludoPlayerName(room, String.valueOf(state.get("turnPlayerId"))) +
                            " is up."
            );
        }
    }


    private List<Integer> ludoLegalMoves(
            GameRoom room,
            Map<String, Object> state,
            String userId,
            int dice
    ) {

        if (dice < 1 || dice > 6) {
            return new ArrayList<>();
        }

        Map<String, Object> pieces = objectMap(state, "pieces");
        List<Integer> ownPieces = ludoPieces(pieces, userId);
        List<Integer> result = new ArrayList<>();

        for (int pieceIndex = 0; pieceIndex < ownPieces.size(); pieceIndex++) {
            if (ludoCanMove(room, state, userId, ownPieces.get(pieceIndex), dice)) {
                result.add(pieceIndex);
            }
        }

        return result;
    }


    private boolean ludoCanMove(
            GameRoom room,
            Map<String, Object> state,
            String userId,
            int position,
            int dice
    ) {

        if (position == LUDO_FINISH_PROGRESS) {
            return false;
        }

        if (position == -1) {
            if (dice != 6) {
                return false;
            }
            return !ludoBlockedByOpponents(room, state, userId, 0, 0);
        }

        int target = position + dice;

        if (target > LUDO_FINISH_PROGRESS) {
            return false;
        }

        int fromCommon = Math.max(0, position + 1);
        int toCommon = Math.min(target, 51);

        if (fromCommon <= toCommon &&
                ludoBlockedByOpponents(room, state, userId, fromCommon, toCommon)) {
            return false;
        }

        if (target <= 51) {
            int globalIndex = ludoGlobalIndex(userId, target, room, state);
            int opponentCount = ludoOpponentCountAt(
                    room,
                    state,
                    userId,
                    globalIndex
            );

            if (opponentCount >= 2) {
                return false;
            }
        }

        return true;
    }


    private boolean ludoBlockedByOpponents(
            GameRoom room,
            Map<String, Object> state,
            String userId,
            int fromProgress,
            int toProgress
    ) {

        for (int progress = fromProgress; progress <= toProgress; progress++) {
            int globalIndex = ludoGlobalIndex(userId, progress, room, state);
            int opponents = ludoOpponentCountAt(
                    room,
                    state,
                    userId,
                    globalIndex
            );

            if (opponents >= 2) {
                return true;
            }
        }

        return false;
    }


    private int ludoGlobalIndex(
            String userId,
            int progress,
            GameRoom room,
            Map<String, Object> state
    ) {

        int seatIndex =
                ludoSeatIndex(
                        room,
                        userId
                );

        /*
         * Board coordinate system:
         *
         * RED    -> path 0
         * GREEN  -> path 13
         * YELLOW -> path 26
         * BLUE   -> path 39
         *
         * This MUST match the React boardPosition()
         * implementation.
         */
        return Math.floorMod(
                seatIndex * 13 + progress,
                52
        );
    }

    private int ludoOpponentCountAt(
            GameRoom room,
            Map<String, Object> state,
            String userId,
            int globalIndex
    ) {

        if (globalIndex < 0 || globalIndex >= LUDO_MAIN_PATH.size()) {
            return 0;
        }

        int count = 0;
        Map<String, Object> pieces = objectMap(state, "pieces");

        for (GameRoom.Player player : room.getPlayers()) {
            if (player.getUserId().equals(userId)) {
                continue;
            }

            List<Integer> opponentPieces = ludoPieces(pieces, player.getUserId());
            int playerIndex = ludoSeatIndex(room, player.getUserId());
            int startIndex =
                    playerIndex * 13;

            for (Integer progress : opponentPieces) {
                if (progress != null && progress >= 0 && progress <= 51) {
                    int opponentGlobal = Math.floorMod(startIndex + progress, 52);
                    if (opponentGlobal == globalIndex) {
                        count++;
                    }
                }
            }
        }

        return count;
    }


    private List<String> ludoApplyCapture(
            GameRoom room,
            Map<String, Object> state,
            String userId,
            int targetProgress
    ) {

        List<String> captures = new ArrayList<>();

        if (targetProgress < 0 || targetProgress > 51) {
            return captures;
        }

        int globalIndex = ludoGlobalIndex(userId, targetProgress, room, state);

        if (
                LUDO_SAFE_PATH_INDEXES.contains(
                        globalIndex
                )
        ) {
            return captures;
        }

        Map<String, Object> pieces = objectMap(state, "pieces");

        for (GameRoom.Player player : room.getPlayers()) {
            if (player.getUserId().equals(userId)) {
                continue;
            }

            List<Integer> opponentPieces = ludoPieces(pieces, player.getUserId());
            int playerIndex = ludoSeatIndex(room, player.getUserId());
            int startIndex =
                    playerIndex * 13;
            boolean capturedAny = false;

            for (int i = 0; i < opponentPieces.size(); i++) {
                Integer progress = opponentPieces.get(i);
                if (progress != null && progress >= 0 && progress <= 51) {
                    int opponentGlobal = Math.floorMod(startIndex + progress, 52);
                    if (opponentGlobal == globalIndex) {
                        opponentPieces.set(i, -1);
                        capturedAny = true;
                    }
                }
            }

            if (capturedAny) {
                captures.add(player.getUserId());
                pieces.put(player.getUserId(), opponentPieces);
            }
        }

        return captures;
    }

    private void ludoAdvanceTurn(
            GameRoom room,
            Map<String, Object> state
    ) {

        List<String> order =
                ludoPlayerOrder(
                        state,
                        room
                );

        if (order.isEmpty()) {
            state.put(
                    "turnIndex",
                    0
            );

            state.put(
                    "turnPlayerId",
                    null
            );

            state.put(
                    "dice",
                    0
            );

            state.put(
                    "legalMoves",
                    new HashMap<String, Object>()
            );

            return;
        }

        int currentIndex =
                numberValue(
                        state.get("turnIndex"),
                        0
                );

        /*
         * Move to the next player.
         *
         * Math.floorMod() keeps the index safe even if
         * the current index is negative or larger than
         * the current player count after someone leaves.
         */
        int nextIndex =
                Math.floorMod(
                        currentIndex + 1,
                        order.size()
                );

        state.put(
                "turnIndex",
                nextIndex
        );

        state.put(
                "turnPlayerId",
                order.get(nextIndex)
        );

        /*
         * A new turn always starts with no dice rolled
         * and no pending token selection.
         */
        state.put(
                "dice",
                0
        );

        state.put(
                "legalMoves",
                new HashMap<String, Object>()
        );
    }

    private List<String> ludoPlayerOrder(
            Map<String, Object> state,
            GameRoom room
    ) {

        Object raw = state.get("playerOrder");

        List<String> order = new ArrayList<>();

        if (raw instanceof List<?> list) {

            for (Object value : list) {

                if (value == null) {
                    continue;
                }

                String id = String.valueOf(value);

                boolean exists =
                        room.getPlayers()
                                .stream()
                                .anyMatch(
                                        player ->
                                                player.getUserId()
                                                        .equals(id)
                                );

                if (exists && !order.contains(id)) {
                    order.add(id);
                }
            }
        }

        /*
         * Add any room players that are missing from the
         * persisted playerOrder.
         */
        for (GameRoom.Player player : room.getPlayers()) {

            String userId =
                    player.getUserId();

            if (!order.contains(userId)) {
                order.add(userId);
            }
        }

        /*
         * Keep the synchronized order inside the game state.
         */
        state.put(
                "playerOrder",
                order
        );

        return order;
    }


    private void normalizeLudoColors(GameRoom room) {

        if (room.getGameType() != GameType.LUDO) {
            return;
        }

        for (
                int i = 0;
                i < room.getPlayers().size() && i < LUDO_COLORS.size();
                i++
        ) {
            room.getPlayers()
                    .get(i)
                    .setSymbol(
                            LUDO_COLORS.get(i)
                    );
        }
    }

    private void syncLudoPlayersState(GameRoom room) {

        try {
            normalizeLudoColors(room);
            GameState gameState = state(room.getId());
            Map<String, Object> state = gameState.getState();
            List<String> order = room.getPlayers().stream()
                    .map(GameRoom.Player::getUserId)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            state.put("playerOrder", order);
            Map<String, Object> pieces = objectMap(state, "pieces");
            Map<String, Object> legalMoves = objectMap(state, "legalMoves");

            for (String playerId : order) {
                pieces.putIfAbsent(playerId, ludoNewPieces());
            }

            pieces.keySet().removeIf(id -> !order.contains(id));
            legalMoves.keySet().removeIf(id -> !order.contains(id));

            if (!order.isEmpty()) {
                String currentId = String.valueOf(state.get("turnPlayerId"));
                if (!order.contains(currentId)) {
                    int index = Math.floorMod(numberValue(state.get("turnIndex"), 0), order.size());
                    state.put("turnIndex", index);
                    state.put("turnPlayerId", order.get(index));
                }
            }

            gameState.setUpdatedAt(Instant.now());
            states.save(gameState);
        } catch (Exception ignored) {
            // State may not exist yet while a room is being created.
        }
    }


    private List<String> ludoPlayerOrder(
            GameState gameState,
            GameRoom room
    ) {

        Object raw = gameState.getState().get("playerOrder");
        List<String> order = new ArrayList<>();

        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    String id = String.valueOf(value);
                    if (room.getPlayers().stream().anyMatch(p -> p.getUserId().equals(id))) {
                        order.add(id);
                    }
                }
            }
        }

        for (GameRoom.Player player : room.getPlayers()) {
            if (!order.contains(player.getUserId())) {
                order.add(player.getUserId());
            }
        }

        gameState.getState().put("playerOrder", order);
        return order;
    }


    private List<Integer> ludoNewPieces() {
        return new ArrayList<>(Arrays.asList(-1, -1, -1, -1));
    }


    private List<Integer> ludoPieces(
            Map<String, Object> pieces,
            String userId
    ) {

        Object raw = pieces.get(userId);
        List<Integer> result = new ArrayList<>();

        if (raw instanceof List<?> list) {
            for (Object value : list) {
                result.add(numberValue(value, -1));
            }
        }

        while (result.size() < LUDO_PIECES_PER_PLAYER) {
            result.add(-1);
        }

        if (result.size() > LUDO_PIECES_PER_PLAYER) {
            result = new ArrayList<>(
                    result.subList(0, LUDO_PIECES_PER_PLAYER)
            );
        }

        return result;
    }


    private String ludoNextColor(GameRoom room) {
        Set<String> used = new HashSet<>();

        for (GameRoom.Player player : room.getPlayers()) {
            String color = String.valueOf(player.getSymbol()).toUpperCase(Locale.ROOT);
            if (LUDO_COLORS.contains(color)) {
                used.add(color);
            }
        }

        for (String color : LUDO_COLORS) {
            if (!used.contains(color)) {
                return color;
            }
        }

        return LUDO_COLORS.get(0);
    }


    private int ludoSeatIndex(
            GameRoom room,
            String userId
    ) {

        for (int i = 0; i < room.getPlayers().size(); i++) {

            if (
                    room.getPlayers()
                            .get(i)
                            .getUserId()
                            .equals(userId)
            ) {
                return i;
            }
        }

        return -1;
    }

    private String ludoPlayerName(
            GameRoom room,
            String userId
    ) {
        return room.getPlayers().stream()
                .filter(player -> player.getUserId().equals(userId))
                .map(GameRoom.Player::getName)
                .findFirst()
                .orElse("Player");
    }


    /* =========================================================
       GENERIC MAP / VALUE HELPERS
       ========================================================= */

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(
            Map<String, Object> parent,
            String key
    ) {

        Object value =
                parent.get(key);


        if (
                value instanceof Map<?, ?>
        ) {

            return (Map<String, Object>) value;
        }


        Map<String, Object> created =
                new HashMap<>();


        parent.put(
                key,
                created
        );


        return created;
    }


    @SuppressWarnings("unchecked")
    private void removeFromMap(
            Map<String, Object> parent,
            String key,
            String childKey
    ) {

        Object value =
                parent.get(key);


        if (
                value instanceof Map<?, ?>
        ) {

            ((Map<String, Object>) value)
                    .remove(childKey);
        }
    }


    private int numberValue(
            Object value,
            int fallback
    ) {

        if (
                value instanceof Number number
        ) {

            return number.intValue();
        }


        try {

            return Integer.parseInt(
                    String.valueOf(value)
            );

        } catch (Exception e) {

            return fallback;
        }
    }


    private List<String> stringList(
            Object value
    ) {

        if (
                !(value instanceof List<?> list)
        ) {

            return new ArrayList<>();
        }


        List<String> result =
                new ArrayList<>();


        for (
                Object item :
                list
        ) {

            result.add(
                    item == null
                            ? ""
                            : String.valueOf(item)
            );
        }


        return result;
    }


    private List<List<Integer>> toSnake(
            Object value
    ) {

        if (
                !(value instanceof List<?> list)
        ) {

            return new ArrayList<>();
        }


        List<List<Integer>> result =
                new ArrayList<>();


        for (
                Object point :
                list
        ) {

            List<Integer> converted =
                    toPoint(point);


            if (
                    converted != null
            ) {

                result.add(
                        converted
                );
            }
        }


        return result;
    }


    private List<Integer> toPoint(
            Object value
    ) {

        if (
                value instanceof List<?> list &&
                        list.size() >= 2
        ) {

            Integer x =
                    integerObject(
                            list.get(0)
                    );

            Integer y =
                    integerObject(
                            list.get(1)
                    );


            if (
                    x != null &&
                            y != null
            ) {

                return point(
                        x,
                        y
                );
            }
        }


        if (
                value instanceof Map<?, ?> map
        ) {

            Integer x =
                    integerObject(
                            map.get("x")
                    );

            Integer y =
                    integerObject(
                            map.get("y")
                    );


            if (
                    x != null &&
                            y != null
            ) {

                return point(
                        x,
                        y
                );
            }
        }


        return null;
    }


    private Integer integerObject(
            Object value
    ) {

        if (
                value instanceof Number number
        ) {

            return number.intValue();
        }


        try {

            return Integer.parseInt(
                    String.valueOf(value)
            );

        } catch (Exception e) {

            return null;
        }
    }


    private boolean validPoint(
            Object value
    ) {

        return toPoint(value) != null;
    }
}