package com.nexflow.nexflow_backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralized error handling — prevents stack traces leaking in HTTP responses.
 * Controllers may still return their own ResponseEntity for known error cases.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 400 — expected validation failures */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /** 403 — @PreAuthorize failures */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
    }

    /** 500 — catch-all: log internally, never expose details */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAll(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}: {}", req.getServletPath(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "An internal error occurred"));
    }
}
