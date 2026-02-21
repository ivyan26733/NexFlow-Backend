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

import java.util.List;
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
        return flowRepository.save(flow);
    }

    @GetMapping("/{flowId}")
    public ResponseEntity<Flow> getFlow(@PathVariable UUID flowId) {
        return flowRepository.findById(flowId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Save the entire canvas in one shot — Studio sends all nodes + edges together
    @Transactional
    @PostMapping("/{flowId}/canvas")
    public ResponseEntity<Void> saveCanvas(
            @PathVariable UUID flowId,
            @RequestBody CanvasSaveDto dto) {

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

    /** Response type for GET canvas; also used internally for the saved entities. */
    public record CanvasSaveRequest(List<FlowNode> nodes, List<FlowEdge> edges) {}
}
