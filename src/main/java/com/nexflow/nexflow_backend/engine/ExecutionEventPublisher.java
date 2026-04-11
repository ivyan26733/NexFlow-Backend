package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionEventPublisher {

    private static final String TOPIC_PREFIX = "/queue/execution.";
    /** Separate queue prefix so branch events don't mix with node events. */
    private static final String BRANCH_PREFIX = "/queue/branch.";

    private final SimpMessagingTemplate messagingTemplate;

    public void nodeStarted(String executionId, String nodeId) {
        publish(executionId, nodeId, NodeStatus.RUNNING, null, null);
    }

    public void nodeCompleted(String executionId, String nodeId, NodeStatus status) {
        publish(executionId, nodeId, status, null, null);
    }

    /** Node completed with current nex state for live frontend display. */
    public void nodeCompleted(String executionId, String nodeId, NodeStatus status, Map<String, Object> nex) {
        publish(executionId, nodeId, status, null, nex);
    }

    /**
     * Called when a node is scheduled to be retried after a failure.
     * Frontend treats this as an in-progress state distinct from RUNNING.
     */
    public void nodeRetrying(String executionId, String nodeId) {
        publish(executionId, nodeId, NodeStatus.RETRYING, null, null);
    }

    public void nodeError(String executionId, String nodeId, String error) {
        publish(executionId, nodeId, NodeStatus.FAILURE, error, null);
    }

    /**
     * Max bytes allowed per nex value in a WebSocket event.
     * Values larger than this are replaced with a stub so a single large HTTP
     * response body does not bloat thousands of RabbitMQ messages during a loop.
     */
    private static final int MAX_NEX_VALUE_BYTES = 2048;

    /**
     * Produces a "safe" copy of the nex map where values whose JSON representation
     * exceeds MAX_NEX_VALUE_BYTES are replaced with a small stub object.
     * This keeps WebSocket frames small even when the loop body calls an API that
     * returns large HTML / JSON payloads.
     */
    private Map<String, Object> capNex(Map<String, Object> nex) {
        if (nex == null || nex.isEmpty()) return nex;
        Map<String, Object> capped = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : nex.entrySet()) {
            Object v = entry.getValue();
            String json;
            try {
                json = v instanceof String s ? s : v.toString();
            } catch (Exception e) {
                json = "";
            }
            if (json.length() > MAX_NEX_VALUE_BYTES) {
                capped.put(entry.getKey(), Map.of("_truncated", true, "_bytes", json.length()));
            } else {
                capped.put(entry.getKey(), v);
            }
        }
        return capped;
    }

    private void publish(String executionId, String nodeId, NodeStatus status, String error, Map<String, Object> nex) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("status", status.name());
        payload.put("error", error != null ? error : "");
        if (nex != null) {
            payload.put("nex", capNex(nex));
        }
        String destination = TOPIC_PREFIX + executionId;

        // Detailed log so we can see exactly what is being published in prod/local.
        // NOTE: nex can be large, so only log its keys/size rather than full JSON.
        if (log.isInfoEnabled()) {
            int nexSize = nex != null ? nex.size() : 0;
            log.info(
                "[ExecutionEventPublisher] sending event dest='{}' executionId={} nodeId={} status={} errorPresent={} nexSize={}",
                destination,
                executionId,
                nodeId,
                status.name(),
                error != null && !error.isBlank(),
                nexSize
            );
        }

        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.warn("[ExecutionEventPublisher] broker unavailable, event dropped dest='{}': {}", destination, e.getMessage());
        }
    }

    // ── Branch events ─────────────────────────────────────────────────────────

    public void branchStarted(String executionId, String forkNodeId, String branchName) {
        publishBranchEvent(executionId, forkNodeId, branchName, "RUNNING", null, null);
    }

    public void branchCompleted(
            String executionId,
            String forkNodeId,
            String branchName,
            NodeStatus status,
            Long durationMs,
            String error) {
        publishBranchEvent(executionId, forkNodeId, branchName, status.name(), durationMs, error);
    }

    private void publishBranchEvent(
            String executionId,
            String forkNodeId,
            String branchName,
            String status,
            Long durationMs,
            String error) {

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type",        "BRANCH_EVENT");
        payload.put("executionId", executionId);
        payload.put("forkNodeId",  forkNodeId);
        payload.put("branchName",  branchName);
        payload.put("status",      status);
        if (durationMs != null) {
            payload.put("durationMs", durationMs);
        }
        if (error != null) {
            payload.put("error", error);
        }

        String destination = BRANCH_PREFIX + executionId;

        if (log.isInfoEnabled()) {
            log.info(
                "[ExecutionEventPublisher] branch event dest='{}' forkNodeId={} branchName={} status={}",
                destination,
                forkNodeId,
                branchName,
                status
            );
        }

        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.warn("[ExecutionEventPublisher] broker unavailable, branch event dropped dest='{}': {}", destination, e.getMessage());
        }
    }
}
