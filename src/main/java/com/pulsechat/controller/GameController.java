package com.pulsechat.controller;
import com.pulsechat.model.*;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.service.GameService;
import com.pulsechat.service.OxRestartService;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/games")
public class GameController {
    private final GameService games; private final UserRepository users; private final SimpMessagingTemplate ws; private final OxRestartService oxRestart;
    public GameController(GameService g,UserRepository u,SimpMessagingTemplate w,OxRestartService r){games=g;users=u;ws=w;oxRestart=r;}
    private User u(org.springframework.security.core.Authentication a){return users.findById(a.getName()).orElseThrow();}
    @GetMapping("/rooms") public List<GameRoom> rooms(){return games.rooms();}
    @PostMapping("/rooms") public GameRoom create(@RequestBody Map<String,String> b,org.springframework.security.core.Authentication a){
        GameRoom r=games.create(u(a),GameType.valueOf(b.get("gameType")));ws.convertAndSend("/topic/games",r);return r;
    }
    @PostMapping("/rooms/{id}/join") public GameRoom join(@PathVariable String id,org.springframework.security.core.Authentication a){
        GameRoom r=games.join(u(a),id);
        ws.convertAndSend("/topic/game/"+id,r);
        if (r.getGameType()==GameType.LUDO) {
            ws.convertAndSend("/topic/game/"+id+"/state",games.state(id));
        }
        return r;
    }

    @PostMapping("/rooms/{id}/leave")
    public ResponseEntity<?> leave(
            @PathVariable String id,
            org.springframework.security.core.Authentication a
    ) {

        GameRoom r = games.leave(u(a), id);

        /*
         * Room was completely deleted.
         */
        if (r == null) {

            return ResponseEntity.ok(
                    Map.of(
                            "deleted", true,
                            "roomId", id
                    )
            );
        }

        /*
         * Room still exists.
         */
        ws.convertAndSend(
                "/topic/game/" + id,
                r
        );

        /*
         * Ludo needs updated state.
         */
        if (r.getGameType() == GameType.LUDO) {

            try {

                ws.convertAndSend(
                        "/topic/game/" + id + "/state",
                        games.state(id)
                );

            } catch (Exception ignored) {
            }
        }

        return ResponseEntity.ok(r);
    }

    @GetMapping("/rooms/{id}/state") public GameState state(@PathVariable String id){return games.state(id);}
    @PostMapping("/rooms/{id}/action") public GameState action(@PathVariable String id,@RequestBody Map<String,Object>b,org.springframework.security.core.Authentication a){
        User current=u(a);
        String action=String.valueOf(b.get("action"));

        GameState s;
        if ("restart".equalsIgnoreCase(action)) {
            s=oxRestart.restart(current,id);
        } else {
            s=games.genericAction(current,id,action,b.get("payload"));
        }

        ws.convertAndSend("/topic/game/"+id+"/state",s);
        if (s.getGameType()==GameType.LUDO) {
            ws.convertAndSend("/topic/game/"+id,games.get(id));
        }
        return s;
    }
}
