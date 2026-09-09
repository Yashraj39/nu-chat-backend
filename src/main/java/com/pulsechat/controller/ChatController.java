package com.pulsechat.controller;

import com.pulsechat.model.*;
import com.pulsechat.repo.MessageRepository;
import com.pulsechat.repo.UserRepository;
import com.pulsechat.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.URL;
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
    private final RateLimiter limiter;
    private final int uploadRateLimit;

    public ChatController(MessageService m, CloudinaryService c, CloudinaryDownloadService d,
                          SavedMediaService sm, MessageRepository messageRepo, UserRepository u,
                          SimpMessagingTemplate w, RateLimiter limiter,
                          @Value("${app.upload-rate-limit:10}") int uploadRateLimit) {
        messages=m; cloud=c; downloads=d; savedMedia=sm; this.messageRepo=messageRepo; users=u; ws=w;
        this.limiter=limiter; this.uploadRateLimit=Math.max(1,uploadRateLimit);
    }

    private User user(String id) { return users.findById(id).orElseThrow(); }

    private void ensureGlobalMediaHistory() {
        savedMedia.backfillLegacyHistory();
    }

    @GetMapping("/messages")
    public List<Message> latest(org.springframework.security.core.Authentication a, HttpServletRequest request) {
        List<Message> result=messages.latest();
        result.forEach(m -> { exposeFileProxy(m, request); exposeMediaProxy(m, request); });
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
        if(!limiter.allow("upload:"+a.getName(),uploadRateLimit)) {
            throw new IllegalStateException("Too many uploads. Please slow down.");
        }
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

        if(m.getMedia()!=null) {
            Message.MediaInfo stored=m.getMedia();
            savedMedia.recordSent(u, t.name(), stored.getProvider(), stored.getProviderId(), stored.getTitle(),
                    stored.getUrl(), stored.getPreviewUrl(), null, stored.getMimeType(), stored.getWidth(), stored.getHeight());
            exposeMediaProxy(m, request);
        } else {
            exposeFileProxy(m, request);
        }

        ws.convertAndSend("/topic/chat",m); return m;
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
            Message.MediaInfo stored=message.getMedia();
            savedMedia.recordSent(u, item.getKind(), stored.getProvider(), stored.getProviderId(), stored.getTitle(),
                    stored.getUrl(), stored.getPreviewUrl(), null, stored.getMimeType(), stored.getWidth(), stored.getHeight());
            exposeMediaProxy(message, request);
        } else {
            MessageType type="IMAGE".equals(item.getKind()) ? MessageType.IMAGE : MessageType.FILE;
            Message.FileInfo fi=Message.FileInfo.builder()
                    .url(item.getUrl()).publicId(item.getPublicId()).originalName(item.getTitle() == null ? "linked-media" : item.getTitle())
                    .mimeType(item.getMimeType()).size(0L).build();
            message=messages.create(u, type, null, fi, replyToMessageId);
            exposeFileProxy(message, request);
        }

        ws.convertAndSend("/topic/chat",message);
        return message;
    }

    @GetMapping("/media/content/{messageId}")
    public ResponseEntity<StreamingResponseBody> mediaContent(@PathVariable String messageId) throws Exception {
        Message message=messageRepo.findById(messageId).orElseThrow(()->new NoSuchElementException("Media not found."));
        if(message.isDeleted() || message.getMedia()==null || message.getMedia().getUrl()==null || message.getMedia().getUrl().isBlank()) {
            throw new NoSuchElementException("Media not found.");
        }

        String cloudinaryUrl=message.getMedia().getUrl().trim();
        URI uri=URI.create(cloudinaryUrl);
        String host=uri.getHost();
        if(host==null || !host.toLowerCase(Locale.ROOT).endsWith("res.cloudinary.com")) {
            throw new IllegalArgumentException("Media proxy only supports stored Cloudinary media.");
        }

        HttpURLConnection connection=(HttpURLConnection)new URL(cloudinaryUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);

        int status=connection.getResponseCode();
        if(status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Cloudinary returned HTTP " + status + ".");
        }

        String contentType=message.getMedia().getMimeType();
        if(contentType==null || contentType.isBlank()) contentType=connection.getContentType();
        if(contentType==null || contentType.isBlank()) contentType="application/octet-stream";
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
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        if(length>=0) builder.contentLength(length);
        return builder.body(stream);
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

    /** Streams a Cloudinary file through Render; browser never needs direct Cloudinary access. */
    @GetMapping("/files/content")
    public ResponseEntity<StreamingResponseBody> fileContent(
            @RequestParam("publicId") String publicId,
            @RequestParam(value="download", defaultValue="false") boolean download,
            HttpServletRequest request
    ) throws Exception {
        String cleanPublicId=publicId == null ? "" : publicId.trim();
        if(cleanPublicId.isBlank() || cleanPublicId.length()>512 || cleanPublicId.indexOf('\0')>=0) {
            throw new IllegalArgumentException("Invalid file identifier.");
        }

        Message message=messageRepo.findByFilePublicId(cleanPublicId)
                .orElseThrow(()->new NoSuchElementException("File not found."));
        if(message.isDeleted() || message.getFile()==null) throw new NoSuchElementException("File not found.");

        Message.FileInfo file=message.getFile();
        String cloudinaryUrl=(!download && file.getMimeType()!=null && file.getMimeType().toLowerCase(Locale.ROOT).startsWith("image/") && !"image/svg+xml".equalsIgnoreCase(file.getMimeType()))
                ? downloads.createImagePreviewUrl(file)
                : downloads.createDownloadUrl(file);
        HttpURLConnection connection=(HttpURLConnection)new URL(cloudinaryUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);

        String range=request.getHeader(HttpHeaders.RANGE);
        if(range!=null && !range.isBlank()) connection.setRequestProperty(HttpHeaders.RANGE,range);
        String ifRange=request.getHeader(HttpHeaders.IF_RANGE);
        if(ifRange!=null && !ifRange.isBlank()) connection.setRequestProperty(HttpHeaders.IF_RANGE,ifRange);

        int status=connection.getResponseCode();
        if(status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
            String contentRange=connection.getHeaderField(HttpHeaders.CONTENT_RANGE);
            connection.disconnect();
            if(status==416) {
                ResponseEntity.BodyBuilder error=ResponseEntity.status(416).header(HttpHeaders.CACHE_CONTROL,"private, max-age=60");
                if(contentRange!=null) error.header(HttpHeaders.CONTENT_RANGE,contentRange);
                return error.build();
            }
            throw new IOException("Cloudinary returned HTTP " + status + ".");
        }

        String contentType=connection.getContentType();
        if(contentType==null || contentType.isBlank()) contentType=file.getMimeType();
        if(contentType==null || contentType.isBlank()) contentType="application/octet-stream";
        String filename=file.getOriginalName()==null || file.getOriginalName().isBlank() ? "download" : file.getOriginalName();
        String disposition=(download ? "attachment" : "inline") + "; filename*=UTF-8''" +
                URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        long length=connection.getContentLengthLong();
        String contentRange=connection.getHeaderField(HttpHeaders.CONTENT_RANGE);

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

        ResponseEntity.BodyBuilder builder=ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes");
        if(contentRange!=null) builder.header(HttpHeaders.CONTENT_RANGE,contentRange);
        if(length>=0) builder.contentLength(length);
        return builder.body(stream);
    }

    private void exposeFileProxy(Message message, HttpServletRequest request) {
        if(message==null || message.getFile()==null) return;
        String publicId=message.getFile().getPublicId();
        if(publicId==null || publicId.isBlank()) return;
        message.getFile().setUrl(proxyUrl(publicId, false, request));
    }

    private void exposeMediaProxy(Message message, HttpServletRequest request) {
        if(message==null || message.getMedia()==null) return;
        String url=message.getMedia().getUrl();
        if(url==null || url.isBlank()) return;
        try {
            URI uri=URI.create(url.trim());
            String host=uri.getHost();
            if(host!=null && host.toLowerCase(Locale.ROOT).endsWith("res.cloudinary.com")) {
                message.getMedia().setUrl(mediaProxyUrl(message.getId(), request));
            }
        } catch(Exception ignored) {
        }
    }

    private String mediaProxyUrl(String messageId, HttpServletRequest request) {
        String base=request.getScheme()+"://"+request.getServerName();
        if(request.getServerPort()!=80 && request.getServerPort()!=443) base += ":"+request.getServerPort();
        return base+"/api/media/content/"+URLEncoder.encode(messageId, StandardCharsets.UTF_8);
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
}