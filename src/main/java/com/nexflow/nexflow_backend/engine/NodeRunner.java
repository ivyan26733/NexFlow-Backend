package com.nexflow.nexflow_backend.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.executor.NodeExecutorRegistry;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.model.nco.RetryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a single node: calls its executor, retries real failures according to the node's retry
 * policy, and makes sure every failure carries a plain-language message.
 *
 * Only the result of the final attempt is returned, so the engine never routes on an attempt
 * that is going to be retried.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeRunner {

    private static final int MAX_RETRIES = 10;

    private final NodeExecutorRegistry executors;
    private final ObjectMapper objectMapper;

    public NodeContext run(FlowNode node, NexflowContextObject nco) {
        RetryConfig retry = retryConfig(node);
        long delayMs = retry.getBackoffMs();
        int attempt = 0;

        while (true) {
            attempt++;
            Throwable thrown = null;
            NodeContext result;
            try {
                result = executors.get(node.getNodeType()).execute(node, nco);
            } catch (Exception ex) {
                thrown = ex;
                result = failure(node, ex.getMessage());
            }

            if (!result.isError()) return result;

            if (attempt > retry.getMaxRetries() || Thread.currentThread().isInterrupted()) {
                NodeContext described = FriendlyErrors.describe(node, result, thrown);
                if (attempt > 1) {
                    described = described.toBuilder()
                            .errorMessage(described.getErrorMessage() + " (tried " + attempt + " times)")
                            .build();
                }
                return described;
            }

            log.warn("Node '{}' failed on attempt {}/{} — retrying in {} ms: {}",
                    node.displayName(), attempt, retry.getMaxRetries() + 1, delayMs, result.getErrorMessage());
            if (!sleep(delayMs)) return FriendlyErrors.describe(node, result, thrown);
            delayMs = (long) (delayMs * retry.getBackoffMultiplier());
        }
    }

    private RetryConfig retryConfig(FlowNode node) {
        RetryConfig retry;
        try {
            Object raw = node.configValue("retry", null);
            retry = raw != null ? objectMapper.convertValue(raw, RetryConfig.class) : new RetryConfig();
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring invalid retry config on node '{}': {}", node.displayName(), ex.getMessage());
            retry = new RetryConfig();
        }
        retry.setMaxRetries(Math.max(0, Math.min(MAX_RETRIES, retry.getMaxRetries())));
        if (retry.getBackoffMs() <= 0) retry.setBackoffMs(1000L);
        if (retry.getBackoffMultiplier() <= 0) retry.setBackoffMultiplier(1.0d);
        return retry;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static NodeContext failure(FlowNode node, String message) {
        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(node.getNodeType().name())
                .status(NodeStatus.FAILURE)
                .errorMessage(message != null ? message : "Unexpected error")
                .build();
    }
}
