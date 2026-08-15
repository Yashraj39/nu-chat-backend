package com.pulsechat.repo;
import com.pulsechat.model.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
public interface GameRoomRepository extends MongoRepository<GameRoom,String> {
    List<GameRoom> findTop50ByStatusInOrderByUpdatedAtDesc(List<RoomStatus> statuses);
}
