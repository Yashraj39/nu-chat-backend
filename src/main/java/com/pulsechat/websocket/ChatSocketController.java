package com.pulsechat.websocket;

import com.pulsechat.model.*;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.service.MessageService;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import java.util.Map;

@Controller
public class ChatSocketController {
 private final MessageService messages; private final UserRepository users; private final SimpMessagingTemplate ws;
 public ChatSocketController(MessageService m,UserRepository u,SimpMessagingTemplate w){messages=m;users=u;ws=w;}
 @MessageMapping("/chat.send")
 public void send(@Payload Map<String,Object> body,java.security.Principal principal){
   User u=users.findById(principal.getName()).orElseThrow();
   Message m=messages.create(u,MessageType.TEXT,String.valueOf(body.getOrDefault("content","")),null);
   ws.convertAndSend("/topic/chat",m);
 }
 @MessageMapping("/chat.delete")
 public void delete(@Payload Map<String,Object> body,java.security.Principal principal){
   User u=users.findById(principal.getName()).orElseThrow();
   Message m=messages.delete(u,String.valueOf(body.get("id")));ws.convertAndSend("/topic/chat",m);
 }
}
