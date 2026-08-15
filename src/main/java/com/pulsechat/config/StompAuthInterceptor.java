package com.pulsechat.config;

import com.pulsechat.security.JwtService;
import org.springframework.context.annotation.*;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import java.security.Principal;

@Component
public class StompAuthInterceptor implements ChannelInterceptor {
 private final JwtService jwt;
 public StompAuthInterceptor(JwtService j){jwt=j;}
 @Override public Message<?> preSend(Message<?> message,MessageChannel channel){
   StompHeaderAccessor a=StompHeaderAccessor.wrap(message);
   if(StompCommand.CONNECT.equals(a.getCommand())){
     String h=a.getFirstNativeHeader("Authorization");
     if(h==null||!h.startsWith("Bearer ")) throw new IllegalArgumentException("Missing WebSocket token");
     var c=jwt.parse(h.substring(7));
     a.setUser(new Principal(){public String getName(){return c.getSubject();}});
   }
   return message;
 }
}
