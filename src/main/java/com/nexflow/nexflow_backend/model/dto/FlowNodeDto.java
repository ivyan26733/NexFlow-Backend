package com.nexflow.nexflow_backend.model.dto;

import com.nexflow.nexflow_backend.model.domain.NodeType;

import java.util.Map;

/**
 * Incoming DTO for canvas save. Uses types that Jackson deserializes reliably.
 */
public record FlowNodeDto(
    String id,
    String flowId,
    NodeType nodeType,
    String label,
    Map<String, Object> config,
    Double positionX,
    Double positionY
) {}
