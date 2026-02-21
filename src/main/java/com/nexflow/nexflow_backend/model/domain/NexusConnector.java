package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Nexus Connector — a reusable API connection definition.
 * Users create these once and reference them by name in PULSE nodes
 * instead of re-entering base URLs and auth headers on every node.
 *
 * Example: { name: "Stripe", baseUrl: "https://api.stripe.com", headers: { "Authorization": "Bearer sk_..." } }
 */
@Entity
@Table(name = "nexus_connectors")
@Data
public class NexusConnector {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "base_url")
    private String baseUrl;

    // Auth type: NONE, BEARER, API_KEY, BASIC
    @Column(name = "auth_type")
    private String authType = "NONE";

    // Default headers — merged into every PULSE node that uses this connector
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_headers")
    private Map<String, String> defaultHeaders;

    // Auth credentials stored as JSON (e.g. {"token": "sk_..."} or {"key": "...", "secret": "..."})
    // In production this would be encrypted. For MVP, stored as-is.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_config")
    private Map<String, String> authConfig;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { updatedAt = Instant.now(); }
}
