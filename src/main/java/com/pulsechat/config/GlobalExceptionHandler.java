package com.pulsechat.config;

import com.pulsechat.dto.ErrorResponse;
import com.pulsechat.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthService.NameTakenException.class)
    public ResponseEntity<ErrorResponse> nameTaken(AuthService.NameTakenException e) {
        return ResponseEntity
                .status(409)
                .body(new ErrorResponse(false, e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> security(SecurityException e) {
        return ResponseEntity
                .status(403)
                .body(new ErrorResponse(false, e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> bad(IllegalArgumentException e) {
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse(false, e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> state(IllegalStateException e) {
        return ResponseEntity
                .status(429)
                .body(new ErrorResponse(false, e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> generic(Exception e) {
        return ResponseEntity
                .status(500)
                .body(new ErrorResponse(
                        false,
                        "Something went wrong.",
                        Instant.now()
                ));
    }
}
