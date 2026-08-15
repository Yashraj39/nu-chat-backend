package com.pulsechat.dto;
import java.time.Instant;
public record ErrorResponse(boolean success, String message, Instant timestamp) {}
