package com.pulsechat.service;
import com.pulsechat.model.*;
import com.pulsechat.repo.MessageRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageServiceTest {
 @Test void ownMessageCanBeDeleted(){
   MessageRepository r=mock(MessageRepository.class); MongoTemplate m=mock(MongoTemplate.class); RateLimiter l=mock(RateLimiter.class); CloudinaryService c=mock(CloudinaryService.class);
   when(l.allow(anyString(),anyInt())).thenReturn(true);
   Message x=Message.builder().id("1").senderId("u1").senderName("A").type(MessageType.TEXT).content("hi").createdAt(Instant.now()).build();
   when(r.findById("1")).thenReturn(java.util.Optional.of(x)); when(r.save(any())).thenAnswer(i->i.getArgument(0));
   var s=new MessageService(r,m,l,c); User u=User.builder().id("u1").role(Role.USER).build();
   assertTrue(s.delete(u,"1").isDeleted());
 }
 @Test void otherUserCannotDelete(){
   MessageRepository r=mock(MessageRepository.class); MongoTemplate m=mock(MongoTemplate.class); RateLimiter l=mock(RateLimiter.class); CloudinaryService c=mock(CloudinaryService.class);
   Message x=Message.builder().id("1").senderId("owner").build(); when(r.findById("1")).thenReturn(java.util.Optional.of(x));
   var s=new MessageService(r,m,l,c); User u=User.builder().id("other").role(Role.USER).build();
   assertThrows(SecurityException.class,()->s.delete(u,"1"));
 }
}
