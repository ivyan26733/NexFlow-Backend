package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ExecutionEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<RedisWebSocketBridge> redisBridgeProvider;

    // Studio subscribes to /topic/execution/{executionId} to receive live updates
    private static final String TOPIC = "/topic/execution/";

    public ExecutionEventPublisher(SimpMessagingTemplate messagingTemplate,
                                  ObjectProvider<RedisWebSocketBridge> redisBridgeProvider) {
        this.messagingTemplate = messagingTemplate;
        this.redisBridgeProvider = redisBridgeProvider;
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
        RedisWebSocketBridge bridge = redisBridgeProvider.getIfAvailable();
        log.info("[WS-DEBUG] ExecutionEventPublisher.publish: destination={}, nodeId={}, status={}, via={}",
                destination, nodeId, status, bridge != null ? "Redis" : "Direct");
        if (bridge != null) {
            bridge.publish(destination, payload);
        } else {
            messagingTemplate.convertAndSend(destination, payload);
        }
    }
}
