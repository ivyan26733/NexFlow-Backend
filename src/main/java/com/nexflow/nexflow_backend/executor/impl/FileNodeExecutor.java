package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.executor.ReferenceResolver;
import com.nexflow.nexflow_backend.executor.file.FileFormats;
import com.nexflow.nexflow_backend.executor.file.FileReferences;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.domain.StoredFile;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import com.nexflow.nexflow_backend.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FILE node — reads an incoming file or writes a new one.
 *
 * READ config:
 *   { "operation": "READ", "source": "{{nex.file}}", "parseAs": "AUTO|TEXT|CSV|JSON|EXCEL|PDF|WORD",
 *     "delimiter": ",", "sheet": "Sheet1" }
 *   source may be a file object ({fileId…}), a fileId, or an http(s) URL to download.
 *   Output: file details + text / rows / columns / data depending on the format.
 *
 * WRITE config:
 *   { "operation": "WRITE", "format": "CSV|EXCEL|JSON|TEXT|MARKDOWN|HTML|PDF|WORD",
 *     "fileName": "report-{{meta.executionId}}", "data": "{{nex.extract.rows}}",
 *     "content": "# Report\n{{nex.summary}}", "columns": [{"header": "Name", "path": "customer.name"}] }
 *   Output: { fileId, name, mimeType, size, downloadUrl, rowCount }.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileNodeExecutor implements NodeExecutor {

    private final FileStorageService storage;
    private final ReferenceResolver resolver;
    private final FileReferences files;

    @Override
    public NodeType supportedType() {
        return NodeType.FILE;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> cfg = node.getConfig() != null ? node.getConfig() : Map.of();
        String nodeId = node.getId().toString();
        String operation = String.valueOf(cfg.getOrDefault("operation", "READ")).toUpperCase();
        if (nco.getMeta().getUserId() == null) {
            return failure(nodeId, Map.of(), "This flow has no owner, so it can't read or save files.", null);
        }
        try {
            return "WRITE".equals(operation) ? write(nodeId, cfg, nco) : read(nodeId, cfg, nco);
        } catch (IllegalArgumentException e) {
            return failure(nodeId, Map.of("operation", operation), e.getMessage(), e.toString());
        } catch (Exception e) {
            log.warn("[FILE] node '{}' failed: {}", node.displayName(), e.getMessage(), e);
            String what = "WRITE".equals(operation) ? "create the file" : "read the file";
            return failure(nodeId, Map.of("operation", operation),
                    "Could not " + what + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e.toString());
        }
    }

    // ── READ ─────────────────────────────────────────────────────────────────

    private NodeContext read(String nodeId, Map<String, Object> cfg, NexflowContextObject nco) throws Exception {
        Object rawSource = cfg.getOrDefault("source", "{{nex.file}}");
        StoredFile file = files.resolve(rawSource, nco, true);
        byte[] bytes = storage.read(file);

        Map<String, Object> out = new LinkedHashMap<>(storage.describe(file));
        out.putAll(FileFormats.read(bytes, file.getName(), file.getMimeType(),
                str(cfg.get("parseAs")), str(cfg.get("delimiter")), resolver.resolve(str(cfg.get("sheet")), nco)));

        return NodeContext.builder()
                .nodeId(nodeId).nodeType(NodeType.FILE.name())
                .status(NodeStatus.SUCCESS)
                .input(Map.of("operation", "READ", "fileId", file.getId().toString(), "name", file.getName()))
                .successOutput(out)
                .build();
    }

    // ── WRITE ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private NodeContext write(String nodeId, Map<String, Object> cfg, NexflowContextObject nco) throws Exception {
        String format = str(cfg.getOrDefault("format", "CSV")).toUpperCase();
        Object data = resolveData(cfg.get("data"), nco);
        String content = resolver.resolveForText(str(cfg.get("content")), nco);
        if (data == null && (content == null || content.isBlank())) {
            throw new IllegalArgumentException("Tell the step what to put in the file: set Data (e.g. {{nex.extract.rows}}) or Content.");
        }

        List<FileFormats.Column> columns = new ArrayList<>();
        if (cfg.get("columns") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String header = str(((Map<String, Object>) m).get("header")).trim();
                String path = str(((Map<String, Object>) m).get("path")).trim();
                if (!header.isEmpty() || !path.isEmpty()) columns.add(new FileFormats.Column(header.isEmpty() ? path : header, path));
            }
        }

        FileFormats.Written written = FileFormats.write(format, data, content, columns);
        String name = fileName(resolver.resolve(str(cfg.get("fileName")), nco), written.extension());
        StoredFile file = storage.store(nco.getMeta().getUserId(), name, written.mimeType(), written.bytes(), "FLOW",
                FileReferences.uuid(nco.getMeta().getFlowId()), FileReferences.uuid(nco.getMeta().getExecutionId()));

        Map<String, Object> out = new LinkedHashMap<>(storage.describe(file));
        out.put("format", format);
        if (written.rowCount() > 0) out.put("rowCount", written.rowCount());
        return NodeContext.builder()
                .nodeId(nodeId).nodeType(NodeType.FILE.name())
                .status(NodeStatus.SUCCESS)
                .input(Map.of("operation", "WRITE", "format", format, "fileName", name))
                .successOutput(out)
                .build();
    }

    /** A reference like {{nex.rows}} keeps its real shape (list / object); other text is filled in as text. */
    private Object resolveData(Object raw, NexflowContextObject nco) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) return raw;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (FileReferences.isSingleReference(t)) {
            Object value = resolver.resolveToObject(t, nco);
            if (value == null) throw new IllegalArgumentException("Data " + t + " is empty. Check the reference — it must point at an earlier step's output.");
            return value;
        }
        if (t.matches("(nex|nodes|variables)\\.[\\w.\\[\\]]+")) {
            Object value = resolver.resolveToObject(t, nco);
            if (value != null) return value;
        }
        return resolver.resolveForText(s, nco);
    }

    static String fileName(String requested, String extension) {
        String name = requested != null ? requested.trim() : "";
        if (name.isEmpty()) name = "nexflow-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        if (!FileStorageService.extension(name).equals(extension)) name = name + "." + extension;
        return name;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static NodeContext failure(String nodeId, Map<String, Object> input, String message, String detail) {
        return NodeContext.builder()
                .nodeId(nodeId).nodeType(NodeType.FILE.name())
                .status(NodeStatus.FAILURE)
                .input(input)
                .failureOutput(Map.of("error", message))
                .errorMessage(message)
                .errorDetail(detail)
                .build();
    }
}
