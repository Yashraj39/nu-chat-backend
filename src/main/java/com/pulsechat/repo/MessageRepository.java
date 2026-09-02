package com.pulsechat.repo;

import com.pulsechat.model.Message;
import com.pulsechat.model.MessageType;
import org.springframework.data.mongodb.repository.*;

import java.util.Collection;
import java.util.List;

public interface MessageRepository extends MongoRepository<Message,String> {
    List<Message> findTop50ByOrderByCreatedAtDesc();
    List<Message> findBySenderIdAndTypeIn(String senderId, Collection<MessageType> types);
    long countByDeletedFalse();
}
