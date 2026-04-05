package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "nex_users")
@Data
public class NexUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.MEMBER;

    @Column(nullable = false)
    private boolean emailVerified = false;

    private String otpCode;
    private Instant otpExpiresAt;

    /** Tracks failed OTP attempts to prevent brute-force. Reset on fresh code or successful verify. */
    @Column(name = "otp_failed_attempts", nullable = false, columnDefinition = "integer default 0")
    private int otpFailedAttempts = 0;

    @Column(unique = true)
    private String googleId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
