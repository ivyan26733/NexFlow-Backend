package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.NexusConnector;
import com.nexflow.nexflow_backend.repository.NexusConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

@RestController
@RequestMapping("/api/nexus/connectors")
@RequiredArgsConstructor
public class NexusController {

    private final NexusConnectorRepository connectorRepository;
    private final RestTemplate restTemplate;

    @GetMapping
    public List<NexusConnector> list() {
        return connectorRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public NexusConnector create(@RequestBody NexusConnector connector) {
        connector.setId(null); // let DB generate
        return connectorRepository.save(connector);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NexusConnector> get(@PathVariable UUID id) {
        return connectorRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<NexusConnector> update(@PathVariable UUID id, @RequestBody NexusConnector updated) {
        return connectorRepository.findById(id)
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
                    existing.setDbPassword(updated.getDbPassword());
                    return ResponseEntity.ok(connectorRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!connectorRepository.existsById(id)) return ResponseEntity.notFound().build();
        connectorRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> requestBody) {
        Optional<NexusConnector> opt = connectorRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        NexusConnector connector = opt.get();
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if ("JDBC".equalsIgnoreCase(connector.getConnectorType())) {
                testJdbc(connector, result);
            } else {
                testRest(connector, result, requestBody);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        result.put("latencyMs", System.currentTimeMillis() - start);
        return ResponseEntity.ok(result);
    }

    private void testRest(NexusConnector connector, Map<String, Object> result, Map<String, Object> requestBody) {
        String baseUrl = connector.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            result.put("success", false);
            result.put("message", "REST connector has no baseUrl");
            return;
        }
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
            if (methodObj instanceof String) {
                methodStr = ((String) methodObj).toUpperCase();
            }
            bodyToSend = new LinkedHashMap<>(requestBody);
            bodyToSend.remove("method");
            if (bodyToSend.isEmpty()) bodyToSend = null;
        }

        HttpMethod method;
        try {
            method = HttpMethod.valueOf(methodStr);
        } catch (IllegalArgumentException e) {
            method = HttpMethod.GET;
        }
        boolean sendsBody = bodyToSend != null && !bodyToSend.isEmpty() && method != HttpMethod.GET;
        try {
            ResponseEntity<String> response;
            if (sendsBody) {
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(bodyToSend, headers);
                response = restTemplate.exchange(baseUrl, method, request, String.class);
            } else {
                HttpEntity<Void> request = new HttpEntity<>(headers);
                response = restTemplate.exchange(baseUrl, method, request, String.class);
            }
            result.put("success", true);
            result.put("message", "OK " + response.getStatusCode());
            result.put("statusCode", response.getStatusCode().value());
            result.put("responseHeaders", response.getHeaders().toSingleValueMap());
            result.put("responseBody", response.getBody());
        } catch (HttpStatusCodeException e) {
            result.put("success", false);
            result.put("message", e.getStatusCode() + " " + (e.getResponseBodyAsString() != null ? e.getResponseBodyAsString() : e.getMessage()));
            result.put("statusCode", e.getStatusCode().value());
            result.put("responseBody", e.getResponseBodyAsString());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
    }

    private void applyAuthToHeaders(NexusConnector connector, HttpHeaders headers) {
        if (connector.getAuthConfig() == null || "NONE".equalsIgnoreCase(connector.getAuthType())) return;
        switch (connector.getAuthType().toUpperCase()) {
            case "BEARER" -> {
                String token = connector.getAuthConfig().get("token");
                if (token != null) headers.set("Authorization", "Bearer " + token);
            }
            case "API_KEY" -> {
                String key = connector.getAuthConfig().getOrDefault("headerName", "X-API-Key");
                String keyValue = connector.getAuthConfig().get("key");
                if (keyValue != null) headers.set(key, keyValue);
            }
            case "BASIC" -> {
                String username = connector.getAuthConfig().getOrDefault("username", "");
                String password = connector.getAuthConfig().getOrDefault("password", "");
                String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.set("Authorization", "Basic " + encoded);
            }
            default -> { }
        }
    }

    private void testJdbc(NexusConnector connector, Map<String, Object> result) {
        String jdbcUrl = connector.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            result.put("success", false);
            result.put("message", "JDBC connector has no jdbcUrl");
            return;
        }
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
}
