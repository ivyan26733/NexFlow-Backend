package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.StoredFile;
import com.nexflow.nexflow_backend.model.domain.UserRole;
import com.nexflow.nexflow_backend.repository.StoredFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Stores file bytes on local disk (app.files.dir) and their metadata in stored_files.
 *
 * Every file belongs to a user. Files are reachable by the owner through the authenticated API, and by
 * anyone holding a signed download link (used for chat replies and sharing generated reports).
 */
@Slf4j
@Service
public class FileStorageService {

    /** How long a signed download link keeps working. */
    static final Duration LINK_TTL = Duration.ofDays(7);

    private final StoredFileRepository files;
    private final Path root;
    private final long maxBytes;
    private final String publicBaseUrl;
    private final byte[] signingKey;
    private final ObjectMapper objectMapper;

    public FileStorageService(StoredFileRepository files,
                              ObjectMapper objectMapper,
                              @Value("${app.files.dir:./data/files}") String dir,
                              @Value("${app.files.max-bytes:26214400}") long maxBytes,
                              @Value("${app.public-base-url:http://localhost:8090}") String publicBaseUrl,
                              @Value("${app.jwt.secret}") String secret) {
        this.files = files;
        this.objectMapper = objectMapper;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.signingKey = ("files:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** Saves bytes as a new file owned by {@code userId}. */
    public StoredFile store(UUID userId, String name, String mimeType, byte[] bytes,
                            String source, UUID flowId, UUID executionId) {
        if (userId == null) throw new IllegalArgumentException("A file needs an owner.");
        if (bytes == null) bytes = new byte[0];
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("That file is " + humanSize(bytes.length)
                    + ". Files can be at most " + humanSize(maxBytes) + ".");
        }
        String safeName = sanitizeName(name);
        UUID id = UUID.randomUUID();
        String key = LocalDate.now() + "/" + id + "-" + safeName;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the file to disk", e);
        }

        StoredFile file = new StoredFile();
        file.setUserId(userId);
        file.setName(safeName);
        file.setMimeType(mimeType != null && !mimeType.isBlank() ? mimeType : guessMimeType(safeName));
        file.setSizeBytes(bytes.length);
        file.setStorageKey(key);
        file.setSource(source != null ? source : "UPLOAD");
        file.setFlowId(flowId);
        file.setExecutionId(executionId);
        return files.save(file);
    }

    public byte[] read(StoredFile file) {
        try {
            return Files.readAllBytes(resolve(file.getStorageKey()));
        } catch (IOException e) {
            throw new IllegalArgumentException("The file '" + file.getName() + "' is no longer on the server.");
        }
    }

    public StoredFile find(UUID id) {
        return files.findById(id).orElseThrow(() -> new IllegalArgumentException("File not found."));
    }

    /** The file if {@code user} owns it (admins can open any file). */
    public StoredFile findAccessible(UUID id, NexUser user) {
        StoredFile file = find(id);
        if (!canAccess(file, user)) throw new AccessDeniedException("No access to this file");
        return file;
    }

    public boolean canAccess(StoredFile file, NexUser user) {
        return user != null && (user.getRole() == UserRole.ADMIN || user.getId().equals(file.getUserId()));
    }

    public List<StoredFile> listFor(NexUser user) {
        return files.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    public void delete(StoredFile file) {
        try {
            Files.deleteIfExists(resolve(file.getStorageKey()));
        } catch (IOException e) {
            log.warn("[Files] could not delete bytes of {}: {}", file.getId(), e.getMessage());
        }
        files.delete(file);
    }

    // ── Signed download links ────────────────────────────────────────────────

    public String signedDownloadUrl(StoredFile file) {
        long expires = Instant.now().plus(LINK_TTL).getEpochSecond();
        return publicBaseUrl + "/api/files/public/" + file.getId() + "?exp=" + expires + "&sig=" + sign(file.getId(), expires);
    }

    public boolean verifySignature(UUID id, long expires, String signature) {
        if (signature == null || Instant.now().getEpochSecond() > expires) return false;
        return MessageDigest.isEqual(sign(id, expires).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(UUID id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign download link", e);
        }
    }

    /**
     * How a file appears inside a flow ({{nex.file.fileId}}, {{nex.file.name}} …) and in API responses.
     */
    public Map<String, Object> describe(StoredFile file) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileId", file.getId().toString());
        out.put("name", file.getName());
        out.put("mimeType", file.getMimeType());
        out.put("size", file.getSizeBytes());
        out.put("source", file.getSource());
        out.put("downloadUrl", signedDownloadUrl(file));
        out.put("createdAt", file.getCreatedAt().toString());
        if (file.getFlowId() != null) out.put("flowId", file.getFlowId().toString());
        if (file.getExecutionId() != null) out.put("executionId", file.getExecutionId().toString());
        return out;
    }

    /**
     * Trigger body for a run started with files: the extra fields plus
     * {@code file} (the first file) and {@code files} (all of them), so a flow reads {{nex.file.fileId}}.
     */
    public Map<String, Object> triggerPayload(List<StoredFile> uploaded, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra != null ? extra : Map.of());
        List<Map<String, Object>> described = uploaded.stream().map(this::describe).toList();
        if (!described.isEmpty()) payload.put("file", described.get(0));
        payload.put("files", described);
        return payload;
    }

    /**
     * Saves uploaded files (owned by {@code ownerId}) and builds the trigger body {file, files, ...payloadJson}.
     * Used by Studio "Run with file" and the public POST /api/pulse/{slug}/file.
     */
    public Map<String, Object> uploadPayload(List<MultipartFile> uploads, String payloadJson,
                                             UUID ownerId, UUID flowId) throws IOException {
        Map<String, Object> extra = Map.of();
        if (payloadJson != null && !payloadJson.isBlank()) {
            try {
                extra = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            } catch (IOException e) {
                throw new IllegalArgumentException("The extra input isn't a valid JSON object.");
            }
        }
        List<StoredFile> saved = new ArrayList<>();
        for (MultipartFile upload : uploads != null ? uploads : List.<MultipartFile>of()) {
            if (upload.isEmpty()) continue;
            saved.add(store(ownerId, upload.getOriginalFilename(), upload.getContentType(), upload.getBytes(), "UPLOAD", flowId, null));
        }
        if (saved.isEmpty()) throw new IllegalArgumentException("Choose a file to send to the flow.");
        return triggerPayload(saved, extra);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid file path.");
        return path;
    }

    /** Keeps the name readable but safe as a path segment. */
    static String sanitizeName(String name) {
        String base = name == null ? "" : name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[^A-Za-z0-9._ ()-]", "_").trim();
        while (base.startsWith(".")) base = base.substring(1);
        if (base.isBlank()) base = "file";
        return base.length() > 150 ? base.substring(base.length() - 150) : base;
    }

    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static String guessMimeType(String name) {
        return switch (extension(name)) {
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "txt", "log" -> "text/plain";
            case "md" -> "text/markdown";
            case "html", "htm" -> "text/html";
            case "xml" -> "application/xml";
            case "pdf" -> "application/pdf";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp3" -> "audio/mpeg";
            case "ogg", "oga" -> "audio/ogg";
            case "mp4" -> "video/mp4";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
