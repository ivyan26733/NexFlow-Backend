package com.nexflow.nexflow_backend.model.dto;

import java.util.List;

/**
 * Request body for POST /api/flows/{flowId}/canvas.
 * Null-safe: null lists are treated as empty.
 */
public record CanvasSaveDto(
    List<FlowNodeDto> nodes,
    List<FlowEdgeDto> edges
) {
    public List<FlowNodeDto> nodes() {
        return nodes != null ? nodes : java.util.Collections.emptyList();
    }

    public List<FlowEdgeDto> edges() {
        return edges != null ? edges : java.util.Collections.emptyList();
    }
}
