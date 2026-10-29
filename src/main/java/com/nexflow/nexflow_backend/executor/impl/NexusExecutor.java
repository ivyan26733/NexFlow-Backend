package com.nexflow.nexflow_backend.executor.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import com.nexflow.nexflow_backend.engine.FriendlyErrors;
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

    /** Short driver names from Nexus UI (e.g. "postgresql") → JDBC Driver class name for Class.forName(). */
    private static final Map<String, String> JDBC_DRIVER_CLASSES = Map.of(
        "postgresql", "org.postgresql.Driver",
        "mysql",      "com.mysql.cj.jdbc.Driver",
        "mariadb",    "org.mariadb.jdbc.Driver",
        "oracle",     "oracle.jdbc.OracleDriver",
        "sqlserver",  "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    );

    private final NexusConnectorRepository connectorRepository;
    private final ReferenceResolver        resolver;
    private final RestTemplate             restTemplate;
    private final ObjectMapper             objectMapper;

    @Override
    public NodeType supportedType() {
        return NodeType.NEXUS;
    }

    /**
     * Two modes:
     * 1) Inline HTTP: no connectorId; config has url, method, headers, body (same shape as legacy HTTP Call node).
     * 2) Connector: connectorId set → load NexusConnector and run REST or JDBC.
     */

    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> config      = node.getConfig();
        String              nodeId      = node.getId().toString();
        String              connectorId = config != null ? (String) config.get("connectorId") : null;

        if (connectorId == null || connectorId.isBlank()) {
            return executeInlineRest(nodeId, config, nco);
        }

        NexusConnector connector = connectorRepository.findById(UUID.fromString(connectorId))
                .orElse(null);

        if (connector == null) {
            return failureContext(nodeId, null, "The saved connector this step uses no longer exists. Pick another connector.", null);
        }

        return "JDBC".equalsIgnoreCase(connector.getConnectorType())
                ? executeJdbc(nodeId, connector, config, nco)
                : executeRest(nodeId, connector, config, nco);
    }

    /** Inline HTTP (no connector): config has url, method, headers, body. */
    @SuppressWarnings("unchecked")
    private NodeContext executeInlineRest(String nodeId, Map<String, Object> config, NexflowContextObject nco) {
        if (config == null) return failureContext(nodeId, null, "This step has no request configured yet.", null);
        String url = resolver.resolve((String) config.get("url"), nco);
        if (url == null || url.isBlank()) {
            return failureContext(nodeId, null, "This step has no URL. Open it and enter the address to call.", null);
        }
        Map<String, Object> headers = resolver.resolveMap((Map<String, Object>) config.getOrDefault("headers", new HashMap<>()), nco);
        Map<String, Object> body = resolver.resolveMap((Map<String, Object>) config.getOrDefault("body", new HashMap<>()), nco);
        return callHttp(nodeId, url, (String) config.getOrDefault("method", "GET"), headers, body);
    }

    // ── REST execution ────────────────────────────────────────────────────────

    /** Connector REST: connector baseUrl + auth + default headers, overridden by the node's path/headers/body. */
    @SuppressWarnings("unchecked")
    private NodeContext executeRest(String nodeId, NexusConnector connector,
                                    Map<String, Object> config, NexflowContextObject nco) {
        String path = resolver.resolve((String) config.getOrDefault("path", ""), nco);
        Map<String, Object> headers = new LinkedHashMap<>();
        if (connector.getDefaultHeaders() != null) headers.putAll(connector.getDefaultHeaders());
        headers.putAll(resolver.resolveMap((Map<String, Object>) config.getOrDefault("headers", new HashMap<>()), nco));
        applyAuth(connector, headers);
        Map<String, Object> body = resolver.resolveMap((Map<String, Object>) config.getOrDefault("body", new HashMap<>()), nco);
        return callHttp(nodeId, buildUrl(connector.getBaseUrl(), path), (String) config.getOrDefault("method", "GET"), headers, body);
    }

    /** Fires one HTTP call. 2xx → SUCCESS {statusCode, body, headers}; anything else → FAILURE with a friendly reason. */
    private NodeContext callHttp(String nodeId, String url, String method,
                                 Map<String, Object> headers, Map<String, Object> body) {
        Map<String, Object> input = Map.of("url", url, "method", method, "body", body);
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            headers.forEach((k, v) -> httpHeaders.set(k, String.valueOf(v)));
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.valueOf(method.toUpperCase()), new HttpEntity<>(body, httpHeaders), String.class);

            Map<String, Object> successOutput = new LinkedHashMap<>();
            successOutput.put("statusCode", response.getStatusCode().value());
            successOutput.put("body", parseBody(response.getBody()));
            successOutput.put("headers", response.getHeaders().toSingleValueMap());
            return NodeContext.builder()
                    .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                    .status(NodeStatus.SUCCESS)
                    .input(input)
                    .successOutput(successOutput)
                    .build();
        } catch (HttpStatusCodeException ex) {
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("statusCode", ex.getStatusCode().value());
            failureOutput.put("body", parseBody(ex.getResponseBodyAsString()));
            failureOutput.put("error", ex.getMessage());
            return NodeContext.builder()
                    .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                    .status(NodeStatus.FAILURE)
                    .input(input)
                    .failureOutput(failureOutput)
                    .errorMessage(FriendlyErrors.forHttpStatus(ex.getStatusCode().value(), url))
                    .errorDetail(ex.getMessage())
                    .build();
        } catch (Exception ex) {
            log.warn("NEXUS {} {} failed: {}", method, url, ex.getMessage());
            return failureContext(nodeId, input, FriendlyErrors.forException(ex, url), ex.getMessage());
        }
    }

    // ── JDBC execution ────────────────────────────────────────────────────────

    private NodeContext executeJdbc(String nodeId, NexusConnector connector,
                                    Map<String, Object> config, NexflowContextObject nco) {

        String rawQuery   = (String) config.getOrDefault("query", "");
        String queryType  = (String) config.getOrDefault("queryType", "SELECT");
        String query      = resolver.resolve(rawQuery, nco);

        if (query.isBlank()) {
            return failureContext(nodeId, Map.of("query", rawQuery), "This database step has no query yet. Open it and write the SQL.", null);
        }

        // Load driver class (Nexus UI stores short name e.g. "postgresql" → map to org.postgresql.Driver)
        String driverKey = connector.getJdbcDriver() != null ? connector.getJdbcDriver().trim() : null;
        if (driverKey != null && !driverKey.isBlank()) {
            String driverClass = JDBC_DRIVER_CLASSES.getOrDefault(driverKey.toLowerCase(Locale.ROOT), driverKey);
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException ex) {
                return failureContext(nodeId, Map.of("query", query),
                        "This server can't talk to that kind of database yet (driver " + driverClass + " isn't installed).",
                        "ClassNotFoundException: " + driverClass);
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
                    FriendlyErrors.forSql(ex), "SQLState " + ex.getSQLState() + ": " + ex.getMessage());
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

    /** JSON (object or array) when the body parses, otherwise the raw text. */
    private Object parseBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception e) {
            return body;
        }
    }

    private static NodeContext failureContext(String nodeId, Map<String, Object> input, String message, String detail) {
        Map<String, Object> failureOutput = new LinkedHashMap<>();
        failureOutput.put("error", detail != null ? detail : message);
        return NodeContext.builder()
                .nodeId(nodeId).nodeType(NodeType.NEXUS.name())
                .status(NodeStatus.FAILURE)
                .input(input != null ? input : Map.of())
                .failureOutput(failureOutput)
                .errorMessage(message)
                .errorDetail(detail != null ? detail : message)
                .build();
    }
}
