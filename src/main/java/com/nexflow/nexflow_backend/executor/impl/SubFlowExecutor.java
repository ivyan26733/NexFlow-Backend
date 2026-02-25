package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.executor.NodeExecutor;
import com.nexflow.nexflow_backend.executor.ReferenceResolver;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.FlowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes SUB_FLOW nodes.
 *
 * Config shape stored in FlowNode.config:
 * {
 *   "targetFlowId":   "uuid-of-child-flow",
 *   "targetFlowName": "My Child Flow",       // denormalized for display
 *   "mode":           "SYNC" | "ASYNC",
 *
 *   // Optional payload to send to child flow's START node.
 *   // Values support {{}} reference resolution against the parent NCO.
 *   "payload": {
 *     "userId":  "{{variables.userId}}",
 *     "orderId": "{{nodes.someNode.successOutput.body.id}}"
 *   }
 * }
 *
 * SYNC behaviour:
 *   - Calls FlowService.triggerFlow() — blocks until child execution completes.
 *   - Child's final NCO snapshot is embedded in successOutput so the parent
 *     flow can reference child node outputs:
 *     {{nodes.subFlowNodeId.successOutput.nco.nodes.childNodeId.successOutput.body.field}}
 *   - If child flow ends in FAILURE → this node returns FAILURE → parent routes via failure edge.
 *
 * ASYNC behaviour:
 *   - Calls FlowService.triggerFlowAsync() — returns immediately.
 *   - successOutput contains only { "executionId": "...", "status": "TRIGGERED" }.
 *   - Parent flow continues immediately; no child result is available.
 *   - Node always returns SUCCESS so the parent can continue on the success edge.
 */
@Slf4j
@Component
public class SubFlowExecutor implements NodeExecutor {

    private final FlowService      flowService;
    private final FlowRepository   flowRepository;
    private final ReferenceResolver resolver;

    public SubFlowExecutor(@Lazy FlowService flowService, FlowRepository flowRepository, ReferenceResolver resolver) {
        this.flowService = flowService;
        this.flowRepository = flowRepository;
        this.resolver = resolver;
    }

