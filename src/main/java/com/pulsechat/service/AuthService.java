package com.pulsechat.service;

import com.pulsechat.dto.AuthDtos.*;
import com.pulsechat.model.*;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {
 private final UserRepository repo; private final JwtService jwt; private final String adminCode;
 public AuthService(UserRepository r, JwtService j,@Value("${app.admin-invite-code:}") String c){repo=r;jwt=j;adminCode=c;}
 public JoinResponse join(JoinRequest req){
   String name=req.name()==null?"":req.name().trim();
   if(name.length()<2||name.length()>32) throw new IllegalArgumentException("Name must contain 2-32 characters.");
   Role role=(!adminCode.isBlank() && adminCode.equals(req.adminCode()))?Role.ADMIN:Role.USER;
   User u=User.builder().sessionKey(UUID.randomUUID().toString()).displayName(name).role(role).createdAt(Instant.now()).lastActiveAt(Instant.now()).build();
   repo.save(u);
   return new JoinResponse(jwt.create(u.getId(),role.name(),name),new UserDto(u.getId(),name,role.name()));
 }
}
