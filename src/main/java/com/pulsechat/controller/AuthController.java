package com.pulsechat.controller;

import com.pulsechat.dto.AuthDtos.*;
import com.pulsechat.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService s) {
        service = s;
    }

    @PostMapping("/join")
    public JoinResponse join(@RequestBody @Valid JoinRequest req) {
        return service.join(req);
    }

    @PostMapping("/heartbeat")
    public void heartbeat(org.springframework.security.core.Authentication authentication) {
        service.heartbeat(authentication.getName());
    }

    @PostMapping("/logout")
    public void logout(org.springframework.security.core.Authentication authentication) {
        service.logout(authentication.getName());
    }
}
