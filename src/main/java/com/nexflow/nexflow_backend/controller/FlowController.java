package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.FlowStatus;
import com.nexflow.nexflow_backend.EdgeCondition;
import com.nexflow.nexflow_backend.model.domain.*;
import com.nexflow.nexflow_backend.model.dto.CanvasSaveDto;
import com.nexflow.nexflow_backend.model.dto.FlowEdgeDto;
import com.nexflow.nexflow_backend.model.dto.FlowNodeDto;
import com.nexflow.nexflow_backend.repository.*;
import com.nexflow.nexflow_backend.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowRepository flowRepository;
    private final FlowNodeRepository nodeRepository;
    private final FlowEdgeRepository edgeRepository;
    private final ExecutionRepository executionRepository;
    private final NexUserRepository nexUserRepository;
    private final FlowSaveLogRepository flowSaveLogRepository;
    private final GroupService groupService;

    @GetMapping
    public List<FlowResponse> getAllFlows(@AuthenticationPrincipal NexUser user) {
        if (user == null) return List.of();
        List<Flow> all = flowRepository.findAll();
        // Admins see every flow. Everyone else only sees flows they can access.
        if (user.getRole() == UserRole.ADMIN) {
            log.debug("[Flow] listAll userId={} (admin) total={}", user.getId(), all.size());
            return all.stream().map(this::toFlowResponse).toList();
        }
        List<FlowResponse> filtered = all.stream()
                .filter(f -> groupService.hasFlowAccess(f.getId(), f.getUserId(), user))
                .map(this::toFlowResponse)
                .toList();
        log.debug("[Flow] listAll userId={} accessible={}", user.getId(), filtered.size());
        return filtered;
    }

    @PostMapping
    public ResponseEntity<FlowResponse> createFlow(@RequestBody Flow flow, @AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        // Create the flow record first. The canvas nodes and edges are saved separately.
        flow.setUserId(user.getId());
        if (flow.getSlug() == null || flow.getSlug().isBlank()) {
            flow.setSlug(toSlug(flow.getName()));
        }
        String base = flow.getSlug();
        int counter = 1;
        while (flowRepository.existsBySlug(flow.getSlug())) {
            flow.setSlug(base + "-" + counter++);
        }
        Flow saved = flowRepository.save(flow);
        log.info("[Flow] created flowId={} userId={} slug={}", saved.getId(), user.getId(), saved.getSlug());
        recordActivity(saved.getId(), user.getId(), "FLOW_CREATED");
        return ResponseEntity.ok(toFlowResponse(saved));
    }

    /** "Auth Service" → "auth-service" */
    private static String toSlug(String name) {
        if (name == null || name.isBlank()) {
            return "flow-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
    }

    @GetMapping("/{flowId}")
    public ResponseEntity<FlowResponse> getFlow(@PathVariable UUID flowId,
                                                @AuthenticationPrincipal NexUser user) {
        return flowRepository.findById(flowId)
                .filter(f -> user == null || groupService.hasFlowAccess(f.getId(), f.getUserId(), user))
                .map(this::ensureSlug)
                .map(this::toFlowResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{flowId}")
    public ResponseEntity<FlowResponse> updateFlow(@PathVariable UUID flowId,
                                                   @RequestBody Map<String, String> body,
                                                   @AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        if (body == null) return ResponseEntity.badRequest().build();
        String name = body.get("name");
        String statusRaw = body.get("status");
        FlowStatus parsedStatus = null;
        if (statusRaw != null && !statusRaw.isBlank()) {
            try {
                parsedStatus = FlowStatus.valueOf(statusRaw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().build();
            }
        }
        final FlowStatus nextStatus = parsedStatus;
        if ((name == null || name.isBlank()) && nextStatus == null) return ResponseEntity.badRequest().build();
        return flowRepository.findById(flowId)
                .filter(f -> isOwnerOrAdmin(f, user))
                .map(flow -> {
                    if (name != null && !name.isBlank()) {
                        flow.setName(name.trim());
                    }
                    if (nextStatus != null) {
                        flow.setStatus(nextStatus);
                    }
                    Flow saved = flowRepository.save(flow);
                    log.info("[Flow] updated flowId={} userId={} nameChanged={} status={}",
                            flowId, user.getId(), name != null && !name.isBlank(), saved.getStatus());
                    return saved;
                })
                .map(this::toFlowResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(403).build());
    }

    private void recordActivity(UUID flowId, UUID userId, String activityType) {
        try {
            FlowSaveLog log = new FlowSaveLog();
            log.setFlowId(flowId);
            log.setUserId(userId);
            log.setActivityType(activityType);
            log.setSavedAt(Instant.now());
            flowSaveLogRepository.save(log);
        } catch (Exception ex) {
            FlowController.log.warn("[Flow] failed to record activity flowId={} type={}: {}", flowId, activityType, ex.getMessage());
        }
    }

    private FlowResponse toFlowResponse(Flow flow) {
        String createdByName = null;
        if (flow.getUserId() != null) {
            createdByName = nexUserRepository.findById(flow.getUserId()).map(NexUser::getName).orElse(null);
        }
        return new FlowResponse(
                flow.getId(),
                flow.getName(),
                flow.getSlug(),
                flow.getDescription(),
                flow.getUserId(),
                flow.getStatus(),
                flow.getCreatedAt(),
                flow.getUpdatedAt(),
                createdByName
        );
    }

    /** Backfill slug if missing so trigger-by-slug works for flows created before slug was required. */
    private Flow ensureSlug(Flow flow) {
        if (flow.getSlug() == null || flow.getSlug().isBlank()) {
            String slug = "flow-" + flow.getId().toString().replace("-", "").substring(0, 12);
            if (flowRepository.findBySlug(slug).isEmpty()) {
                flow.setSlug(slug);
            } else {
                flow.setSlug("flow-" + flow.getId().toString().replace("-", ""));
            }
            flowRepository.save(flow);
        }
        return flow;
    }

    // Save the entire canvas in one shot — Studio sends all nodes + edges together
    @Transactional
    @PostMapping("/{flowId}/canvas")
    public ResponseEntity<?> saveCanvas(
            @PathVariable UUID flowId,
            @RequestBody CanvasSaveDto dto,
            @AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        Flow flow = flowRepository.findById(flowId).orElse(null);
        if (flow == null) return ResponseEntity.notFound().build();
        if (!isOwnerOrAdmin(flow, user)) return ResponseEntity.status(403).build();

        // Save is replace-all: remove the old canvas rows, then insert the latest snapshot.
        String saveOutputAsError = validateSaveOutputAs(dto.nodes());
        if (saveOutputAsError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", saveOutputAsError));
        }

        nodeRepository.deleteAll(nodeRepository.findByFlowId(flowId));
        edgeRepository.deleteAll(edgeRepository.findByFlowId(flowId));

        for (FlowNodeDto n : dto.nodes()) {
            FlowNode node = toFlowNode(n, flowId);
            nodeRepository.save(node);
        }
        for (FlowEdgeDto e : dto.edges()) {
            FlowEdge edge = toFlowEdge(e, flowId);
            if (edge != null) {
                edgeRepository.save(edge);
            }
        }

        log.info("[Flow] canvas saved flowId={} userId={} nodes={} edges={}",
                flowId, user.getId(), dto.nodes() != null ? dto.nodes().size() : 0, dto.edges() != null ? dto.edges().size() : 0);
        recordActivity(flowId, user.getId(), "CANVAS_SAVED");
        return ResponseEntity.ok().build();
    }

    private static final Set<String> RESERVED_NEX_KEYS = Set.of("nodes", "trigger", "variables", "loop", "meta", "nex");

    /** Validates saveOutputAs across all nodes: key format, reserved words, uniqueness. Returns error message or null. */
    private static String validateSaveOutputAs(List<FlowNodeDto> nodes) {
        if (nodes == null) return null;
        Set<String> seen = new HashSet<>();
        for (FlowNodeDto n : nodes) {
            Object raw = n.config() != null ? n.config().get("saveOutputAs") : null;
            if (raw == null || raw.toString().isBlank()) continue;
            String value = raw.toString().trim();
            String label = n.label() != null ? n.label() : n.id();
            if (!value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                return "Node '" + label + "' has invalid saveOutputAs value '" + value + "'. Use only letters, numbers, underscores, starting with a letter.";
            }
            if (RESERVED_NEX_KEYS.contains(value)) {
                return "Node '" + label + "' has reserved saveOutputAs value '" + value + "'. Cannot use: nodes, trigger, variables, loop, meta, nex.";
            }
            if (seen.contains(value)) {
                return "Two nodes have the same saveOutputAs value '" + value + "'. Each node must have a unique name.";
            }
            seen.add(value);
        }
        return null;
    }

    @GetMapping("/{flowId}/canvas")
    public CanvasSaveRequest getCanvas(@PathVariable UUID flowId) {
        return new CanvasSaveRequest(
                nodeRepository.findByFlowId(flowId),
                edgeRepository.findByFlowId(flowId)
        );
    }

    private static FlowNode toFlowNode(FlowNodeDto dto, UUID flowId) {
        FlowNode n = new FlowNode();
        if (dto.id() != null && !dto.id().isBlank()) {
            n.setId(UUID.fromString(dto.id()));
        }
        n.setFlowId(flowId);
        n.setNodeType(dto.nodeType());
        n.setLabel(dto.label() != null ? dto.label() : "");
        n.setConfig(dto.config() != null ? dto.config() : new java.util.HashMap<>());
        n.setPositionX(dto.positionX() != null ? dto.positionX() : 0.0);
        n.setPositionY(dto.positionY() != null ? dto.positionY() : 0.0);
        return n;
    }

    private static FlowEdge toFlowEdge(FlowEdgeDto dto, UUID flowId) {
        if (dto.sourceNodeId() == null || dto.sourceNodeId().isBlank()
                || dto.targetNodeId() == null || dto.targetNodeId().isBlank()) {
            return null;
        }
        try {
            FlowEdge e = new FlowEdge();
            if (dto.id() != null && !dto.id().isBlank()) {
                e.setId(UUID.fromString(dto.id()));
            }
            e.setFlowId(flowId);
            e.setSourceNodeId(UUID.fromString(dto.sourceNodeId().trim()));
            e.setTargetNodeId(UUID.fromString(dto.targetNodeId().trim()));
            e.setSourceHandle(dto.sourceHandle() != null && !dto.sourceHandle().isBlank() ? dto.sourceHandle().trim() : null);
            e.setTargetHandle(dto.targetHandle() != null && !dto.targetHandle().isBlank() ? dto.targetHandle().trim() : null);
            e.setConditionType(parseEdgeCondition(dto.conditionType()));
            e.setConditionExpr(dto.conditionExpr());
            return e;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static EdgeCondition parseEdgeCondition(String value) {
        if (value == null || value.isBlank()) return EdgeCondition.DEFAULT;
        try {
            return EdgeCondition.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return EdgeCondition.DEFAULT;
        }
    }

    @GetMapping("/{flowId}/executions")
    public List<Execution> getExecutions(@PathVariable UUID flowId) {
        return executionRepository.findByFlowIdOrderByStartedAtDesc(flowId);
    }

    // ─── EXPORT ──────────────────────────────────────────────────────────────

    /** Export a flow bundle: flow metadata + canvas (nodes + edges). No transaction history. */
    @GetMapping("/{flowId}/export")
    public ResponseEntity<?> exportFlow(@PathVariable UUID flowId,
                                        @AuthenticationPrincipal NexUser user) {
        Flow flow = flowRepository.findById(flowId).orElse(null);
        if (flow == null) return ResponseEntity.notFound().build();
        if (user == null || !groupService.hasFlowAccess(flow.getId(), flow.getUserId(), user))
            return ResponseEntity.status(403).build();

        List<FlowNode> nodes = nodeRepository.findByFlowId(flowId);
        List<FlowEdge> edges = edgeRepository.findByFlowId(flowId);

        FlowBundle bundle = new FlowBundle(
            "1.0",
            Instant.now().toString(),
            new FlowBundle.FlowExport(flow.getName(), flow.getSlug(), flow.getDescription(), flow.getStatus().name()),
            nodes.stream().map(n -> new FlowNodeDto(
                n.getId().toString(), n.getFlowId().toString(),
                n.getNodeType(), n.getLabel(), n.getConfig(),
                n.getPositionX(), n.getPositionY()
            )).toList(),
            edges.stream().map(e -> new FlowEdgeDto(
                e.getId().toString(), e.getFlowId().toString(),
                e.getSourceNodeId().toString(), e.getTargetNodeId().toString(),
                e.getSourceHandle(), e.getTargetHandle(),
                e.getConditionType() != null ? e.getConditionType().name() : null,
                e.getConditionExpr()
            )).toList()
        );

        log.info("[Flow] export flowId={} userId={} nodes={} edges={}",
                flowId, user.getId(), nodes.size(), edges.size());
        return ResponseEntity.ok().body(bundle);
    }

    // ─── IMPORT ──────────────────────────────────────────────────────────────

    /** Import a flow bundle: creates a new flow (always as DRAFT) with fresh UUIDs. */
    @Transactional
    @PostMapping("/import")
    public ResponseEntity<?> importFlow(@RequestBody FlowBundle bundle,
                                        @AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        if (bundle == null || bundle.flow() == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid bundle"));

        // Create the new flow, always as DRAFT regardless of source status
        Flow flow = new Flow();
        flow.setName(bundle.flow().name());
        flow.setDescription(bundle.flow().description());
        flow.setStatus(com.nexflow.nexflow_backend.FlowStatus.DRAFT);
        flow.setUserId(user.getId());

        // Handle slug conflicts the same way as createFlow
        String base = (bundle.flow().slug() != null && !bundle.flow().slug().isBlank())
                ? bundle.flow().slug() : toSlug(bundle.flow().name());
        flow.setSlug(base);
        int counter = 1;
        while (flowRepository.existsBySlug(flow.getSlug())) {
            flow.setSlug(base + "-" + counter++);
        }

        Flow saved = flowRepository.save(flow);

        // Build old-id → new-UUID map so edges can be remapped
        Map<String, UUID> nodeIdMap = new java.util.HashMap<>();
        if (bundle.nodes() != null) {
            for (FlowNodeDto n : bundle.nodes()) {
                UUID newId = UUID.randomUUID();
                if (n.id() != null) nodeIdMap.put(n.id(), newId);

                FlowNode node = new FlowNode();
                node.setId(newId);
                node.setFlowId(saved.getId());
                node.setNodeType(n.nodeType());
                node.setLabel(n.label() != null ? n.label() : "");
                node.setConfig(n.config() != null ? n.config() : new java.util.HashMap<>());
                node.setPositionX(n.positionX() != null ? n.positionX() : 0.0);
                node.setPositionY(n.positionY() != null ? n.positionY() : 0.0);
                nodeRepository.save(node);
            }
        }

        if (bundle.edges() != null) {
            for (FlowEdgeDto e : bundle.edges()) {
                UUID newSrc = e.sourceNodeId() != null ? nodeIdMap.get(e.sourceNodeId()) : null;
                UUID newTgt = e.targetNodeId() != null ? nodeIdMap.get(e.targetNodeId()) : null;
                if (newSrc == null || newTgt == null) continue;

                FlowEdge edge = new FlowEdge();
                edge.setId(UUID.randomUUID());
                edge.setFlowId(saved.getId());
                edge.setSourceNodeId(newSrc);
                edge.setTargetNodeId(newTgt);
                edge.setSourceHandle(e.sourceHandle());
                edge.setTargetHandle(e.targetHandle());
                edge.setConditionType(parseEdgeCondition(e.conditionType()));
                edge.setConditionExpr(e.conditionExpr());
                edgeRepository.save(edge);
            }
        }

        recordActivity(saved.getId(), user.getId(), "FLOW_IMPORTED");
        log.info("[Flow] imported flowId={} userId={} name='{}' nodes={} edges={}",
                saved.getId(), user.getId(), saved.getName(),
                bundle.nodes() != null ? bundle.nodes().size() : 0,
                bundle.edges() != null ? bundle.edges().size() : 0);
        return ResponseEntity.ok(toFlowResponse(saved));
    }

    /**
     * Delete a flow (\"studio\") and all of its persisted state:
     * - all executions/transactions for this flow
     * - all nodes & edges in its canvas
     *
     * Pulse endpoints and external APIs are not separate entities here:
     * deleting the Flow simply removes the ability to trigger it; Nexus
     * connectors and other APIs remain untouched.
     */
    @Transactional
    @DeleteMapping("/{flowId}")
    public ResponseEntity<Void> deleteFlow(@PathVariable UUID flowId,
                                            @AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        Flow flow = flowRepository.findById(flowId).orElse(null);
        if (flow == null) return ResponseEntity.notFound().build();
        if (!isOwnerOrAdmin(flow, user)) return ResponseEntity.status(403).build();

        // Removing the flow also removes its executions, nodes, and edges.
        executionRepository.deleteAll(executionRepository.findByFlowIdOrderByStartedAtDesc(flowId));
        nodeRepository.deleteAll(nodeRepository.findByFlowId(flowId));
        edgeRepository.deleteAll(edgeRepository.findByFlowId(flowId));
        flowRepository.deleteById(flowId);
        log.info("[Flow] deleted flowId={} userId={}", flowId, user.getId());
        return ResponseEntity.noContent().build();
    }

    /** Returns true if the user is the flow owner or has ADMIN role. */
    private static boolean isOwnerOrAdmin(Flow flow, NexUser user) {
        if (user.getRole() == UserRole.ADMIN) return true;
        // Flows with no owner (pre-auth legacy) are editable by any authenticated user
        if (flow.getUserId() == null) return true;
        return flow.getUserId().equals(user.getId());
    }

    /** Response type for GET canvas; also used internally for the saved entities. */
    public record CanvasSaveRequest(List<FlowNode> nodes, List<FlowEdge> edges) {}

    /** Portable flow bundle used for import/export (no transaction history). */
    public record FlowBundle(
            String version,
            String exportedAt,
            FlowExport flow,
            List<FlowNodeDto> nodes,
            List<FlowEdgeDto> edges
    ) {
        public record FlowExport(
                String name,
                String slug,
                String description,
                String status
        ) {}
    }

    /** Public flow payload with derived creator name for UI cards. */
    public record FlowResponse(
            UUID id,
            String name,
            String slug,
            String description,
            UUID userId,
            com.nexflow.nexflow_backend.FlowStatus status,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            String createdByName
    ) {}
}
