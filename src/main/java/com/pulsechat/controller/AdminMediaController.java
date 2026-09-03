package com.pulsechat.controller;

import com.pulsechat.model.SavedMedia;
import com.pulsechat.model.User;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.service.SavedMediaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/media")
public class AdminMediaController {
    private final SavedMediaService savedMedia;
    private final UserRepository users;

    public AdminMediaController(SavedMediaService savedMedia, UserRepository users) {
        this.savedMedia = savedMedia;
        this.users = users;
    }

    @DeleteMapping("/saved/{id}")
    public ResponseEntity<?> deleteSharedMedia(
            @PathVariable String id,
            org.springframework.security.core.Authentication authentication) {
        User user = users.findById(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        if (user.getRole() == null || !"ADMIN".equals(user.getRole().name())) {
            return ResponseEntity.status(403).body(Map.of("message", "Only admins can remove shared media."));
        }

        SavedMedia item = savedMedia.get(id);
        if (!"LINK".equalsIgnoreCase(item.getProvider())) {
            return ResponseEntity.status(403).body(Map.of("message", "Only direct-link media can be removed here."));
        }

        savedMedia.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}
