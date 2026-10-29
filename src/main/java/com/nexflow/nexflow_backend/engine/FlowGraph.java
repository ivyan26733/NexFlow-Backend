package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.EdgeCondition;
import com.nexflow.nexflow_backend.model.domain.FlowEdge;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view of a flow's nodes and edges with the routing rules the engine needs.
 *
 * Routing rules (which edges are followed after a node runs):
 *   SUCCESS  → SUCCESS and DEFAULT edges
 *   FAILURE  → FAILURE edges only (a failed step never continues down its success path)
 *   CONTINUE → CONTINUE edges only (LOOP repeating its body)
 */
public final class FlowGraph {

    private static final UUID DEFAULT_START_NODE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final Map<UUID, FlowNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, List<FlowEdge>> outgoing = new HashMap<>();
    private final Map<UUID, List<FlowEdge>> incoming = new HashMap<>();
    private final FlowNode start;
    private final Map<UUID, Set<UUID>> loopBodies = new HashMap<>();

    public FlowGraph(UUID flowId, Collection<FlowNode> flowNodes, Collection<FlowEdge> flowEdges) {
        flowNodes.forEach(n -> nodes.put(n.getId(), n));
        for (FlowEdge e : flowEdges) {
            if (!nodes.containsKey(e.getSourceNodeId()) || !nodes.containsKey(e.getTargetNodeId())) continue;
            outgoing.computeIfAbsent(e.getSourceNodeId(), k -> new ArrayList<>()).add(e);
            incoming.computeIfAbsent(e.getTargetNodeId(), k -> new ArrayList<>()).add(e);
        }
        this.start = nodes.values().stream()
                .filter(n -> n.getNodeType() == NodeType.START)
                .findFirst()
                .orElseGet(() -> addDefaultStart(flowId));
    }

    public FlowNode start() {
        return start;
    }

    public FlowNode node(UUID id) {
        return nodes.get(id);
    }

    public Collection<FlowNode> nodes() {
        return nodes.values();
    }

    /** Nodes to run next after {@code node} finished with {@code result}. */
    public List<FlowNode> next(FlowNode node, NodeContext result) {
        NodeStatus status = result.getStatus();
        return edgesFrom(node.getId()).stream()
                .filter(e -> follows(e.getConditionType(), status))
                .map(e -> nodes.get(e.getTargetNodeId()))
                .distinct()
                .toList();
    }

    private static boolean follows(EdgeCondition condition, NodeStatus status) {
        return switch (status) {
            case SUCCESS -> condition == EdgeCondition.SUCCESS || condition == EdgeCondition.DEFAULT;
            case FAILURE -> condition == EdgeCondition.FAILURE;
            case CONTINUE -> condition == EdgeCondition.CONTINUE;
            default -> false;
        };
    }

    /**
     * Entry nodes of each FORK branch, keyed by branch name (in configured order).
     * Edges leaving the FORK carry the branch name as their source handle. Edges saved without a
     * handle are assigned, in order, to branches that have no entry yet.
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<FlowNode>> branchEntries(FlowNode fork) {
        List<String> names = fork.configValue("branches", List.of()) instanceof List<?> l
                ? l.stream().map(String::valueOf).toList()
                : List.of();
        Map<String, List<FlowNode>> entries = new LinkedHashMap<>();
        names.forEach(n -> entries.put(n, new ArrayList<>()));

        List<FlowNode> unnamed = new ArrayList<>();
        for (FlowEdge e : edgesFrom(fork.getId())) {
            FlowNode target = nodes.get(e.getTargetNodeId());
            String handle = e.getSourceHandle();
            if (handle != null && entries.containsKey(handle.trim())) {
                entries.get(handle.trim()).add(target);
            } else {
                unnamed.add(target);
            }
        }
        for (List<FlowNode> list : entries.values()) {
            if (unnamed.isEmpty()) break;
            if (list.isEmpty()) list.add(unnamed.remove(0));
        }
        return entries;
    }

    /** Nodes inside a LOOP's body: reachable from its CONTINUE edges and leading back to the loop. */
    public Set<UUID> loopBody(UUID loopId) {
        return loopBodies.computeIfAbsent(loopId, this::computeLoopBody);
    }

