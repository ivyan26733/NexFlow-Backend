package com.nexflow.nexflow_backend.model.nco;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeContext {
    private String nodeId;
    private String nodeType;
    private NodeStatus status;

    private Map<String, Object> input;

    // Nodes with two outcomes write to success OR failure, not both
    private Map<String, Object> successOutput;
    private Map<String, Object> failureOutput;

    // Single-outcome nodes write here
    private Map<String, Object> output;

    /** Plain-language explanation shown to users in the transaction view. */
    private String errorMessage;

    /** Raw technical error (exception text, HTTP body…) kept for developers. */
    private String errorDetail;

    /**
     * A DECISION whose condition evaluated to false reports FAILURE (so the "false" edge is followed)
     * but it is not an error: there is no error message. Used by the engine to tell the two apart.
     */
    @JsonIgnore
    public boolean isConditionFalse() {
        return status == NodeStatus.FAILURE && "DECISION".equals(nodeType) && errorMessage == null;
    }

    /** True when the node genuinely failed (not just a false decision). */
    @JsonIgnore
    public boolean isError() {
        return status == NodeStatus.FAILURE && !isConditionFalse();
    }
}
