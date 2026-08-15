package com.pulsechat.service;

import com.pulsechat.model.*;
import com.pulsechat.repo.MessageRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class MessageService {
 private final MessageRepository repo; private final MongoTemplate mongo; private final RateLimiter limiter;
 public MessageService(MessageRepository r,MongoTemplate m,RateLimiter l){repo=r;mongo=m;limiter=l;}

 public List<Message> latest(){var x=repo.findTop50ByOrderByCreatedAtDesc(); Collections.reverse(x); return x;}
 public Message create(User u, MessageType type,String content,Message.FileInfo file){
   if(!limiter.allow("chat:"+u.getId(),30)) throw new IllegalStateException("Too many messages. Please slow down.");
   if(type==MessageType.TEXT){
     String c=content==null?"":content.trim();
     if(c.isBlank()||c.length()>2000) throw new IllegalArgumentException("Message must contain 1-2000 characters.");
     content=c;
   } else if(file==null) throw new IllegalArgumentException("File metadata is required.");
   Message m=Message.builder().senderId(u.getId()).senderName(u.getDisplayName()).type(type).content(content)
       .file(file).deleted(false).createdAt(Instant.now()).build();
   repo.save(m); trim();
   return m;
 }
 private void trim(){
   var all=repo.findAll(Sort.by(Sort.Direction.DESC,"createdAt"));
   if(all.size()>50) repo.deleteAll(all.subList(50,all.size()));
 }
 public Message delete(User actor,String id){
   Message m=repo.findById(id).orElseThrow(()->new NoSuchElementException("Message not found."));
   if(!actor.getId().equals(m.getSenderId()) && actor.getRole()!=Role.ADMIN) throw new SecurityException("You are not allowed to delete this message.");
   m.setDeleted(true);m.setDeletedAt(Instant.now());m.setContent(null);m.setFile(null);return repo.save(m);
 }
}