    private Set<UUID> computeLoopBody(UUID loopId) {
        Set<UUID> forward = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        edgesFrom(loopId).stream()
                .filter(e -> e.getConditionType() == EdgeCondition.CONTINUE)
                .forEach(e -> queue.add(e.getTargetNodeId()));
        while (!queue.isEmpty()) {
            UUID id = queue.poll();
            if (id.equals(loopId) || !forward.add(id)) continue;
            edgesFrom(id).forEach(e -> queue.add(e.getTargetNodeId()));
        }
        Set<UUID> backward = new HashSet<>();
        incoming.getOrDefault(loopId, List.of()).forEach(e -> queue.add(e.getSourceNodeId()));
        while (!queue.isEmpty()) {
            UUID id = queue.poll();
            if (id.equals(loopId) || !backward.add(id)) continue;
            incoming.getOrDefault(id, List.of()).forEach(e -> queue.add(e.getSourceNodeId()));
        }
        forward.retainAll(backward);
        return forward;
    }

    /** All "Save output as" keys used anywhere in the flow. */
    public Set<String> saveOutputAsKeys() {
        Set<String> keys = new HashSet<>();
        nodes.values().stream().map(FlowNode::saveOutputAsKey).filter(Objects::nonNull).forEach(keys::add);
        return keys;
    }

    /**
     * Problems that make the flow impossible to run, in plain language. Empty when the flow is valid.
     * Checks: every LOOP has a Repeat (CONTINUE) connection, and the only circles in the flow are loops.
     */
    public Optional<String> validate() {
        for (FlowNode n : nodes.values()) {
            if (n.getNodeType() == NodeType.LOOP && edgesFrom(n.getId()).stream()
                    .noneMatch(e -> e.getConditionType() == EdgeCondition.CONTINUE)) {
                return Optional.of("The loop '" + n.displayName() + "' has no Repeat connection. "
                        + "Connect its Repeat handle back to the first step that should run again.");
            }
        }
        return findCycleOutsideLoops().map(cycle -> "Steps " + cycle
                + " are connected in a circle. Only a Loop's Repeat connection may point back to an earlier step.");
    }

    /** Depth-first search over all edges except CONTINUE; returns a readable description of a cycle. */
    private Optional<String> findCycleOutsideLoops() {
        Map<UUID, Integer> state = new HashMap<>(); // 1 = on stack, 2 = finished
        for (UUID id : nodes.keySet()) {
            Optional<String> cycle = dfs(id, state, new ArrayDeque<>());
            if (cycle.isPresent()) return cycle;
        }
        return Optional.empty();
    }

    private Optional<String> dfs(UUID id, Map<UUID, Integer> state, Deque<UUID> path) {
        Integer s = state.get(id);
        if (s != null && s == 2) return Optional.empty();
        if (s != null) {
            List<String> labels = new ArrayList<>();
            boolean inCycle = false;
            for (UUID p : (Iterable<UUID>) path::descendingIterator) {
                if (p.equals(id)) inCycle = true;
                if (inCycle) labels.add("'" + nodes.get(p).displayName() + "'");
            }
            return Optional.of(String.join(" → ", labels));
        }
        state.put(id, 1);
        path.push(id);
        for (FlowEdge e : edgesFrom(id)) {
            if (e.getConditionType() == EdgeCondition.CONTINUE) continue;
            Optional<String> cycle = dfs(e.getTargetNodeId(), state, path);
            if (cycle.isPresent()) return cycle;
        }
        path.pop();
        state.put(id, 2);
        return Optional.empty();
    }

    private List<FlowEdge> edgesFrom(UUID id) {
        return outgoing.getOrDefault(id, List.of());
    }

    private FlowNode addDefaultStart(UUID flowId) {
        FlowNode node = new FlowNode();
        node.setId(DEFAULT_START_NODE_ID);
        node.setFlowId(flowId);
        node.setNodeType(NodeType.START);
        node.setLabel("Start");
        node.setConfig(Map.of());
        nodes.put(node.getId(), node);
        return node;
    }
}
