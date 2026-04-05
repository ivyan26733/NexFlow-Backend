package com.nexflow.nexflow_backend.model.dto.auth;

import com.nexflow.nexflow_backend.model.domain.UserRole;

import java.util.UUID;

public record AuthResponse(
        String token,
        UUID   id,
        String email,
        String name,
        UserRole role
) {}