    @Override
    public NodeType supportedType() {
        return NodeType.SUB_FLOW;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> config  = node.getConfig();
        String              nodeId  = node.getId().toString();

        // ── Validate config ──────────────────────────────────────────────────
        String targetFlowId = (String) config.get("targetFlowId");
        if (targetFlowId == null || targetFlowId.isBlank()) {
            return failure(nodeId, null, "SUB_FLOW node has no targetFlowId configured");
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(targetFlowId);
        } catch (IllegalArgumentException ex) {
            return failure(nodeId, null, "SUB_FLOW node has invalid targetFlowId: " + targetFlowId);
        }

        Flow targetFlow = flowRepository.findById(targetUuid).orElse(null);
        if (targetFlow == null) {
            return failure(nodeId, null, "Target flow not found: " + targetFlowId);
        }

        // Prevent infinite loops — a flow cannot call itself
        if (targetUuid.toString().equals(nco.getMeta().getFlowId())) {
            return failure(nodeId, null,
                "Circular reference: flow cannot call itself. Use a different target flow.");
        }

        String mode = ((String) config.getOrDefault("mode", "SYNC")).toUpperCase();

        // ── Build payload for child flow ─────────────────────────────────────
        Map<String, Object> rawPayload = (Map<String, Object>) config.getOrDefault("payload", new HashMap<>());
        Map<String, Object> resolvedPayload = resolver.resolveMap(rawPayload, nco);

        // If no payload configured, pass parent's trigger body so child receives input.trigger.body by default
        if (resolvedPayload.isEmpty()) {
            NodeContext startCtx = nco.getNodeOutput("start");
            if (startCtx != null && startCtx.getOutput() != null && startCtx.getOutput().get("body") instanceof Map<?, ?> body) {
                resolvedPayload = new HashMap<>((Map<String, Object>) body);
                log.info("[SUB-FLOW] empty payload config — passing parent trigger body to child: {}", resolvedPayload);
            }
        }

        log.info("[SUB-FLOW] triggering child flowId={}, targetFlowName={}, rawPayload={}, resolvedPayload={} (child receives this as input.trigger.body)",
                targetFlowId, targetFlow.getName(), rawPayload, resolvedPayload);

        String triggeredBy = "SUB_FLOW:" + nco.getMeta().getExecutionId();
        Map<String, Object> inputSnapshot = Map.of(
            "targetFlowId",   targetFlowId,
            "targetFlowName", targetFlow.getName(),
            "mode",           mode,
            "payload",        resolvedPayload
        );

        // ── ASYNC — fire and forget ──────────────────────────────────────────
        if ("ASYNC".equals(mode)) {
            // Create the execution record synchronously so we have an ID to return
            Execution stub = new Execution();  // FlowService.triggerFlowAsync creates its own, but we
            // just fire it — the executionId is not pre-knowable in async, so we return TRIGGERED.
            flowService.triggerFlowAsync(targetUuid, resolvedPayload, triggeredBy);

            Map<String, Object> successOutput = new LinkedHashMap<>();
            successOutput.put("status",           "TRIGGERED");
            successOutput.put("mode",             "ASYNC");
            successOutput.put("targetFlowId",     targetFlowId);
            successOutput.put("targetFlowName",   targetFlow.getName());

            return NodeContext.builder()
                    .nodeId(nodeId)
                    .nodeType(NodeType.SUB_FLOW.name())
                    .status(NodeStatus.SUCCESS)
                    .input(inputSnapshot)
                    .successOutput(successOutput)
                    .build();
        }

        // ── SYNC — block until child completes ───────────────────────────────
        try {
            Execution childExecution = flowService.triggerFlowSync(targetUuid, resolvedPayload, triggeredBy);
            boolean   childSucceeded = "SUCCESS".equals(childExecution.getStatus().name());

            Map<String, Object> childSnapshot = childExecution.getNcoSnapshot();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("executionId",   childExecution.getId().toString());
            output.put("status",        childExecution.getStatus().name());
            output.put("mode",          "SYNC");
            output.put("targetFlowId",  targetFlowId);
            output.put("targetFlowName", targetFlow.getName());
            output.put("nco",           childSnapshot);
            // Convenience: last SCRIPT (or any) node's successOutput.result so parent can use input.nodes.subFlow.successOutput.result
            output.put("result",        extractChildResult(childSnapshot));

            NodeStatus resultStatus = childSucceeded ? NodeStatus.SUCCESS : NodeStatus.FAILURE;

            NodeContext.NodeContextBuilder builder = NodeContext.builder()
                    .nodeId(nodeId)
                    .nodeType(NodeType.SUB_FLOW.name())
                    .status(resultStatus)
                    .input(inputSnapshot);

            if (childSucceeded) {
                builder.successOutput(output);
            } else {
                builder.failureOutput(output)
                       .errorMessage("Child flow ended with status: " + childExecution.getStatus().name());
            }

            return builder.build();

        } catch (Exception ex) {
            log.error("SUB_FLOW node {} — child flow {} failed: {}", nodeId, targetFlowId, ex.getMessage());
            return failure(nodeId, inputSnapshot, "Child flow execution threw: " + ex.getMessage());
        }
    }

    /**
     * Extracts a single "result" from the child NCO for parent scripts.
     * Uses the last node in execution order that has successOutput.result (e.g. a SCRIPT node's return value).
     * If that value is an object { result: x }, unwraps to x so parent gets the inner value directly.
     */
    @SuppressWarnings("unchecked")
    private Object extractChildResult(Map<String, Object> childSnapshot) {
        if (childSnapshot == null) return null;
        Object orderObj = childSnapshot.get("nodeExecutionOrder");
        Object nodesObj = childSnapshot.get("nodes");
        if (!(orderObj instanceof List<?>) || !(nodesObj instanceof Map<?, ?> nodes)) return null;
        List<String> nodeOrder = (List<String>) orderObj;
        for (int i = nodeOrder.size() - 1; i >= 0; i--) {
            Object nodeObj = nodes.get(nodeOrder.get(i));
            if (!(nodeObj instanceof Map<?, ?> node)) continue;
            Object successOutput = node.get("successOutput");
            if (!(successOutput instanceof Map<?, ?> so)) continue;
            Object result = so.get("result");
            if (result == null) continue;
            // Script nodes return { result: value }; unwrap so parent gets value directly
            if (result instanceof Map<?, ?> map && map.containsKey("result")) {
                return map.get("result");
            }
            return result;
        }
        return null;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private NodeContext failure(String nodeId, Map<String, Object> input, String error) {
        Map<String, Object> failureOutput = new LinkedHashMap<>();
        failureOutput.put("error", error);
        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.SUB_FLOW.name())
                .status(NodeStatus.FAILURE)
                .input(input != null ? input : Map.of())
                .failureOutput(failureOutput)
                .errorMessage(error)
                .build();
    }
}
