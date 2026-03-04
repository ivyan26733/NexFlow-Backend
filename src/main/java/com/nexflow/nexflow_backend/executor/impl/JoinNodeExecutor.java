package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.executor.NodeExecutor;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Executes a JOIN node.
 *
 * JOIN is a visual convergence point; the actual fork/join orchestration lives
 * in ForkNodeExecutor. By the time we reach this node, nex already contains
 * merged branch outputs and nex.join metadata.
 */
@Component
@Slf4j
public class JoinNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportedType() {
        return NodeType.JOIN;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        if (!nco.getNex().containsKey("join")) {
            log.error("[JoinNode] '{}' reached without nex.join metadata — check that this JOIN is connected to a FORK node.", node.getLabel());
            return NodeContext.builder()
                    .nodeId(node.getId().toString())
                    .nodeType(NodeType.JOIN.name())
                    .status(NodeStatus.FAILURE)
                    .errorMessage("JOIN reached without nex.join metadata — ensure it is paired with a FORK node.")
                    .build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> joinMeta = (Map<String, Object>) nco.getNex().get("join");
        log.info("[JoinNode] '{}' — parallel window: {}ms, strategy: {}, branches: {}",
                node.getLabel(),
                joinMeta.get("totalParallelMs"),
                joinMeta.get("strategy"),
                joinMeta.get("branchCount"));

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.JOIN.name())
                .status(NodeStatus.SUCCESS)
                .build();
    }
}

