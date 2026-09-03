package com.pulsechat.controller;

import com.pulsechat.model.*;
import com.pulsechat.repo.MessageRepository;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    private void ensureGlobalMediaHistory() {
        savedMedia.backfillLegacyHistory();
    }

    @GetMapping("/messages")
    public List<Message> latest(org.springframework.security.core.Authentication a, HttpServletRequest request) {
        List<Message> result=messages.latest();
        result.forEach(m -> exposeFileProxy(m, request));
        return result;
    }

    @PostMapping("/messages")
    public Message text(@RequestBody Map<String,String> body, org.springframework.security.core.Authentication a) {
        Message m=messages.create(user(a.getName()),MessageType.TEXT,body.get("content"),null,body.get("replyToMessageId"));
        ws.convertAndSend("/topic/chat",m); return m;
    }

    @PostMapping("/files/upload")
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file, org.springframework.security.core.Authentication a,
                                     HttpServletRequest request) throws Exception {
        var x=cloud.upload(file);
        return Map.of("url",proxyUrl(x.publicId(), false, request),"publicId",x.publicId(),"originalName",x.originalName(),"mimeType",x.mimeType(),"size",x.size());
    }

    @PostMapping("/messages/file")
    public Message file(@RequestBody Map<String,Object> body, org.springframework.security.core.Authentication a,
                        HttpServletRequest request) {
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
        } else {
            exposeFileProxy(m, request);
        }

        ws.convertAndSend("/topic/chat",m); return m;
    }

    @PostMapping("/media/link")
    public Message linkMedia(@RequestBody Map<String,Object> body, org.springframework.security.core.Authentication a) {
        User u=user(a.getName());
        String sourceUrl=String.valueOf(body.getOrDefault("url", "")).trim();
        String requestedType=String.valueOf(body.getOrDefault("type", "")).trim().toUpperCase(Locale.ROOT);
        String provider=String.valueOf(body.getOrDefault("provider", "LINK"));
        String providerId=body.get("providerId") == null ? null : String.valueOf(body.get("providerId"));
        String title=body.get("title") == null ? null : String.valueOf(body.get("title"));
        String replyToMessageId=body.get("replyToMessageId") == null ? null : String.valueOf(body.get("replyToMessageId"));

        validateHttpUrl(sourceUrl);
        String lowerUrl=sourceUrl.toLowerCase(Locale.ROOT);
        String kind=requestedType;
        if(kind.isBlank()) {
            if(looksLikeGif(lowerUrl)) kind="GIF";
            else if(looksLikeImage(lowerUrl)) kind="IMAGE";
            else if(looksLikeVideo(lowerUrl)) kind="VIDEO";
            else throw new IllegalArgumentException("Use a direct image, GIF, or video URL ending in a supported media extension.");
        }

        Message message;
        if("GIF".equals(kind) || "STICKER".equals(kind)) {
            String mime="GIF".equals(kind) ? "image/gif" : inferImageMime(lowerUrl);
            Message.MediaInfo media=Message.MediaInfo.builder()
                    .provider(provider).providerId(providerId).title(title)
                    .url(sourceUrl).previewUrl(sourceUrl).mimeType(mime).build();
            message=messages.create(u, "STICKER".equals(kind) ? MessageType.STICKER : MessageType.GIF,
                    null, null, media, replyToMessageId);
            savedMedia.recordSent(u, kind, provider, providerId, title, sourceUrl, sourceUrl, null, mime, 0, 0);
        } else {
            String mime="IMAGE".equals(kind) ? inferImageMime(lowerUrl) : inferVideoMime(lowerUrl);
            String originalName=extractName(sourceUrl);
            Message.FileInfo fi=Message.FileInfo.builder()
                    .url(sourceUrl).publicId(null).originalName(originalName)
                    .mimeType(mime).size(0L).build();
            message=messages.create(u, "IMAGE".equals(kind) ? MessageType.IMAGE : MessageType.FILE,
                    null, fi, replyToMessageId);
            savedMedia.recordSent(u, kind, provider, providerId,
                    title == null || title.isBlank() ? originalName : title,
                    sourceUrl, sourceUrl, null, mime, 0, 0);
        }

        ws.convertAndSend("/topic/chat",message);
        return message;
    }

    @GetMapping("/media/saved")
    public List<SavedMedia> saved(org.springframework.security.core.Authentication a) {
        ensureGlobalMediaHistory();
        return savedMedia.list();
    }

    @PostMapping("/media/saved/{id}/send")
    public Message sendSaved(@PathVariable String id, @RequestBody(required=false) Map<String,Object> body,
                             org.springframework.security.core.Authentication a, HttpServletRequest request) {
        User u=user(a.getName());
        ensureGlobalMediaHistory();
        SavedMedia item=savedMedia.get(id);
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
            exposeFileProxy(message, request);
        }

        savedMedia.recordSent(u, item.getKind(), item.getProvider(), item.getProviderId(), item.getTitle(),
                item.getUrl(), item.getPreviewUrl(), item.getPublicId(), item.getMimeType(), item.getWidth(), item.getHeight());
        ws.convertAndSend("/topic/chat",message);
        return message;
    }

    @GetMapping("/files/{messageId}/download-url")
    public Map<String,String> fileDownloadUrl(@PathVariable String messageId, org.springframework.security.core.Authentication a,
                                               HttpServletRequest request) throws Exception {
        Message message=messageRepo.findById(messageId).orElseThrow(()->new NoSuchElementException("Message not found."));
        if(message.isDeleted() || message.getFile()==null) throw new NoSuchElementException("File not found.");
        Message.FileInfo file=message.getFile();
        if(file.getPublicId()==null || file.getPublicId().isBlank()) return Map.of("url",file.getUrl());
        return Map.of("url",proxyUrl(file.getPublicId(), true, request));
    }

    /**
     * Streams a Cloudinary file through this server. The browser never needs
     * to contact res.cloudinary.com, which makes file sharing work on networks
     * that block Cloudinary while keeping Cloudinary as the storage layer.
     */
    @GetMapping("/files/content")
    public ResponseEntity<StreamingResponseBody> fileContent(
            @RequestParam("publicId") String publicId,
            @RequestParam(value="download", defaultValue="false") boolean download
    ) throws Exception {
        String cleanPublicId=publicId == null ? "" : publicId.trim();
        if(cleanPublicId.isBlank() || cleanPublicId.length()>512 || cleanPublicId.indexOf('\0')>=0) {
            throw new IllegalArgumentException("Invalid file identifier.");
        }

        Message message=messageRepo.findByFilePublicId(cleanPublicId)
                .orElseThrow(()->new NoSuchElementException("File not found."));
        if(message.isDeleted() || message.getFile()==null) throw new NoSuchElementException("File not found.");

        Message.FileInfo file=message.getFile();
        String cloudinaryUrl=downloads.createDownloadUrl(file);
        URL remote=new URL(cloudinaryUrl);
        HttpURLConnection connection=(HttpURLConnection)remote.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);

        int status=connection.getResponseCode();
        if(status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Cloudinary returned HTTP " + status + ".");
        }

        String contentType=file.getMimeType();
        if(contentType==null || contentType.isBlank()) contentType="application/octet-stream";
        String filename=file.getOriginalName()==null || file.getOriginalName().isBlank() ? "download" : file.getOriginalName();
        String disposition=(download ? "attachment" : "inline") + "; filename*=UTF-8''" +
                URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        long length=connection.getContentLengthLong();

        StreamingResponseBody stream=output -> {
            try(InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[16 * 1024];
                int read;
                while((read=input.read(buffer))!=-1) output.write(buffer,0,read);
                output.flush();
            } finally {
                connection.disconnect();
            }
        };

        ResponseEntity.BodyBuilder builder=ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        if(length>=0) builder.contentLength(length);
        return builder.body(stream);
    }

    private void exposeFileProxy(Message message, HttpServletRequest request) {
        if(message==null || message.getFile()==null) return;
        String publicId=message.getFile().getPublicId();
        if(publicId==null || publicId.isBlank()) return;
        message.getFile().setUrl(proxyUrl(publicId, false, request));
    }

    private String proxyUrl(String publicId, boolean download, HttpServletRequest request) {
        String base=request.getScheme()+"://"+request.getServerName();
        if(request.getServerPort()!=80 && request.getServerPort()!=443) base += ":"+request.getServerPort();
        String encoded=URLEncoder.encode(publicId, StandardCharsets.UTF_8);
        return base+"/api/files/content?publicId="+encoded+(download ? "&download=true" : "");
    }

    @DeleteMapping("/messages/{id}")
    public Message delete(@PathVariable String id, org.springframework.security.core.Authentication a) {
        Message m=messages.delete(user(a.getName()),id); ws.convertAndSend("/topic/chat",m); return m;
    }

    private void validateHttpUrl(String sourceUrl) {
        if(sourceUrl.isBlank() || sourceUrl.length()>2048) throw new IllegalArgumentException("Please provide a valid media URL.");
        try {
            URI uri=URI.create(sourceUrl);
            String scheme=uri.getScheme();
            if(!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost()==null || uri.getHost().isBlank()) throw new IllegalArgumentException();
        } catch(Exception e) { throw new IllegalArgumentException("Only valid HTTP/HTTPS media URLs are supported."); }
    }

    private boolean looksLikeGif(String url) { return extension(url).equals("gif"); }
    private boolean looksLikeImage(String url) { return Set.of("jpg","jpeg","png","webp","bmp","avif","svg").contains(extension(url)); }
    private boolean looksLikeVideo(String url) { return Set.of("mp4","webm","mov","m4v","ogg").contains(extension(url)); }

    private String inferImageMime(String url) {
        String e=extension(url);
        if("jpg".equals(e) || "jpeg".equals(e)) return "image/jpeg";
        if("png".equals(e)) return "image/png";
        if("webp".equals(e)) return "image/webp";
        if("bmp".equals(e)) return "image/bmp";
        if("avif".equals(e)) return "image/avif";
        if("svg".equals(e)) return "image/svg+xml";
        return "image/*";
    }

    private String inferVideoMime(String url) {
        String e=extension(url);
        if("webm".equals(e)) return "video/webm";
        if("mov".equals(e)) return "video/quicktime";
        if("ogg".equals(e)) return "video/ogg";
        return "video/mp4";
    }

    private String extension(String url) {
        try {
            String path=URI.create(url).getPath();
            if(path==null) return "";
            int slash=path.lastIndexOf('/');
            String name=slash>=0 ? path.substring(slash+1) : path;
            int dot=name.lastIndexOf('.');
            return dot>=0 ? name.substring(dot+1).toLowerCase(Locale.ROOT) : "";
        } catch(Exception e) { return ""; }
    }

    private String extractName(String url) {
        try {
            String path=URI.create(url).getPath();
            if(path!=null && !path.isBlank()) {
                String name=path.substring(path.lastIndexOf('/')+1);
                if(!name.isBlank()) return name;
            }
        } catch(Exception ignored) {}
        return "linked-media";
    }
}