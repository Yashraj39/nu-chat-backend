package com.pulsechat.repo;
import com.pulsechat.model.GameState;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface GameStateRepository extends MongoRepository<GameState,String> {}
