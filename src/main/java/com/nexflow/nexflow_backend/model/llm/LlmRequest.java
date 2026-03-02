package com.nexflow.nexflow_backend.model.llm;

import java.util.Map;

/**
 * Provider-agnostic request that AiNodeExecutor builds.
 * Each LlmClient implementation translates this into its provider's API format.
 */
public class LlmRequest {

    private String systemPrompt;
    private String userPrompt;
    private String model;
    private int maxTokens = 1000;
    private double temperature = 0.0;
    private String outputSchema;
    private Map<String, Object> inputs;

    public LlmRequest() {}

    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public String getModel() { return model; }
    public int getMaxTokens() { return maxTokens; }
    public double getTemperature() { return temperature; }
    public String getOutputSchema() { return outputSchema; }
    public Map<String, Object> getInputs() { return inputs; }

    public void setSystemPrompt(String s) { this.systemPrompt = s; }
    public void setUserPrompt(String s) { this.userPrompt = s; }
    public void setModel(String m) { this.model = m; }
    public void setMaxTokens(int n) { this.maxTokens = n; }
    public void setTemperature(double t) { this.temperature = t; }
    public void setOutputSchema(String s) { this.outputSchema = s; }
    public void setInputs(Map<String, Object> i) { this.inputs = i; }
}
