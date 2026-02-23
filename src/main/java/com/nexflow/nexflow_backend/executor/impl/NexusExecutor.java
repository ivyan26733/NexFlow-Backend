package com.nexflow.nexflow_backend.executor.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.executor.NodeExecutor;
import com.nexflow.nexflow_backend.executor.ReferenceResolver;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.domain.NexusConnector;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.NexusConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.sql.*;
import java.util.*;

/**
 * Executes NEXUS nodes.
 *
 * Two modes, determined by the connector's connectorType field:
 *
 *   REST  — merges connector's baseUrl + auth + defaultHeaders with the node's
 *           path / method / headers / body config. Fires the HTTP call.
 *
 *   JDBC  — uses connector's jdbcUrl/jdbcDriver/dbUsername/dbPassword to open
 *           a JDBC connection, resolves {{}} refs in the query, executes it,
 *           and returns the result rows as a list of maps.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NexusExecutor implements NodeExecutor {

    private final NexusConnectorRepository connectorRepository;
    private final ReferenceResolver        resolver;
    private final RestTemplate             restTemplate;
    private final ObjectMapper             objectMapper;

    @Override
    public NodeType supportedType() {
        return NodeType.NEXUS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> config    = node.getConfig();
        String              nodeId    = node.getId().toString();
        String              connectorId = (String) config.get("connectorId");

        if (connectorId == null || connectorId.isBlank()) {
            return failureContext(nodeId, null, "NEXUS node has no connectorId configured");
        }

        NexusConnector connector = connectorRepository.findById(UUID.fromString(connectorId))
                .orElse(null);

        if (connector == null) {
            return failureContext(nodeId, null, "Connector not found: " + connectorId);
        }

        return "JDBC".equalsIgnoreCase(connector.getConnectorType())
                ? executeJdbc(nodeId, connector, config, nco)
                : executeRest(nodeId, connector, config, nco);
    }

    // ── REST execution ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private NodeContext executeRest(String nodeId, NexusConnector connector,
                                    Map<String, Object> config, NexflowContextObject nco) {

        // Resolve path and build full URL
        String path   = resolver.resolve((String) config.getOrDefault("path", ""), nco);
        String method = (String) config.getOrDefault("method", "GET");
        String url    = buildUrl(connector.getBaseUrl(), path);

        // Merge headers: connector defaults → node overrides
        Map<String, Object> mergedHeaders = new LinkedHashMap<>();
        if (connector.getDefaultHeaders() != null) mergedHeaders.putAll(connector.getDefaultHeaders());
        Map<String, Object> nodeHeaders = (Map<String, Object>) config.getOrDefault("headers", new HashMap<>());
        mergedHeaders.putAll(resolver.resolveMap(nodeHeaders, nco));

        // Apply auth on top
        applyAuth(connector, mergedHeaders);

        Map<String, Object> body = resolver.resolveMap(
                (Map<String, Object>) config.getOrDefault("body", new HashMap<>()), nco);

        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            mergedHeaders.forEach((k, v) -> httpHeaders.set(k, String.valueOf(v)));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, httpHeaders);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.valueOf(method.toUpperCase()), request, String.class);

            Map<String, Object> successOutput = new LinkedHashMap<>();
            successOutput.put("statusCode", response.getStatusCode().value());
            successOutput.put("body",       parseBody(response.getBody()));
            successOutput.put("headers",    response.getHeaders().toSingleValueMap());

            return NodeContext.builder()
                    .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                    .status(NodeStatus.SUCCESS)
                    .input(Map.of("url", url, "method", method, "body", body))
                    .successOutput(successOutput)
                    .build();

        } catch (HttpStatusCodeException ex) {
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("statusCode", ex.getStatusCode().value());
            failureOutput.put("body",       parseBody(ex.getResponseBodyAsString()));
            failureOutput.put("error",      ex.getMessage());

            return NodeContext.builder()
                    .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                    .status(NodeStatus.FAILURE)
                    .input(Map.of("url", url, "method", method, "body", body))
                    .failureOutput(failureOutput)
                    .errorMessage(ex.getMessage())
                    .build();

        } catch (Exception ex) {
            log.error("NEXUS REST node {} failed: {}", nodeId, ex.getMessage());
            return failureContext(nodeId, Map.of("url", url, "method", method), ex.getMessage());
        }
    }

    // ── JDBC execution ────────────────────────────────────────────────────────

    private NodeContext executeJdbc(String nodeId, NexusConnector connector,
                                    Map<String, Object> config, NexflowContextObject nco) {

        String rawQuery   = (String) config.getOrDefault("query", "");
        String queryType  = (String) config.getOrDefault("queryType", "SELECT");
        String query      = resolver.resolve(rawQuery, nco);

        if (query.isBlank()) {
            return failureContext(nodeId, Map.of("query", rawQuery), "NEXUS JDBC node has no query configured");
        }

        // Load driver class if specified (driver jar must be on the classpath)
        if (connector.getJdbcDriver() != null && !connector.getJdbcDriver().isBlank()) {
            try { Class.forName(connector.getJdbcDriver()); }
            catch (ClassNotFoundException ex) {
                return failureContext(nodeId, Map.of("query", query),
                        "JDBC driver not found on classpath: " + connector.getJdbcDriver()
                        + ". Add the driver dependency to pom.xml.");
            }
        }

        try (Connection conn = DriverManager.getConnection(
                connector.getJdbcUrl(),
                connector.getDbUsername(),
                connector.getDbPassword())) {

            Map<String, Object> input = Map.of("query", query, "queryType", queryType);

            if ("SELECT".equalsIgnoreCase(queryType)) {
                List<Map<String, Object>> rows = executeSelect(conn, query);

                Map<String, Object> successOutput = new LinkedHashMap<>();
                successOutput.put("rows",     rows);
                successOutput.put("rowCount", rows.size());
                successOutput.put("query",    query);

                return NodeContext.builder()
                        .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                        .status(NodeStatus.SUCCESS)
                        .input(input).successOutput(successOutput)
                        .build();

            } else {
                // INSERT / UPDATE / DELETE
                int affected = executeUpdate(conn, query);

                Map<String, Object> successOutput = new LinkedHashMap<>();
                successOutput.put("rowsAffected", affected);
                successOutput.put("query",        query);
                successOutput.put("queryType",    queryType);

                return NodeContext.builder()
                        .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                        .status(NodeStatus.SUCCESS)
                        .input(input).successOutput(successOutput)
                        .build();
            }

        } catch (SQLException ex) {
            log.error("NEXUS JDBC node {} failed: {}", nodeId, ex.getMessage());
            return failureContext(nodeId, Map.of("query", query, "queryType", queryType),
                    "SQL error: " + ex.getMessage());
        }
    }

    private List<Map<String, Object>> executeSelect(Connection conn, String query) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs   = stmt.executeQuery(query)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private int executeUpdate(Connection conn, String query) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(query);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyAuth(NexusConnector connector, Map<String, Object> headers) {
        if (connector.getAuthConfig() == null || "NONE".equalsIgnoreCase(connector.getAuthType())) return;

        switch (connector.getAuthType().toUpperCase()) {
            case "BEARER" -> {
                String token = connector.getAuthConfig().get("token");
                if (token != null) headers.put("Authorization", "Bearer " + token);
            }
            case "API_KEY" -> {
                String key      = connector.getAuthConfig().getOrDefault("headerName", "X-API-Key");
                String keyValue = connector.getAuthConfig().get("key");
                if (keyValue != null) headers.put(key, keyValue);
            }
            case "BASIC" -> {
                String username = connector.getAuthConfig().getOrDefault("username", "");
                String password = connector.getAuthConfig().getOrDefault("password", "");
                String encoded  = Base64.getEncoder()
                        .encodeToString((username + ":" + password).getBytes());
                headers.put("Authorization", "Basic " + encoded);
            }
        }
    }

    private String buildUrl(String baseUrl, String path) {
        if (baseUrl == null) return path;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p    = (path == null || path.isBlank()) ? "" : (path.startsWith("/") ? path : "/" + path);
        return base + p;
    }

    @SuppressWarnings("unchecked")
    private Object parseBody(String body) {
        if (body == null || body.isBlank()) return null;
        try { return objectMapper.readValue(body, Map.class); }
        catch (Exception e) { return body; }
    }

    private NodeContext failureContext(String nodeId, Map<String, Object> input, String error) {
        Map<String, Object> failureOutput = new LinkedHashMap<>();
        failureOutput.put("error", error);
        return NodeContext.builder()
                .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                .status(NodeStatus.FAILURE)
                .input(input != null ? input : Map.of())
                .failureOutput(failureOutput)
                .errorMessage(error)
                .build();
    }
}
