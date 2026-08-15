package com.pulsechat.dto;
import com.pulsechat.model.MessageType;
public final class MessageDtos {
  private MessageDtos(){}
  public record CreateMessageRequest(MessageType type, String content, String fileUrl, String publicId,
                                     String originalName, String mimeType, Long fileSize) {}
  public record MessageEvent(String event, Object data) {}
}
