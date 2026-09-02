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
    private final SavedMediaService savedMedia;
    private final MessageRepository messageRepo;
    private final UserRepository users;
    private final SimpMessagingTemplate ws;

    public ChatController(MessageService m, CloudinaryService c, CloudinaryDownloadService d,
                          SavedMediaService sm, MessageRepository messageRepo, UserRepository u,
                          SimpMessagingTemplate w) {
        messages=m; cloud=c; downloads=d; savedMedia=sm; this.messageRepo=messageRepo; users=u; ws=w;
    }

    private User user(String id) { return users.findById(id).orElseThrow(); }

    @GetMapping("/messages")
    public List<Message> latest(org.springframework.security.core.Authentication a) { return messages.latest(); }

    @PostMapping("/messages")
    public Message text(@RequestBody Map<String,String> body, org.springframework.security.core.Authentication a) {
        Message m=messages.create(user(a.getName()),MessageType.TEXT,body.get("content"),null,body.get("replyToMessageId"));
        ws.convertAndSend("/topic/chat",m); return m;
    }

    @PostMapping("/files/upload")
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file, org.springframework.security.core.Authentication a) throws Exception {
        var x=cloud.upload(file);
        return Map.of("url",x.url(),"publicId",x.publicId(),"originalName",x.originalName(),"mimeType",x.mimeType(),"size",x.size());
    }

    @PostMapping("/messages/file")
    public Message file(@RequestBody Map<String,Object> body, org.springframework.security.core.Authentication a) {
        User u=user(a.getName());
        MessageType t;
        String requestedType=String.valueOf(body.getOrDefault("type",""));
        if("GIF".equalsIgnoreCase(requestedType)) t=MessageType.GIF;
        else if("STICKER".equalsIgnoreCase(requestedType)) t=MessageType.STICKER;
        else t=String.valueOf(body.get("mimeType")).startsWith("image/") ? MessageType.IMAGE : MessageType.FILE;

        Message.FileInfo fi=null;
        Message.MediaInfo media=null;
        if(t==MessageType.GIF || t==MessageType.STICKER){
            media=Message.MediaInfo.builder()
                    .provider((String)body.get("provider")).providerId((String)body.get("providerId"))
                    .title((String)body.get("title")).url((String)body.get("url"))
                    .previewUrl((String)body.get("previewUrl"))
                    .mimeType((String)body.get("mimeType"))
                    .width(body.get("width") instanceof Number ? ((Number)body.get("width")).intValue() : 0)
                    .height(body.get("height") instanceof Number ? ((Number)body.get("height")).intValue() : 0).build();
        } else {
            fi=Message.FileInfo.builder().url((String)body.get("url")).publicId((String)body.get("publicId"))
                    .originalName((String)body.get("originalName")).mimeType((String)body.get("mimeType"))
                    .size(body.get("size") instanceof Number ? ((Number)body.get("size")).longValue() : 0L).build();
        }
        Message m=messages.create(u,t,null,fi,media,(String)body.get("replyToMessageId"));

        if(media!=null) {
            savedMedia.recordSent(u, t.name(), media.getProvider(), media.getProviderId(), media.getTitle(),
                    media.getUrl(), media.getPreviewUrl(), null, media.getMimeType(), media.getWidth(), media.getHeight());
        }

        ws.convertAndSend("/topic/chat",m); return m;
    }

    @PostMapping("/media/link")
    public Message linkMedia(@RequestBody Map<String,Object> body, org.springframework.security.core.Authentication a) throws Exception {
        User u=user(a.getName());
        String sourceUrl=String.valueOf(body.getOrDefault("url", "")).trim();
        String requestedType=String.valueOf(body.getOrDefault("type", "")).trim().toUpperCase(Locale.ROOT);
        String provider=String.valueOf(body.getOrDefault("provider", "LINK"));
        String providerId=body.get("providerId") == null ? null : String.valueOf(body.get("providerId"));
        String title=body.get("title") == null ? null : String.valueOf(body.get("title"));
        String replyToMessageId=body.get("replyToMessageId") == null ? null : String.valueOf(body.get("replyToMessageId"));

        if(sourceUrl.isBlank()) throw new IllegalArgumentException("Please provide an image, GIF, or video link.");
        if(!requestedType.isBlank() && !requestedType.equals("GIF") && !requestedType.equals("STICKER")) {
            throw new IllegalArgumentException("Unsupported media type.");
        }

        var remote=cloud.uploadRemoteUrl(sourceUrl);
        boolean remoteImage="image".equals(remote.resourceType());
        if("STICKER".equals(requestedType) || "GIF".equals(requestedType)) {
            if(!remoteImage) throw new IllegalArgumentException("GIFs and stickers must be image/GIF links.");
        }

        Message message;
        String kind;
        if("STICKER".equals(requestedType) || "GIF".equals(requestedType)) {
            Message.MediaInfo media=Message.MediaInfo.builder()
                    .provider(provider).providerId(providerId).title(title)
                    .url(remote.url()).previewUrl(remote.url()).mimeType(remote.mimeType())
                    .width(remote.width()).height(remote.height()).build();
            message=messages.create(u, "STICKER".equals(requestedType) ? MessageType.STICKER : MessageType.GIF,
                    null, null, media, replyToMessageId);
            kind=message.getType().name();
            savedMedia.recordSent(u, kind, provider, providerId, title, remote.url(), remote.url(),
                    remote.publicId(), remote.mimeType(), remote.width(), remote.height());
        } else if(remoteImage) {
            Message.FileInfo fi=Message.FileInfo.builder()
                    .url(remote.url()).publicId(remote.publicId()).originalName(remote.originalName())
                    .mimeType(remote.mimeType()).size(remote.size()).build();
            message=messages.create(u, MessageType.IMAGE, null, fi, replyToMessageId);
            savedMedia.recordSent(u, "IMAGE", provider, providerId, remote.originalName(), remote.url(), remote.url(),
                    remote.publicId(), remote.mimeType(), remote.width(), remote.height());
        } else {
            Message.FileInfo fi=Message.FileInfo.builder()
                    .url(remote.url()).publicId(remote.publicId()).originalName(remote.originalName())
                    .mimeType(remote.mimeType()).size(remote.size()).build();
            message=messages.create(u, MessageType.FILE, null, fi, replyToMessageId);
            savedMedia.recordSent(u, "VIDEO", provider, providerId, remote.originalName(), remote.url(), remote.url(),
                    remote.publicId(), remote.mimeType(), remote.width(), remote.height());
        }

        ws.convertAndSend("/topic/chat",message);
        return message;
    }

    @GetMapping("/media/saved")
    public List<SavedMedia> saved(org.springframework.security.core.Authentication a) {
        return savedMedia.list(user(a.getName()));
    }

    @PostMapping("/media/saved/{id}/send")
    public Message sendSaved(@PathVariable String id, @RequestBody(required=false) Map<String,Object> body,
                             org.springframework.security.core.Authentication a) {
        User u=user(a.getName());
        SavedMedia item=savedMedia.getForUser(u,id);
        String replyToMessageId=body == null || body.get("replyToMessageId") == null ? null : String.valueOf(body.get("replyToMessageId"));

        Message message;
        if("GIF".equals(item.getKind()) || "STICKER".equals(item.getKind())) {
            Message.MediaInfo media=Message.MediaInfo.builder()
                    .provider(item.getProvider()).providerId(item.getProviderId()).title(item.getTitle())
                    .url(item.getUrl()).previewUrl(item.getPreviewUrl()).mimeType(item.getMimeType())
                    .width(item.getWidth()).height(item.getHeight()).build();
            message=messages.create(u, MessageType.valueOf(item.getKind()), null, null, media, replyToMessageId);
        } else {
            MessageType type="IMAGE".equals(item.getKind()) ? MessageType.IMAGE : MessageType.FILE;
            Message.FileInfo fi=Message.FileInfo.builder()
                    .url(item.getUrl()).publicId(item.getPublicId()).originalName(item.getTitle() == null ? "linked-media" : item.getTitle())
                    .mimeType(item.getMimeType()).size(0L).build();
            message=messages.create(u, type, null, fi, replyToMessageId);
        }

        savedMedia.recordSent(u, item.getKind(), item.getProvider(), item.getProviderId(), item.getTitle(),
                item.getUrl(), item.getPreviewUrl(), item.getPublicId(), item.getMimeType(), item.getWidth(), item.getHeight());
        ws.convertAndSend("/topic/chat",message);
        return message;
    }

    @GetMapping("/files/{messageId}/download-url")
    public Map<String,String> fileDownloadUrl(@PathVariable String messageId, org.springframework.security.core.Authentication a) throws Exception {
        Message message=messageRepo.findById(messageId).orElseThrow(()->new NoSuchElementException("Message not found."));
        if(message.isDeleted() || message.getFile()==null) throw new NoSuchElementException("File not found.");
        return Map.of("url",downloads.createDownloadUrl(message.getFile()));
    }

    @DeleteMapping("/messages/{id}")
    public Message delete(@PathVariable String id, org.springframework.security.core.Authentication a) {
        Message m=messages.delete(user(a.getName()),id); ws.convertAndSend("/topic/chat",m); return m;
    }
}
