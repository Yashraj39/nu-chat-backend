package com.pulsechat.dto;
import com.pulsechat.model.GameType;
public final class GameDtos {
  private GameDtos(){}
  public record CreateRoomRequest(GameType gameType) {}
  public record GameAction(String action, Object payload) {}
}
