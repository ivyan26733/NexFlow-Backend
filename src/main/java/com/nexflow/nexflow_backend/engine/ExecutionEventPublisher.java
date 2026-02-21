package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExecutionEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    // Studio subscribes to /topic/execution/{executionId} to receive live updates
    private static final String TOPIC = "/topic/execution/";

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
        messagingTemplate.convertAndSend(
                TOPIC + executionId,
                Map.of(
                        "nodeId", nodeId,
                        "status", status.name(),
                        "error",  error != null ? error : ""
                )
        );
    }
}
