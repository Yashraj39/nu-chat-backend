package com.pulsechat.repo;

import com.pulsechat.model.SavedMedia;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SavedMediaRepository extends MongoRepository<SavedMedia, String> {
    Optional<SavedMedia> findByUrl(String url);
    List<SavedMedia> findAllByOrderBySentCountDescLastSentAtDesc();
}
