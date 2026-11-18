package com.nexflow.nexflow_backend.executor.llm;

import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.repository.LlmClient;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class LlmClientFactory {

    private final Map<LlmProvider, LlmClient> clientMap = new EnumMap<>(LlmProvider.class);

    public LlmClientFactory(List<LlmClient> clients) {
        for (LlmClient client : clients) {
            clientMap.put(client.getProvider(), client);
        }
        clientMap.put(LlmProvider.GROQ, new OpenAiCompatibleLlmClient(
            LlmProvider.GROQ,
            "https://api.groq.com/openai/v1/chat/completions",
            "openai/gpt-oss-120b",
            new String[]{"openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b", "allam-2-7b"}
        ));
        clientMap.put(LlmProvider.MISTRAL, new OpenAiCompatibleLlmClient(
            LlmProvider.MISTRAL,
            "https://api.mistral.ai/v1/chat/completions",
            "mistral-small-latest",
            new String[]{"mistral-small-latest", "mistral-medium-latest", "mistral-large-latest", "codestral-latest"}
        ));
        clientMap.put(LlmProvider.CUSTOM, new OpenAiCompatibleLlmClient(
            LlmProvider.CUSTOM,
            "",
            "",
            new String[]{}
        ));
    }

    public LlmClient getClient(LlmProvider provider) {
        LlmClient client = clientMap.get(provider);
        if (client == null) {
            throw new IllegalArgumentException("No LlmClient registered for provider: " + provider);
        }
        return client;
    }

    public Map<LlmProvider, LlmClient> getAllClients() {
        return clientMap;
    }
}
