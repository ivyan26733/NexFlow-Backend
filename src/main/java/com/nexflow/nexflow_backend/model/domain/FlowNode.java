package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "flow_nodes")
@Data
public class FlowNode {

    @Id
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false)
    private NodeType nodeType;

    private String label;

    // Stores all node-specific configuration and input mappings as JSON
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> config;

    // Canvas position
    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    private static final Pattern NEX_KEY = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    /** Config value, or {@code fallback} when absent. */
    public Object configValue(String key, Object fallback) {
        return config != null && config.get(key) != null ? config.get(key) : fallback;
    }

    /** The node's "Save output as" key if it is a valid nex key, otherwise null. */
    public String saveOutputAsKey() {
        Object raw = config != null ? config.get("saveOutputAs") : null;
        String key = raw != null ? raw.toString().trim() : "";
        return NEX_KEY.matcher(key).matches() ? key : null;
    }

    /** camelCase key derived from the label, used for {{nex.labelKey}} and {{nodes.labelKey}}. */
    public String labelKey() {
        return toLabelKey(label);
    }

    /**
     * Multi-word labels become camelCase ("Calculate Discount" → "calculateDiscount").
     * Single words only lower-case their first letter so "generateData" stays "generateData".
     */
    public static String toLabelKey(String label) {
        if (label == null || label.isBlank()) return "node";
        String[] words = label.trim().replaceAll("[^a-zA-Z0-9 ]", "").split("\\s+");
        if (words.length == 0 || words[0].isBlank()) return "node";
        if (words.length == 1) {
            return Character.toLowerCase(words[0].charAt(0)) + words[0].substring(1);
        }
        StringBuilder key = new StringBuilder(words[0].toLowerCase());
        for (int i = 1; i < words.length; i++) {
            if (words[i].isBlank()) continue;
            key.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1).toLowerCase());
        }
        return key.toString();
    }

    public String displayName() {
        return label != null && !label.isBlank() ? label : String.valueOf(nodeType);
    }
}
