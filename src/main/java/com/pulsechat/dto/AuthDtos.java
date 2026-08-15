package com.pulsechat.dto;
public final class AuthDtos {
  private AuthDtos(){}
  public record JoinRequest(String name, String adminCode) {}
  public record JoinResponse(String token, UserDto user) {}
  public record UserDto(String id, String displayName, String role) {}
}
