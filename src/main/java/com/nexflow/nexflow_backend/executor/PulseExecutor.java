package com.nexflow.nexflow_backend.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PulseExecutor implements NodeExecutor {

    private final ReferenceResolver resolver;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType supportedType() {
        return NodeType.PULSE;
    }

    /*
     * Config shape:
     * {
     *   "url":    "https://api.example.com/users",
     *   "method": "POST",
     *   "headers": { "Authorization": "Bearer {{variables.token}}" },
     *   "body":    { "userId": "{{variables.userId}}" }
     * }
     */
    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> config = node.getConfig();
        String nodeId = node.getId().toString();

        String url    = resolver.resolve((String) config.get("url"), nco);
        String method = (String) config.getOrDefault("method", "GET");

        Map<String, Object> headers = resolver.resolveMap((Map<String, Object>) config.getOrDefault("headers", new HashMap<>()), nco);
        Map<String, Object> body    = resolver.resolveMap((Map<String, Object>) config.getOrDefault("body", new HashMap<>()), nco);

        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            headers.forEach((k, v) -> httpHeaders.set(k, v.toString()));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, httpHeaders);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.valueOf(method), request, String.class);

            Map<String, Object> successOutput = new HashMap<>();
            successOutput.put("statusCode", response.getStatusCode().value());
            successOutput.put("body", parseBody(response.getBody()));
            successOutput.put("headers", response.getHeaders().toSingleValueMap());

            return NodeContext.builder()
                    .nodeId(nodeId)
                    .nodeType(NodeType.PULSE.name())
                    .status(NodeStatus.SUCCESS)
                    .input(body)
                    .successOutput(successOutput)
                    .build();

        } catch (HttpStatusCodeException ex) {
            Map<String, Object> failureOutput = new HashMap<>();
            failureOutput.put("statusCode", ex.getStatusCode().value());
            failureOutput.put("body", parseBody(ex.getResponseBodyAsString()));
            failureOutput.put("error", ex.getMessage());

            return NodeContext.builder()
                    .nodeId(nodeId)
                    .nodeType(NodeType.PULSE.name())
                    .status(NodeStatus.FAILURE)
                    .input(body)
                    .failureOutput(failureOutput)
                    .errorMessage(ex.getMessage())
                    .build();

        } catch (Exception ex) {
            log.error("Pulse node {} failed unexpectedly: {}", nodeId, ex.getMessage());

            Map<String, Object> failureOutput = new HashMap<>();
            failureOutput.put("error", ex.getMessage());

            return NodeContext.builder()
                    .nodeId(nodeId)
                    .nodeType(NodeType.PULSE.name())
                    .status(NodeStatus.FAILURE)
                    .input(body)
                    .failureOutput(failureOutput)
                    .errorMessage(ex.getMessage())
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            return body; // return raw string if not JSON
        }
    }
}
