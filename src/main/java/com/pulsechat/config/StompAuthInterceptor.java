package com.pulsechat.config;

import com.pulsechat.repo.UserRepository;
import com.pulsechat.security.JwtService;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwt;
    private final UserRepository users;

    public StompAuthInterceptor(
            JwtService jwt,
            UserRepository users
    ) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.wrap(message);

        if (!StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String header =
                accessor.getFirstNativeHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing WebSocket token");
        }

        try {
            var claims = jwt.parse(header.substring(7));

            // Do not allow a valid JWT whose user was deleted.
            if (!users.existsById(claims.getSubject())) {
                throw new IllegalArgumentException("User session is no longer valid");
            }

            accessor.setUser(new Principal() {
                @Override
                public String getName() {
                    return claims.getSubject();
                }
            });

            return message;

        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid or expired WebSocket token", ex);
        }
    }
}
