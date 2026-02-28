package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes node execution events to WebSocket subscribers via the in-memory broker.
 * Studio subscribes to /topic/execution/{executionId} to show live node status.
 */
@Component
@RequiredArgsConstructor
public class ExecutionEventPublisher {

    private static final String TOPIC_PREFIX = "/topic/execution/";

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

    private void publish(String executionId, String nodeId, NodeStatus status, String error, Map<String, Object> nex) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("status", status.name());
        payload.put("error", error != null ? error : "");
        if (nex != null) {
            payload.put("nex", nex);
        }
        String destination = TOPIC_PREFIX + executionId;
        messagingTemplate.convertAndSend(destination, payload);
    }
}
