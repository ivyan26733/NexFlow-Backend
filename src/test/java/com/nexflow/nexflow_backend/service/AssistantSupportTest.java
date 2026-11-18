package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSupportTest {

    private final AssistantService service = new AssistantService(null, null, null, new ObjectMapper());

    @Test
    void toolCallIsRecognisedPlainOrFenced() {
        var call = service.parseToolCall("{\"tool\": \"get_execution\", \"args\": {\"executionId\": \"abc\"}}");
        assertThat(call.tool()).isEqualTo("get_execution");
        assertThat(call.args()).containsEntry("executionId", "abc");

        assertThat(service.parseToolCall("```json\n{\"tool\":\"list_flows\"}\n```").tool()).isEqualTo("list_flows");
    }

    @Test
    void answersThatMerelyContainJsonAreNotToolCalls() {
        assertThat(service.parseToolCall("Use this config:\n```json\n{\"tool\":\"x\"}\n```")).isNull();
        assertThat(service.parseToolCall("{\"variables\": {\"a\": 1}}")).isNull();
        assertThat(service.parseToolCall("All good — your flow ran 3 times.")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void secretsAreRedactedButReferencesKept() {
        Map<String, Object> config = Map.of(
                "headers", Map.of("Authorization", "Bearer abcdefghijkl", "X-Trace", "1"),
                "apiKey", "{{nex.key}}",
                "dbPassword", "hunter2",
                "url", "https://x.test",
                "notes", List.of("token xoxb-1234567890-abc"));
        Map<String, Object> out = (Map<String, Object>) AssistantTools.redact(config);
        assertThat((Map<String, Object>) out.get("headers")).containsEntry("Authorization", "••••").containsEntry("X-Trace", "1");
        assertThat(out).containsEntry("dbPassword", "••••").containsEntry("apiKey", "{{nex.key}}").containsEntry("url", "https://x.test");
        assertThat(out.get("notes").toString()).doesNotContain("1234567890");
    }

    @Test
    void fileNamesAreSanitized() {
        assertThat(FileStorageService.sanitizeName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileStorageService.sanitizeName("C:\\Users\\me\\report final.pdf")).isEqualTo("report final.pdf");
        assertThat(FileStorageService.sanitizeName(".env")).isEqualTo("env");
        assertThat(FileStorageService.sanitizeName("")).isEqualTo("file");
    }
}
