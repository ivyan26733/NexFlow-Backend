package com.nexflow.nexflow_backend.model.nco;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeContext {
    private String nodeId;
    private String nodeType;
    private NodeStatus status;

    private Map<String, Object> input;

    // Pulse nodes write to success OR failure, not both
    private Map<String, Object> successOutput;
    private Map<String, Object> failureOutput;

    // Non-pulse nodes write here
    private Map<String, Object> output;

    private String errorMessage;
}
