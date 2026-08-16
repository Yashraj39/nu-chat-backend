package com.pulsechat.controller;

import com.pulsechat.model.*;
import com.pulsechat.repo.MessageRepository;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.service.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final MessageService messages;
    private final CloudinaryService cloud;
    private final CloudinaryDownloadService downloads;
    private final MessageRepository messageRepo;
    private final UserRepository users;
    private final SimpMessagingTemplate ws;

    public ChatController(
            MessageService m,
            CloudinaryService c,
            CloudinaryDownloadService d,
            MessageRepository messageRepo,
            UserRepository u,
            SimpMessagingTemplate w
    ) {
        messages = m;
        cloud = c;
        downloads = d;
        this.messageRepo = messageRepo;
        users = u;
        ws = w;
    }

    private User user(String id) {
        return users.findById(id).orElseThrow();
    }

    @GetMapping("/messages")
    public List<Message> latest(org.springframework.security.core.Authentication a) {
        return messages.latest();
    }

    @PostMapping("/messages")
    public Message text(
            @RequestBody Map<String, String> body,
            org.springframework.security.core.Authentication a
    ) {
        User u = user(a.getName());
        Message m = messages.create(u, MessageType.TEXT, body.get("content"), null);
        ws.convertAndSend("/topic/chat", m);
        return m;
    }

    @PostMapping("/files/upload")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            org.springframework.security.core.Authentication a
    ) throws Exception {
        var x = cloud.upload(file);
        return Map.of(
                "url", x.url(),
                "publicId", x.publicId(),
                "originalName", x.originalName(),
                "mimeType", x.mimeType(),
                "size", x.size()
        );
    }

    @PostMapping("/messages/file")
    public Message file(
            @RequestBody Map<String, Object> body,
            org.springframework.security.core.Authentication a
    ) {
        User u = user(a.getName());
        MessageType t = String.valueOf(body.get("mimeType")).startsWith("image/")
                ? MessageType.IMAGE
                : MessageType.FILE;

        var fi = Message.FileInfo.builder()
                .url((String) body.get("url"))
                .publicId((String) body.get("publicId"))
                .originalName((String) body.get("originalName"))
                .mimeType((String) body.get("mimeType"))
                .size(((Number) body.get("size")).longValue())
                .build();

        Message m = messages.create(u, t, null, fi);
        ws.convertAndSend("/topic/chat", m);
        return m;
    }

    /**
     * Returns a short-lived signed Cloudinary download URL for an existing
     * chat file. This avoids public CDN delivery restrictions for PDFs, ZIPs,
     * and other raw files while keeping the Cloudinary API secret on the server.
     */
    @GetMapping("/files/{messageId}/download-url")
    public Map<String, String> fileDownloadUrl(
            @PathVariable String messageId,
            org.springframework.security.core.Authentication a
    ) {
        Message message = messageRepo.findById(messageId)
                .orElseThrow(() -> new NoSuchElementException("Message not found."));

        if (message.isDeleted() || message.getFile() == null) {
            throw new NoSuchElementException("File not found.");
        }

        String url = downloads.createDownloadUrl(message.getFile());
        return Map.of("url", url);
    }

    @DeleteMapping("/messages/{id}")
    public Message delete(
            @PathVariable String id,
            org.springframework.security.core.Authentication a
    ) {
        Message m = messages.delete(user(a.getName()), id);
        ws.convertAndSend("/topic/chat", m);
        return m;
    }
}
