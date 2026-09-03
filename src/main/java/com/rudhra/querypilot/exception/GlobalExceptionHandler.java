package com.rudhra.querypilot.exception;

import com.rudhra.querypilot.security.UnsafeSqlException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnsafeSqlException.class)
    public ResponseEntity<Map<String, String>> handleUnsafeSql(UnsafeSqlException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "UNSAFE_SQL",
                        "message", exception.getMessage()
                ));
    }
}
