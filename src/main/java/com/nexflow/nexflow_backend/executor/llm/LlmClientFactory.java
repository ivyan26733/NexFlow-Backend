package com.nexflow.nexflow_backend.executor.llm;

import com.nexflow.nexflow_backend.model.domain.LlmProvider;
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
            "llama-3.3-70b-versatile",
            new String[]{"llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768", "gemma2-9b-it"}
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
