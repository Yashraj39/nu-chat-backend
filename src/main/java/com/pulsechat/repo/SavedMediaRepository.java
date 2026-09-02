package com.pulsechat.repo;

import com.pulsechat.model.SavedMedia;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SavedMediaRepository extends MongoRepository<SavedMedia, String> {
    Optional<SavedMedia> findBySenderIdAndUrl(String senderId, String url);
    List<SavedMedia> findBySenderIdOrderBySentCountDescLastSentAtDesc(String senderId);
    long countBySenderId(String senderId);
}
