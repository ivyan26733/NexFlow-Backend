package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FriendlyErrorsTest {

    @Test
    void readTimeoutNamesTheHostAndTheLimit() {
        var ex = new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"));
        assertThat(FriendlyErrors.forException(ex, "https://api.example.com/v1/orders"))
                .isEqualTo("The server at api.example.com didn't respond within 30 seconds.");
    }

    @Test
    void unknownHostSuggestsATypo() {
        var ex = new ResourceAccessException("I/O error", new UnknownHostException("api.exmple.com"));
        assertThat(FriendlyErrors.forException(ex, "https://api.exmple.com"))
                .contains("Couldn't find the server api.exmple.com");
    }

    @Test
    void httpStatusesBecomeSentences() {
        assertThat(FriendlyErrors.forHttpStatus(401, "https://api.x.com")).contains("API key or credentials");
        assertThat(FriendlyErrors.forHttpStatus(429, "https://api.x.com")).contains("limiting how often");
        assertThat(FriendlyErrors.forHttpStatus(503, "https://api.x.com")).contains("problem on its side");
    }

    @Test
    void scriptTimeoutAndUndefinedAccessAreExplained() {
        assertThat(describe(NodeType.SCRIPT, "Script timed out after 10 seconds. The script may be waiting"))
                .isEqualTo("The script stopped because it ran longer than 10 seconds.");
        assertThat(describe(NodeType.SCRIPT, "TypeError: Cannot read properties of undefined (reading 'items')"))
                .contains("tried to read 'items'");
    }

    @Test
    void aiAuthErrorPointsToSettings() {
        assertThat(describe(NodeType.AI, "LLM call failed: Anthropic API error 401: invalid x-api-key"))
                .isEqualTo("The AI provider rejected the API key — check Settings → AI Providers.");
    }

    @Test
    void rawTextIsKeptAsDetail() {
        NodeContext result = failed(NodeType.SCRIPT, "ReferenceError: total is not defined");
        NodeContext described = FriendlyErrors.describe(node(NodeType.SCRIPT), result, null);
        assertThat(described.getErrorDetail()).isEqualTo("ReferenceError: total is not defined");
        assertThat(described.getErrorMessage()).contains("uses 'total', which doesn't exist");
    }

    private static String describe(NodeType type, String raw) {
        return FriendlyErrors.describe(node(type), failed(type, raw), null).getErrorMessage();
    }

    private static NodeContext failed(NodeType type, String raw) {
        return NodeContext.builder().nodeType(type.name()).status(NodeStatus.FAILURE).errorMessage(raw).build();
    }

    private static FlowNode node(NodeType type) {
        FlowNode n = new FlowNode();
        n.setId(UUID.randomUUID());
        n.setNodeType(type);
        n.setLabel("Step");
        return n;
    }
}
