package com.nexflow.nexflow_backend.model.llm;

/**
 * Provider-agnostic response returned by every LlmClient.
 * The raw text is always the model's reply.
 * parsedJson is populated by AiNodeExecutor after JSON extraction.
 */
public class LlmResponse {

    private boolean success;
    private String  rawText;       // exact text the model returned
    private String  errorMessage;  // populated if success = false
    private int     inputTokens;   // for cost logging
    private int     outputTokens;
    private String  model;         // actual model used (provider may differ from requested)

    // Set by AiNodeExecutor after parsing rawText
    private Object parsedJson;

    public LlmResponse() {}

    public static LlmResponse ok(String rawText, String model, int in, int out) {
        LlmResponse r = new LlmResponse();
        r.success      = true;
        r.rawText      = rawText;
        r.model        = model;
        r.inputTokens  = in;
        r.outputTokens = out;
        return r;
    }

    public static LlmResponse error(String message) {
        LlmResponse r = new LlmResponse();
        r.success      = false;
        r.errorMessage = message;
        return r;
    }

    // ── Getters + Setters ──────────────────────────────────────────────────

    public boolean isSuccess()           { return success; }
    public String getRawText()           { return rawText; }
    public String getErrorMessage()      { return errorMessage; }
    public int getInputTokens()          { return inputTokens; }
    public int getOutputTokens()         { return outputTokens; }
    public String getModel()             { return model; }
    public Object getParsedJson()        { return parsedJson; }

    public void setSuccess(boolean b)    { this.success = b; }
    public void setRawText(String s)     { this.rawText = s; }
    public void setErrorMessage(String s){ this.errorMessage = s; }
    public void setInputTokens(int n)    { this.inputTokens = n; }
    public void setOutputTokens(int n)   { this.outputTokens = n; }
    public void setModel(String m)       { this.model = m; }
    public void setParsedJson(Object o)  { this.parsedJson = o; }
}
