package com.pulsechat.service;

import com.pulsechat.dto.AuthDtos.*;
import com.pulsechat.model.*;
import com.pulsechat.repo.ActiveNameRepository;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private static final long NAME_LEASE_SECONDS = 90;

    private final UserRepository repo;
    private final ActiveNameRepository activeNames;
    private final JwtService jwt;
    private final String adminCode;

    public AuthService(
            UserRepository r,
            ActiveNameRepository activeNames,
            JwtService j,
            @Value("${app.admin-invite-code:}") String c
    ) {
        repo = r;
        this.activeNames = activeNames;
        jwt = j;
        adminCode = c;
    }

    public JoinResponse join(JoinRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.length() < 2 || name.length() > 32) {
            throw new IllegalArgumentException("Name must contain 2-32 characters.");
        }

        String nameKey = nameKey(name);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(NAME_LEASE_SECONDS);
        Role role = (!adminCode.isBlank() && adminCode.equals(req.adminCode()))
                ? Role.ADMIN
                : Role.USER;

        // Remove a stale reservation if its lease has already expired.
        activeNames.findByNameKey(nameKey).ifPresent(existing -> {
            if (!existing.getExpiresAt().isAfter(now)) {
                activeNames.delete(existing);
            }
        });

        User u = User.builder()
                .sessionKey(UUID.randomUUID().toString())
                .displayName(name)
                .role(role)
                .createdAt(now)
                .lastActiveAt(now)
                .build();

        repo.save(u);

        try {
            activeNames.saveAndFlush(ActiveName.builder()
                    .nameKey(nameKey)
                    .userId(u.getId())
                    .expiresAt(expiresAt)
                    .build());
        } catch (DuplicateKeyException e) {
            // Do not leave an orphaned user when the name was claimed concurrently.
            repo.deleteById(u.getId());
            throw new NameTakenException("The name \"" + name + "\" is already in use. Please choose another name.");
        }

        return new JoinResponse(
                jwt.create(u.getId(), role.name(), name),
                new UserDto(u.getId(), name, role.name())
        );
    }

    public void heartbeat(String userId) {
        User u = repo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Session is no longer active."));

        Instant now = Instant.now();
        u.setLastActiveAt(now);
        repo.save(u);

        String key = nameKey(u.getDisplayName());
        activeNames.findByNameKey(key).ifPresentOrElse(
                reservation -> {
                    if (u.getId().equals(reservation.getUserId())) {
                        reservation.setExpiresAt(now.plusSeconds(NAME_LEASE_SECONDS));
                        activeNames.save(reservation);
                    }
                },
                () -> {
                    try {
                        activeNames.saveAndFlush(ActiveName.builder()
                                .nameKey(key)
                                .userId(u.getId())
                                .expiresAt(now.plusSeconds(NAME_LEASE_SECONDS))
                                .build());
                    } catch (DuplicateKeyException e) {
                        throw new NameTakenException("Your display name is already in use by another active session.");
                    }
                }
        );
    }

    public void logout(String userId) {
        activeNames.deleteByUserId(userId);
    }

    public static String nameKey(String name) {
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    public static class NameTakenException extends RuntimeException {
        public NameTakenException(String message) {
            super(message);
        }
    }
}
