package com.pulsechat.repo;
import com.pulsechat.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;
public interface UserRepository extends MongoRepository<User,String> {
    Optional<User> findBySessionKey(String sessionKey);
}
