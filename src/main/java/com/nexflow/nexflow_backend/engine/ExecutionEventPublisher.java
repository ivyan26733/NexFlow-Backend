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

    private void publish(String executionId, String nodeId, NodeStatus status, String error, Map<String, Object> nex) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("status", status.name());
        payload.put("error", error != null ? error : "");
        if (nex != null) {
            payload.put("nex", nex);
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

        messagingTemplate.convertAndSend(destination, payload);
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

        messagingTemplate.convertAndSend(destination, payload);
    }
}
