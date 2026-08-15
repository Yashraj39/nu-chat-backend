package com.pulsechat.repo;
import com.pulsechat.model.Message;
import org.springframework.data.mongodb.repository.*;
import java.util.List;
public interface MessageRepository extends MongoRepository<Message,String> {
    List<Message> findTop50ByOrderByCreatedAtDesc();
    long countByDeletedFalse();
}
