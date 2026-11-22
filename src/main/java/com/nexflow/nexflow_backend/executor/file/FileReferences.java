package com.nexflow.nexflow_backend.executor.file;

import com.nexflow.nexflow_backend.engine.FriendlyErrors;
import com.nexflow.nexflow_backend.executor.ReferenceResolver;
import com.nexflow.nexflow_backend.model.domain.StoredFile;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves what a node config calls "the file" — {{nex.file}}, a fileId, or a URL — to a stored file
 * the flow's owner is allowed to use. Shared by the FILE and MESSAGE nodes.
 */
@Component
@RequiredArgsConstructor
public class FileReferences {

    private final FileStorageService storage;
    private final ReferenceResolver resolver;
    private final RestTemplate restTemplate;

    /** @param allowUrl download http(s) URLs into storage (FILE read); otherwise only stored files are accepted. */
    @SuppressWarnings("unchecked")
    public StoredFile resolve(Object raw, NexflowContextObject nco, boolean allowUrl) {
        Object value = raw;
        if (raw instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) throw new IllegalArgumentException("Choose which file to use (for example {{nex.file}}).");
            value = isSingleReference(t) ? resolver.resolveToObject(t, nco) : resolver.resolve(t, nco);
        }
        if (value instanceof List<?> list && !list.isEmpty()) value = list.get(0);
        if (value instanceof Map<?, ?> map) {
            Object id = ((Map<String, Object>) map).get("fileId");
            if (id == null) throw new IllegalArgumentException("The value given as the file has no fileId.");
            value = id.toString();
        }
        String ref = value != null ? value.toString().trim() : "";
        if (ref.isEmpty()) {
            throw new IllegalArgumentException("No file was found at " + raw + ". Did this run start with a file? "
                    + "Use Run with file in Studio, upload one on the Files page, or send an attachment in chat.");
        }
        UUID owner = nco.getMeta().getUserId();
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            if (!allowUrl) throw new IllegalArgumentException("Attach a stored file (e.g. the output of a File step), not a URL.");
            return download(ref, owner, nco);
        }
        UUID id;
        try {
            id = UUID.fromString(ref);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + ref + "' isn't a file id" + (allowUrl ? " or URL." : "."));
        }
        StoredFile file = storage.find(id);
        if (owner == null || !owner.equals(file.getUserId())) {
            throw new IllegalArgumentException("This flow's owner doesn't have access to that file.");
        }
        return file;
    }

    /** "{{nex.file}}" or just "nex.file". */
    public static boolean isSingleReference(String t) {
        return ReferenceResolver.isSingleReference(t);
    }

    private StoredFile download(String url, UUID owner, NexflowContextObject nco) {
        ResponseEntity<byte[]> response;
        try {
            response = restTemplate.getForEntity(url, byte[].class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not download " + url + ": " + FriendlyErrors.forException(e, url));
        }
        byte[] body = response.getBody() != null ? response.getBody() : new byte[0];
        String type = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        String name = url.replaceAll("[?#].*$", "");
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank()) name = "download";
        return storage.store(owner, name, type, body, "FLOW", uuid(nco.getMeta().getFlowId()), uuid(nco.getMeta().getExecutionId()));
    }

    public static UUID uuid(String s) {
        try {
            return s != null ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
