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
        publish(executionId, nodeId, NodeStatus.RUNNING, null);
    }

    public void nodeCompleted(String executionId, String nodeId, NodeStatus status) {
        publish(executionId, nodeId, status, null);
    }

    public void nodeError(String executionId, String nodeId, String error) {
        publish(executionId, nodeId, NodeStatus.FAILURE, error);
    }

    private void publish(String executionId, String nodeId, NodeStatus status, String error) {
        Map<String, Object> payload = Map.of(
                "nodeId", nodeId,
                "status", status.name(),
                "error", error != null ? error : ""
        );
        String destination = TOPIC_PREFIX + executionId;
        messagingTemplate.convertAndSend(destination, payload);
    }
}
