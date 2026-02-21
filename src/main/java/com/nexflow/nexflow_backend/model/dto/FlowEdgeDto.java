package com.nexflow.nexflow_backend.model.dto;

/**
 * Incoming DTO for canvas save. Uses String for IDs and conditionType
 * so Jackson deserialization is reliable from the frontend.
 */
public record FlowEdgeDto(
    String id,
    String flowId,
    String sourceNodeId,
    String targetNodeId,
    String sourceHandle,
    String targetHandle,
    String conditionType,
    String conditionExpr
) {}
