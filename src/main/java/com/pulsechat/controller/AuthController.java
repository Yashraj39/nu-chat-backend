package com.pulsechat.controller;
import com.pulsechat.dto.AuthDtos.*;
import com.pulsechat.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auth")
public class AuthController {
 private final AuthService service; public AuthController(AuthService s){service=s;}
 @PostMapping("/join") public JoinResponse join(@RequestBody JoinRequest req){return service.join(req);}
}
