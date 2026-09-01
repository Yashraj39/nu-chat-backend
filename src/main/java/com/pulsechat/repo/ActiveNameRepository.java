package com.pulsechat.repo;

import com.pulsechat.model.ActiveName;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ActiveNameRepository extends MongoRepository<ActiveName, String> {
    Optional<ActiveName> findByNameKey(String nameKey);
    void deleteByUserId(String userId);
}
