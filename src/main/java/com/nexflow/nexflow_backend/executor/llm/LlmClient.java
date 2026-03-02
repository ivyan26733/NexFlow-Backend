package com.nexflow.nexflow_backend.executor.llm;

import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.llm.LlmRequest;
import com.nexflow.nexflow_backend.model.llm.LlmResponse;

public interface LlmClient {

    LlmProvider getProvider();

    LlmResponse call(LlmRequest request, String apiKey, String endpoint);

    String getDefaultModel();

    String[] getKnownModels();
}
