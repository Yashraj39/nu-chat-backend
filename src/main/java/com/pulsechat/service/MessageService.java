package com.pulsechat.service;

import com.pulsechat.model.*;
import com.pulsechat.repo.MessageRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service
public class MessageService {
 private final MessageRepository repo;
 private final MongoTemplate mongo;
 private final RateLimiter limiter;
 private final CloudinaryService cloud;

 public MessageService(MessageRepository r, MongoTemplate m, RateLimiter l, CloudinaryService c){
   repo=r;mongo=m;limiter=l;cloud=c;
 }

 public List<Message> latest(){
   var x=repo.findTop50ByOrderByCreatedAtDesc();
   Collections.reverse(x);
   return x;
 }

 public Message create(User u, MessageType type,String content,Message.FileInfo file){
   return create(u,type,content,file,null,null);
 }

 public Message create(User u, MessageType type,String content,Message.FileInfo file,String replyToMessageId){
   return create(u,type,content,file,null,replyToMessageId);
 }

 public Message create(User u, MessageType type,String content,Message.FileInfo file,Message.MediaInfo media,String replyToMessageId){
   if(!limiter.allow("chat:"+u.getId(),30)) throw new IllegalStateException("Too many messages. Please slow down.");
   if(type==MessageType.TEXT){
     String c=content==null?"":content.trim();
     if(c.isBlank()||c.length()>2000) throw new IllegalArgumentException("Message must contain 1-2000 characters.");
     content=c;
   } else if(type==MessageType.GIF || type==MessageType.STICKER){
     if(media==null || media.getUrl()==null || media.getUrl().isBlank()) throw new IllegalArgumentException("Media metadata is required.");
     media = normalizeExternalMedia(media);
     content = null;
   } else {
     if(file==null) throw new IllegalArgumentException("File metadata is required.");
     file = normalizeExternalFile(file);
   }

   Message.ReplyReference replyTo = null;
   if(replyToMessageId != null && !replyToMessageId.isBlank()) {
     Message original = repo.findById(replyToMessageId.trim())
         .orElseThrow(() -> new NoSuchElementException("The message you are replying to no longer exists."));
     String previewContent = original.getContent();
     String fileName = original.getFile() != null ? original.getFile().getOriginalName() : null;
     String mimeType = original.getFile() != null ? original.getFile().getMimeType() : null;
     replyTo = Message.ReplyReference.builder()
         .messageId(original.getId()).senderId(original.getSenderId()).senderName(original.getSenderName())
         .type(original.getType()).content(previewContent).fileName(fileName).mimeType(mimeType)
         .deleted(original.isDeleted()).build();
   }

   Message m=Message.builder().senderId(u.getId()).senderName(u.getDisplayName()).type(type).content(content)
       .file(file).media(media).replyTo(replyTo).deleted(false).createdAt(Instant.now()).build();
   repo.save(m); trim();
   return m;
 }

 private Message.MediaInfo normalizeExternalMedia(Message.MediaInfo media) {
   String url = media.getUrl().trim();
   if(isCloudinaryUrl(url)) return media;

   try {
     CloudinaryService.RemoteUploadResult remote = cloud.uploadRemoteUrl(url);
     if(!"image".equalsIgnoreCase(remote.resourceType()) && !"video".equalsIgnoreCase(remote.resourceType())) {
       throw new IllegalArgumentException("Unsupported media type.");
     }

     return Message.MediaInfo.builder()
         .provider(media.getProvider())
         .providerId(media.getProviderId())
         .title(media.getTitle())
         .url(remote.url())
         .previewUrl(remote.url())
         .mimeType(remote.mimeType())
         .width(remote.width() > 0 ? remote.width() : media.getWidth())
         .height(remote.height() > 0 ? remote.height() : media.getHeight())
         .build();
   } catch (IllegalArgumentException e) {
     throw e;
   } catch (Exception e) {
     throw new IllegalArgumentException("This GIF or media link could not be imported. It was not sent because it may be blocked, expired, private, or unsupported.");
   }
 }

 private Message.FileInfo normalizeExternalFile(Message.FileInfo file) {
   // Files coming from /api/files/upload already have a Cloudinary publicId.
   // Their URL is deliberately rewritten to the backend proxy, so never try
   // to import that proxy URL back into Cloudinary.
   if(file.getPublicId() != null && !file.getPublicId().isBlank()) return file;

   String url = file.getUrl() == null ? "" : file.getUrl().trim();
   if(url.isBlank()) return file;

   try {
     CloudinaryService.RemoteUploadResult remote = cloud.uploadRemoteUrl(url);
     if(!"image".equalsIgnoreCase(remote.resourceType()) && !"video".equalsIgnoreCase(remote.resourceType())) {
       throw new IllegalArgumentException("Unsupported media type.");
     }

     return Message.FileInfo.builder()
         .url(remote.url())
         .publicId(remote.publicId())
         .originalName(file.getOriginalName() == null || file.getOriginalName().isBlank() ? remote.originalName() : file.getOriginalName())
         .mimeType(remote.mimeType())
         .size(remote.size() > 0 ? remote.size() : file.getSize())
         .build();
   } catch (IllegalArgumentException e) {
     throw e;
   } catch (Exception e) {
     throw new IllegalArgumentException("This media link could not be imported. It was not sent because it may be blocked, expired, private, or unsupported.");
   }
 }

 private boolean isCloudinaryUrl(String url) {
   try {
     String host = URI.create(url).getHost();
     return host != null && host.toLowerCase(Locale.ROOT).endsWith("res.cloudinary.com");
   } catch (Exception e) {
     return false;
   }
 }

 private void trim(){
   var all=repo.findAll(Sort.by(Sort.Direction.DESC,"createdAt"));
   if(all.size()>50) repo.deleteAll(all.subList(50,all.size()));
 }
 public Message delete(User actor,String id){
   Message m=repo.findById(id).orElseThrow(()->new NoSuchElementException("Message not found."));
   if(!actor.getId().equals(m.getSenderId()) && actor.getRole()!=Role.ADMIN) throw new SecurityException("You are not allowed to delete this message.");
   m.setDeleted(true);m.setDeletedAt(Instant.now());m.setContent(null);m.setFile(null);m.setMedia(null);return repo.save(m);
 }
}
