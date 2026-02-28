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
 * Users create these once and reference them in NEXUS (HTTP Call) nodes
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

    // REST or JDBC — nullable so ALTER TABLE succeeds when table has existing rows; null treated as "REST" in code
    @Column(name = "connector_type")
    private String connectorType = "REST";

    // ── REST fields ────────────────────────────────────────────────────────────

    @Column(name = "base_url")
    private String baseUrl;

    // Auth type: NONE, BEARER, API_KEY, BASIC
    @Column(name = "auth_type")
    private String authType = "NONE";

    // Default headers — merged into every NEXUS node that uses this connector
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_headers")
    private Map<String, String> defaultHeaders;

    // Auth credentials stored as JSON (e.g. {"token": "sk_..."} or {"key": "...", "secret": "..."})
    // In production this would be encrypted. For MVP, stored as-is.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_config")
    private Map<String, String> authConfig;

    // ── JDBC fields ────────────────────────────────────────────────────────────

    @Column(name = "jdbc_url")
    private String jdbcUrl;

    @Column(name = "jdbc_driver")
    private String jdbcDriver;

    @Column(name = "db_username")
    private String dbUsername;

    @Column(name = "db_password")
    private String dbPassword;

    // ── Audit ──────────────────────────────────────────────────────────────────

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { updatedAt = Instant.now(); }
}
