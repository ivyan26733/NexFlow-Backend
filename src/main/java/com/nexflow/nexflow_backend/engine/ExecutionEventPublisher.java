package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExecutionEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisWebSocketBridge redisBridge;

    // Studio subscribes to /topic/execution/{executionId} to receive live updates
    private static final String TOPIC = "/topic/execution/";

    public ExecutionEventPublisher(SimpMessagingTemplate messagingTemplate,
                                  @Autowired(required = false) RedisWebSocketBridge redisBridge) {
        this.messagingTemplate = messagingTemplate;
        this.redisBridge = redisBridge;
    }

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
                "error",  error != null ? error : ""
        );
        String destination = TOPIC + executionId;
        if (redisBridge != null) {
            redisBridge.publish(destination, payload);
        } else {
            messagingTemplate.convertAndSend(destination, payload);
        }
    }
}
