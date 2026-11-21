package com.nexflow.nexflow_backend.executor.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmClientTest {

    private final OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient();

    @Test
    void takesTheJsonAReasoningModelEndedWith() {
        assertThat(client.trailingJson("We need to get flow info. Use get_flow.{\"tool\": \"get_flow\", \"args\": {\"flow\": \"Demo\"}}"))
                .isEqualTo("{\"tool\": \"get_flow\", \"args\": {\"flow\": \"Demo\"}}");
        assertThat(client.trailingJson("Earlier I saw {\"a\": 1} so now {\"tool\": \"list_flows\", \"args\": {}}"))
                .isEqualTo("{\"tool\": \"list_flows\", \"args\": {}}");
    }

    @Test
    void ignoresReasoningThatDoesNotEndInJson() {
        assertThat(client.trailingJson("I think the answer is 42.")).isNull();
        assertThat(client.trailingJson("broken {\"tool\": ")).isNull();
    }
}
