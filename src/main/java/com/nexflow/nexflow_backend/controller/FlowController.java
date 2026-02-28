package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.EdgeCondition;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.FlowEdge;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.dto.CanvasSaveDto;
import com.nexflow.nexflow_backend.model.dto.FlowEdgeDto;
import com.nexflow.nexflow_backend.model.dto.FlowNodeDto;
import com.nexflow.nexflow_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowRepository flowRepository;
    private final FlowNodeRepository nodeRepository;
    private final FlowEdgeRepository edgeRepository;
    private final ExecutionRepository executionRepository;

    @GetMapping
    public List<Flow> getAllFlows() {
        return flowRepository.findAll();
    }

    @PostMapping
    public Flow createFlow(@RequestBody Flow flow) {
        if (flow.getSlug() == null || flow.getSlug().isBlank()) {
            flow.setSlug(toSlug(flow.getName()));
        }
        String base = flow.getSlug();
        int counter = 1;
        while (flowRepository.existsBySlug(flow.getSlug())) {
            flow.setSlug(base + "-" + counter++);
        }
        return flowRepository.save(flow);
    }

    /** "Auth Service" → "auth-service" */
    private static String toSlug(String name) {
        if (name == null || name.isBlank()) {
            return "flow-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
    }

    @GetMapping("/{flowId}")
    public ResponseEntity<Flow> getFlow(@PathVariable UUID flowId) {
        return flowRepository.findById(flowId)
                .map(this::ensureSlug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{flowId}")
    public ResponseEntity<Flow> updateFlow(@PathVariable UUID flowId, @RequestBody Map<String, String> body) {
        if (body == null || !body.containsKey("name")) {
            return ResponseEntity.badRequest().build();
        }
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return flowRepository.findById(flowId)
                .map(flow -> {
                    flow.setName(name.trim());
                    return flowRepository.save(flow);
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Backfill slug if missing so trigger-by-slug works for flows created before slug was required. */
    private Flow ensureSlug(Flow flow) {
        if (flow.getSlug() == null || flow.getSlug().isBlank()) {
            String slug = "flow-" + flow.getId().toString().replace("-", "").substring(0, 12);
            if (flowRepository.findBySlug(slug).isEmpty()) {
                flow.setSlug(slug);
            } else {
                flow.setSlug("flow-" + flow.getId().toString().replace("-", ""));
            }
            flowRepository.save(flow);
        }
        return flow;
    }

    // Save the entire canvas in one shot — Studio sends all nodes + edges together
    @Transactional
    @PostMapping("/{flowId}/canvas")
    public ResponseEntity<?> saveCanvas(
            @PathVariable UUID flowId,
            @RequestBody CanvasSaveDto dto) {

        String saveOutputAsError = validateSaveOutputAs(dto.nodes());
        if (saveOutputAsError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", saveOutputAsError));
        }

        nodeRepository.deleteAll(nodeRepository.findByFlowId(flowId));
        edgeRepository.deleteAll(edgeRepository.findByFlowId(flowId));

        for (FlowNodeDto n : dto.nodes()) {
            FlowNode node = toFlowNode(n, flowId);
            nodeRepository.save(node);
        }
        for (FlowEdgeDto e : dto.edges()) {
            FlowEdge edge = toFlowEdge(e, flowId);
            if (edge != null) {
                edgeRepository.save(edge);
            }
        }

        return ResponseEntity.ok().build();
    }

    private static final Set<String> RESERVED_NEX_KEYS = Set.of("nodes", "trigger", "variables", "loop", "meta", "nex");

    /** Validates saveOutputAs across all nodes: key format, reserved words, uniqueness. Returns error message or null. */
    private static String validateSaveOutputAs(List<FlowNodeDto> nodes) {
        if (nodes == null) return null;
        Set<String> seen = new HashSet<>();
        for (FlowNodeDto n : nodes) {
            Object raw = n.config() != null ? n.config().get("saveOutputAs") : null;
            if (raw == null || raw.toString().isBlank()) continue;
            String value = raw.toString().trim();
            String label = n.label() != null ? n.label() : n.id();
            if (!value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                return "Node '" + label + "' has invalid saveOutputAs value '" + value + "'. Use only letters, numbers, underscores, starting with a letter.";
            }
            if (RESERVED_NEX_KEYS.contains(value)) {
                return "Node '" + label + "' has reserved saveOutputAs value '" + value + "'. Cannot use: nodes, trigger, variables, loop, meta, nex.";
            }
            if (seen.contains(value)) {
                return "Two nodes have the same saveOutputAs value '" + value + "'. Each node must have a unique name.";
            }
            seen.add(value);
        }
        return null;
    }

    @GetMapping("/{flowId}/canvas")
    public CanvasSaveRequest getCanvas(@PathVariable UUID flowId) {
        return new CanvasSaveRequest(
                nodeRepository.findByFlowId(flowId),
                edgeRepository.findByFlowId(flowId)
        );
    }

    private static FlowNode toFlowNode(FlowNodeDto dto, UUID flowId) {
        FlowNode n = new FlowNode();
        if (dto.id() != null && !dto.id().isBlank()) {
            n.setId(UUID.fromString(dto.id()));
        }
        n.setFlowId(flowId);
        n.setNodeType(dto.nodeType());
        n.setLabel(dto.label() != null ? dto.label() : "");
        n.setConfig(dto.config() != null ? dto.config() : new java.util.HashMap<>());
        n.setPositionX(dto.positionX() != null ? dto.positionX() : 0.0);
        n.setPositionY(dto.positionY() != null ? dto.positionY() : 0.0);
        return n;
    }

    private static FlowEdge toFlowEdge(FlowEdgeDto dto, UUID flowId) {
        if (dto.sourceNodeId() == null || dto.sourceNodeId().isBlank()
                || dto.targetNodeId() == null || dto.targetNodeId().isBlank()) {
            return null;
        }
        try {
            FlowEdge e = new FlowEdge();
            if (dto.id() != null && !dto.id().isBlank()) {
                e.setId(UUID.fromString(dto.id()));
            }
            e.setFlowId(flowId);
            e.setSourceNodeId(UUID.fromString(dto.sourceNodeId().trim()));
            e.setTargetNodeId(UUID.fromString(dto.targetNodeId().trim()));
            e.setSourceHandle(dto.sourceHandle() != null && !dto.sourceHandle().isBlank() ? dto.sourceHandle().trim() : null);
            e.setTargetHandle(dto.targetHandle() != null && !dto.targetHandle().isBlank() ? dto.targetHandle().trim() : null);
            e.setConditionType(parseEdgeCondition(dto.conditionType()));
            e.setConditionExpr(dto.conditionExpr());
            return e;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static EdgeCondition parseEdgeCondition(String value) {
        if (value == null || value.isBlank()) return EdgeCondition.DEFAULT;
        try {
            return EdgeCondition.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return EdgeCondition.DEFAULT;
        }
    }

    @GetMapping("/{flowId}/executions")
    public List<Execution> getExecutions(@PathVariable UUID flowId) {
        return executionRepository.findByFlowIdOrderByStartedAtDesc(flowId);
    }

    /**
     * Delete a flow (\"studio\") and all of its persisted state:
     * - all executions/transactions for this flow
     * - all nodes & edges in its canvas
     *
     * Pulse endpoints and external APIs are not separate entities here:
     * deleting the Flow simply removes the ability to trigger it; Nexus
     * connectors and other APIs remain untouched.
     */
    @Transactional
    @DeleteMapping("/{flowId}")
    public ResponseEntity<Void> deleteFlow(@PathVariable UUID flowId) {
        if (flowRepository.findById(flowId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        executionRepository.deleteAll(executionRepository.findByFlowIdOrderByStartedAtDesc(flowId));
        nodeRepository.deleteAll(nodeRepository.findByFlowId(flowId));
        edgeRepository.deleteAll(edgeRepository.findByFlowId(flowId));
        flowRepository.deleteById(flowId);
        return ResponseEntity.noContent().build();
    }

    /** Response type for GET canvas; also used internally for the saved entities. */
    public record CanvasSaveRequest(List<FlowNode> nodes, List<FlowEdge> edges) {}
}
