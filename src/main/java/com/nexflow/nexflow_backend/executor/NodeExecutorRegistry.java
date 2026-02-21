package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.domain.NodeType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class NodeExecutorRegistry {

    private final List<NodeExecutor> executors;
    private final Map<NodeType, NodeExecutor> registry = new EnumMap<>(NodeType.class);

    @PostConstruct
    public void init() {
        executors.forEach(executor -> registry.put(executor.supportedType(), executor));
    }

    public NodeExecutor get(NodeType type) {
        NodeExecutor executor = registry.get(type);
        if (executor == null) {
            throw new UnsupportedOperationException("No executor registered for node type: " + type);
        }
        return executor;
    }

    public boolean isSupported(NodeType type) {
        return registry.containsKey(type);
    }
}
