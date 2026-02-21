package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.NexusConnector;
import com.nexflow.nexflow_backend.repository.NexusConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/nexus/connectors")
@RequiredArgsConstructor
public class NexusController {

    private final NexusConnectorRepository connectorRepository;

    @GetMapping
    public List<NexusConnector> list() {
        return connectorRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public NexusConnector create(@RequestBody NexusConnector connector) {
        connector.setId(null); // let DB generate
        return connectorRepository.save(connector);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NexusConnector> get(@PathVariable UUID id) {
        return connectorRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<NexusConnector> update(@PathVariable UUID id, @RequestBody NexusConnector updated) {
        return connectorRepository.findById(id)
                .map(existing -> {
                    existing.setName(updated.getName());
                    existing.setDescription(updated.getDescription());
                    existing.setBaseUrl(updated.getBaseUrl());
                    existing.setAuthType(updated.getAuthType());
                    existing.setDefaultHeaders(updated.getDefaultHeaders());
                    existing.setAuthConfig(updated.getAuthConfig());
                    return ResponseEntity.ok(connectorRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!connectorRepository.existsById(id)) return ResponseEntity.notFound().build();
        connectorRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
