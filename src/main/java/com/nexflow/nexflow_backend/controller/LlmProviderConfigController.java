package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.executor.llm.LlmClientFactory;
import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.domain.LlmProviderConfig;
import com.nexflow.nexflow_backend.repository.LlmProviderConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/llm-providers")
public class LlmProviderConfigController {

    private final LlmProviderConfigRepository repo;
    private final LlmClientFactory clientFactory;

    public LlmProviderConfigController(LlmProviderConfigRepository repo, LlmClientFactory clientFactory) {
        this.repo = repo;
        this.clientFactory = clientFactory;
    }

    @GetMapping
    public List<Map<String, Object>> listProviders() {
        Map<LlmProvider, LlmProviderConfig> saved = repo.findAll()
                .stream()
                .collect(Collectors.toMap(LlmProviderConfig::getProvider, c -> c));

        return Arrays.stream(LlmProvider.values())
                .map(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("provider", p.name());
                    entry.put("displayName", p.getDisplayName());
                    try {
                        String[] models = clientFactory.getClient(p).getKnownModels();
                        String defModel = clientFactory.getClient(p).getDefaultModel();
                        entry.put("knownModels", models);
                        entry.put("defaultModel", defModel);
                    } catch (Exception e) {
                        entry.put("knownModels", new String[]{});
                        entry.put("defaultModel", "");
                    }
                    LlmProviderConfig cfg = saved.get(p);
                    if (cfg != null) {
                        entry.put("configured", true);
                        entry.put("enabled", cfg.isEnabled());
                        entry.put("apiKeyMasked", maskKey(cfg.getApiKey()));
                        entry.put("customEndpoint", cfg.getCustomEndpoint());
                        entry.put("configId", cfg.getId());
                    } else {
                        entry.put("configured", false);
                        entry.put("enabled", false);
                        entry.put("apiKeyMasked", null);
                        entry.put("customEndpoint", null);
                        entry.put("configId", null);
                    }
                    return entry;
                })
                .collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveProvider(@RequestBody Map<String, Object> body) {
        String providerStr = (String) body.get("provider");
        String apiKey = (String) body.get("apiKey");
        String endpoint = (String) body.get("customEndpoint");

        if (providerStr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider is required");
        }

        LlmProvider provider;
        try {
            provider = LlmProvider.valueOf(providerStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + providerStr);
        }

        // MLvoca is free and does not require an API key; allow empty and store a placeholder
        if (apiKey == null || apiKey.isBlank()) {
            if (provider != LlmProvider.MLVOCA) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey is required for this provider");
            }
            apiKey = "free";
        }

        LlmProviderConfig cfg = repo.findByProvider(provider).orElseGet(LlmProviderConfig::new);
        cfg.setProvider(provider);
        cfg.setApiKey(apiKey);
        cfg.setCustomEndpoint(endpoint);
        cfg.setEnabled(true);

        LlmProviderConfig saved = repo.save(cfg);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("provider", saved.getProvider().name());
        response.put("configured", true);
        response.put("enabled", saved.isEnabled());
        response.put("apiKeyMasked", maskKey(saved.getApiKey()));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{provider}/toggle")
    public ResponseEntity<Map<String, Object>> toggleProvider(@PathVariable String provider) {
        LlmProvider p = parseProvider(provider);
        LlmProviderConfig cfg = repo.findByProvider(p)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not configured: " + provider));
        cfg.setEnabled(!cfg.isEnabled());
        repo.save(cfg);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", p.name());
        response.put("enabled", cfg.isEnabled());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> deleteProvider(@PathVariable String provider) {
        LlmProvider p = parseProvider(provider);
        repo.findByProvider(p).ifPresent(repo::delete);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{provider}/test")
    public ResponseEntity<Map<String, Object>> testProvider(@PathVariable String provider) {
        LlmProvider p = parseProvider(provider);
        LlmProviderConfig cfg = repo.findByProvider(p)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not configured: " + provider));

        try {
            var client = clientFactory.getClient(p);
            var req = new com.nexflow.nexflow_backend.model.llm.LlmRequest();
            req.setModel(client.getDefaultModel());
            req.setMaxTokens(50);
            req.setTemperature(0.0);
            req.setUserPrompt("Respond with exactly this JSON: {\"ok\": true}");
            req.setSystemPrompt("You are a test. Return only valid JSON.");

            long start = System.currentTimeMillis();
            var resp = client.call(req, cfg.getApiKey(), cfg.getCustomEndpoint());
            long latency = System.currentTimeMillis() - start;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", resp.isSuccess());
            result.put("latencyMs", latency);
            result.put("model", client.getDefaultModel());
            result.put("message", resp.isSuccess() ? "Connected successfully" : resp.getErrorMessage());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = Map.of("success", false, "message", e.getMessage() != null ? e.getMessage() : "Unknown error");
            return ResponseEntity.ok(result);
        }
    }

    private LlmProvider parseProvider(String s) {
        try {
            return LlmProvider.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + s);
        }
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 10) return "****";
        return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
    }
}
