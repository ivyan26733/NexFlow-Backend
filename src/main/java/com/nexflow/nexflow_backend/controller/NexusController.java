package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.NexusConnector;
import com.nexflow.nexflow_backend.repository.NexusConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/nexus/connectors")
@RequiredArgsConstructor
public class NexusController {

    private final NexusConnectorRepository connectorRepository;
    private final RestTemplate restTemplate;

    // ── Blocked hosts for SSRF protection ────────────────────────────────────
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1",
            "169.254.169.254",          // AWS/GCP/Azure instance metadata
            "metadata.google.internal",
            "100.100.100.200"           // Alibaba Cloud metadata
    );

    // Only allow safe JDBC drivers
    private static final Set<String> ALLOWED_JDBC_PREFIXES = Set.of(
            "jdbc:postgresql://", "jdbc:mysql://", "jdbc:mariadb://"
    );

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @GetMapping
    public List<ConnectorSummary> list(@AuthenticationPrincipal NexUser user) {
        return connectorRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .filter(c -> canAccess(c, user))
                .map(ConnectorSummary::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ConnectorSummary> create(@RequestBody NexusConnector connector,
                                                    @AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        connector.setId(null);
        connector.setUserId(user.getId());
        connector.setCreatedAt(Instant.now());
        connector.setUpdatedAt(Instant.now());
        NexusConnector saved = connectorRepository.save(connector);
        log.info("[Nexus] connector created id={} userId={} type={}", saved.getId(), user.getId(), saved.getConnectorType());
        return ResponseEntity.ok(ConnectorSummary.from(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConnectorSummary> get(@PathVariable UUID id,
                                                 @AuthenticationPrincipal NexUser user) {
        return connectorRepository.findById(id)
                .filter(c -> canAccess(c, user))
                .map(ConnectorSummary::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ConnectorSummary> update(@PathVariable UUID id,
                                                    @RequestBody NexusConnector updated,
                                                    @AuthenticationPrincipal NexUser user) {
        return connectorRepository.findById(id)
                .filter(c -> canAccess(c, user))
                .map(existing -> {
                    existing.setName(updated.getName());
                    existing.setDescription(updated.getDescription());
                    existing.setConnectorType(updated.getConnectorType());
                    existing.setBaseUrl(updated.getBaseUrl());
                    existing.setAuthType(updated.getAuthType());
                    existing.setDefaultHeaders(updated.getDefaultHeaders());
                    existing.setBody(updated.getBody());
                    existing.setQueryParams(updated.getQueryParams());
                    existing.setAuthConfig(updated.getAuthConfig());
                    existing.setJdbcUrl(updated.getJdbcUrl());
                    existing.setJdbcDriver(updated.getJdbcDriver());
                    existing.setDbUsername(updated.getDbUsername());
                    // Only update password if client sends a non-empty value
                    if (updated.getDbPassword() != null && !updated.getDbPassword().isBlank()) {
                        existing.setDbPassword(updated.getDbPassword());
                    }
                    NexusConnector saved = connectorRepository.save(existing);
                    log.info("[Nexus] connector updated id={} userId={}", id, user.getId());
                    return ResponseEntity.ok(ConnectorSummary.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                        @AuthenticationPrincipal NexUser user) {
        Optional<NexusConnector> opt = connectorRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        if (!canAccess(opt.get(), user)) return ResponseEntity.status(403).build();
        connectorRepository.deleteById(id);
        log.info("[Nexus] connector deleted id={} userId={}", id, user.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> requestBody,
            @AuthenticationPrincipal NexUser user) {
        Optional<NexusConnector> opt = connectorRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        NexusConnector connector = opt.get();
        if (!canAccess(connector, user)) return ResponseEntity.status(403).build();

        log.info("[Nexus] test connection start id={} userId={} type={}", id, user.getId(), connector.getConnectorType());
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if ("JDBC".equalsIgnoreCase(connector.getConnectorType())) {
                testJdbc(connector, result);
            } else {
                testRest(connector, result, requestBody);
            }
        } catch (SecurityException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        result.put("latencyMs", System.currentTimeMillis() - start);
        log.info("[Nexus] test connection done id={} success={} latencyMs={}", id, result.get("success"), result.get("latencyMs"));
        return ResponseEntity.ok(result);
    }

    // ── SSRF + REST test ──────────────────────────────────────────────────────

    private void testRest(NexusConnector connector, Map<String, Object> result,
                           Map<String, Object> requestBody) {
        String baseUrl = connector.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            result.put("success", false);
            result.put("message", "REST connector has no baseUrl");
            return;
        }
        validateRestUrl(baseUrl);   // throws SecurityException on SSRF attempt

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (connector.getDefaultHeaders() != null) {
            connector.getDefaultHeaders().forEach(headers::set);
        }
        applyAuthToHeaders(connector, headers);

        String methodStr = "GET";
        Map<String, Object> bodyToSend = null;
        if (requestBody != null && !requestBody.isEmpty()) {
            Object methodObj = requestBody.get("method");
            if (methodObj instanceof String) methodStr = ((String) methodObj).toUpperCase();
            bodyToSend = new LinkedHashMap<>(requestBody);
            bodyToSend.remove("method");
            if (bodyToSend.isEmpty()) bodyToSend = null;
        }

        HttpMethod method;
        try { method = HttpMethod.valueOf(methodStr); }
        catch (IllegalArgumentException e) { method = HttpMethod.GET; }

        boolean sendsBody = bodyToSend != null && method != HttpMethod.GET;
        try {
            ResponseEntity<String> response;
            if (sendsBody) {
                response = restTemplate.exchange(baseUrl, method,
                        new HttpEntity<>(bodyToSend, headers), String.class);
            } else {
                response = restTemplate.exchange(baseUrl, method,
                        new HttpEntity<>(headers), String.class);
            }
            result.put("success", true);
            result.put("message", "OK " + response.getStatusCode());
            result.put("statusCode", response.getStatusCode().value());
            result.put("responseHeaders", response.getHeaders().toSingleValueMap());
            result.put("responseBody", response.getBody());
        } catch (HttpStatusCodeException e) {
            result.put("success", false);
            result.put("message", e.getStatusCode() + " " + e.getResponseBodyAsString());
            result.put("statusCode", e.getStatusCode().value());
            result.put("responseBody", e.getResponseBodyAsString());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
    }

    /**
     * Validates that a REST URL does not point to internal/loopback addresses (SSRF protection).
     * Blocks known cloud metadata endpoints and private IP ranges via DNS resolution.
     */
    private static void validateRestUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new SecurityException("Only http/https URLs are allowed");
            }
            String host = uri.getHost();
            if (host == null) throw new SecurityException("URL has no host");
            if (BLOCKED_HOSTS.contains(host.toLowerCase())) {
                throw new SecurityException("Connections to internal/metadata addresses are not allowed");
            }
            // DNS resolution check — blocks private IP ranges
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                throw new SecurityException("Connections to private/internal IP addresses are not allowed");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + e.getMessage());
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not validate URL: " + e.getMessage());
        }
    }

    // ── JDBC test ─────────────────────────────────────────────────────────────

    private void testJdbc(NexusConnector connector, Map<String, Object> result) {
        String jdbcUrl = connector.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            result.put("success", false);
            result.put("message", "JDBC connector has no jdbcUrl");
            return;
        }
        validateJdbcUrl(jdbcUrl);  // throws SecurityException for dangerous drivers

        String user = connector.getDbUsername();
        String pass = connector.getDbPassword() != null ? connector.getDbPassword() : "";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user != null ? user : "", pass)) {
            result.put("success", true);
            result.put("message", "Connected");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
    }

    private static void validateJdbcUrl(String url) {
        String lower = url.toLowerCase();
        boolean allowed = ALLOWED_JDBC_PREFIXES.stream().anyMatch(lower::startsWith);
        if (!allowed) {
            throw new SecurityException("Unsupported JDBC driver. Allowed: PostgreSQL, MySQL, MariaDB.");
        }
    }

    // ── Auth header helpers ───────────────────────────────────────────────────

    private void applyAuthToHeaders(NexusConnector connector, HttpHeaders headers) {
        if (connector.getAuthConfig() == null || "NONE".equalsIgnoreCase(connector.getAuthType())) return;
        switch (connector.getAuthType().toUpperCase()) {
            case "BEARER" -> {
                String token = connector.getAuthConfig().get("token");
                if (token != null) headers.set("Authorization", "Bearer " + token);
            }
            case "API_KEY" -> {
                String key      = connector.getAuthConfig().getOrDefault("headerName", "X-API-Key");
                String keyValue = connector.getAuthConfig().get("key");
                if (keyValue != null) headers.set(key, keyValue);
            }
            case "BASIC" -> {
                String username = connector.getAuthConfig().getOrDefault("username", "");
                String password = connector.getAuthConfig().getOrDefault("password", "");
                String encoded  = Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.set("Authorization", "Basic " + encoded);
            }
            default -> { }
        }
    }

    // ── Access control ────────────────────────────────────────────────────────

    /** Owner can access; admin can access everything; null-owner connectors = legacy, allow all authenticated. */
    private static boolean canAccess(NexusConnector connector, NexUser user) {
        if (user == null) return false;
        if (connector.getUserId() == null) return true;   // legacy connector — visible to all
        if (user.getRole() == com.nexflow.nexflow_backend.model.domain.UserRole.ADMIN) return true;
        return connector.getUserId().equals(user.getId());
    }

    // ── Safe response DTO — never exposes credentials ─────────────────────────

    /**
     * Connector summary returned by all GET endpoints.
     * Credentials (dbPassword, authConfig tokens) are deliberately omitted.
     * The UI indicates "configured" status without needing the raw values.
     */
    public record ConnectorSummary(
            UUID                id,
            String              name,
            String              description,
            String              connectorType,
            String              baseUrl,
            String              authType,
            boolean             hasAuthConfig,
            String              dbUsername,
            boolean             hasDbPassword,
            Map<String, String> defaultHeaders,
            Map<String, String> queryParams,
            String              jdbcUrl,
            String              jdbcDriver,
            Instant             createdAt,
            Instant             updatedAt
    ) {
        static ConnectorSummary from(NexusConnector c) {
            return new ConnectorSummary(
                    c.getId(), c.getName(), c.getDescription(),
                    c.getConnectorType(), c.getBaseUrl(), c.getAuthType(),
                    c.getAuthConfig() != null && !c.getAuthConfig().isEmpty(),
                    c.getDbUsername(),
                    c.getDbPassword() != null && !c.getDbPassword().isBlank(),
                    c.getDefaultHeaders(), c.getQueryParams(),
                    c.getJdbcUrl(), c.getJdbcDriver(),
                    c.getCreatedAt(), c.getUpdatedAt()
            );
        }
    }
}
