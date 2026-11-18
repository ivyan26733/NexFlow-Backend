package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.channel.ChannelMessenger;
import com.nexflow.nexflow_backend.channel.ChannelMessenger.ChannelException;
import com.nexflow.nexflow_backend.channel.ChannelMessenger.OutgoingFile;
import com.nexflow.nexflow_backend.executor.ReferenceResolver;
import com.nexflow.nexflow_backend.executor.file.FileReferences;
import com.nexflow.nexflow_backend.model.domain.ChannelConnection;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.domain.StoredFile;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.ChannelConnectionRepository;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import com.nexflow.nexflow_backend.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MESSAGE node — sends a chat message, optionally with a file.
 *
 * Config:
 * {
 *   "connectionId": "",              // empty = reply through the channel that started this run
 *   "to": "{{nex.message.chatId}}",  // optional when replying; channel ID / chat ID / phone number
 *   "text": "Hi {{nex.message.from.name}}, your report is ready.",
 *   "file": "{{nex.report}}",        // optional — a File step's output or a fileId
 *   "replyInThread": true            // reply in the Slack thread / quote the message (default true)
 * }
 * Output: { sent, platform, to, messageId }.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageNodeExecutor implements NodeExecutor {

    private final ChannelConnectionRepository connections;
    private final ChannelMessenger messenger;
    private final ReferenceResolver resolver;
    private final FileReferences fileReferences;
    private final FileStorageService storage;

    @Override
    public NodeType supportedType() {
        return NodeType.MESSAGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> cfg = node.getConfig() != null ? node.getConfig() : Map.of();
        String nodeId = node.getId().toString();

        Map<String, Object> trigger = triggerBody(nco);
        Map<String, Object> triggerChannel = trigger.get("channel") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> triggerMessage = trigger.get("message") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        String connectionId = str(cfg.get("connectionId"));
        boolean replying = connectionId == null;
        if (replying) connectionId = str(triggerChannel.get("connectionId"));
        if (connectionId == null) {
            return failure(nodeId, Map.of(), "This run wasn't started by a chat message, so there's nobody to reply to. "
                    + "Pick a channel on this step and say who to send to.");
        }

        ChannelConnection connection;
        try {
            connection = connections.findById(UUID.fromString(connectionId)).orElse(null);
        } catch (IllegalArgumentException e) {
            connection = null;
        }
        if (connection == null) return failure(nodeId, Map.of(), "The channel this step sends with no longer exists. Pick another one.");
        UUID owner = nco.getMeta().getUserId();
        if (owner == null || !owner.equals(connection.getUserId())) {
            return failure(nodeId, Map.of(), "The channel '" + connection.getLabel() + "' belongs to someone else. Use one of your own channels.");
        }

        boolean sameChannelAsTrigger = connectionId.equals(str(triggerChannel.get("connectionId")));
        String to = str(resolver.resolve(str(cfg.get("to")), nco));
        String triggerChat = str(triggerMessage.get("chatId"));
        if (to == null && sameChannelAsTrigger) to = triggerChat;

        Map<String, Object> replyContext = Map.of();
        boolean inThread = !Boolean.FALSE.equals(cfg.get("replyInThread"));
        if (sameChannelAsTrigger && triggerMessage.get("replyContext") instanceof Map<?, ?> rc && (to == null || to.equals(triggerChat))) {
            Map<String, Object> ctx = new LinkedHashMap<>((Map<String, Object>) rc);
            if (!inThread) {
                ctx.remove("threadTs");
                ctx.remove("messageId");
            }
            replyContext = ctx;
        }

        String text = resolver.resolveForText(str(cfg.get("text")) != null ? str(cfg.get("text")) : "", nco);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("channel", connection.getLabel());
        input.put("platform", connection.getPlatform().name());
        input.put("to", to);
        input.put("text", text);

        try {
            OutgoingFile file = null;
            Object fileRef = cfg.get("file");
            if (fileRef != null && !fileRef.toString().isBlank()) {
                StoredFile stored = fileReferences.resolve(fileRef, nco, false);
                file = new OutgoingFile(stored.getName(), stored.getMimeType(), storage.read(stored));
                input.put("file", stored.getName());
            }
            Map<String, Object> sent = messenger.send(connection, to, text, file, replyContext);
            return NodeContext.builder()
                    .nodeId(nodeId).nodeType(NodeType.MESSAGE.name())
                    .status(NodeStatus.SUCCESS)
                    .input(input)
                    .successOutput(sent)
                    .build();
        } catch (ChannelException | IllegalArgumentException e) {
            return failure(nodeId, input, e.getMessage());
        } catch (Exception e) {
            log.warn("[MESSAGE] '{}' failed: {}", node.displayName(), e.getMessage(), e);
            return failure(nodeId, input, "Couldn't send the message: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> triggerBody(NexflowContextObject nco) {
        NodeContext start = nco.getNodeOutput("start");
        if (start == null || start.getOutput() == null) return Map.of();
        Object body = start.getOutput().get("body");
        return body instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o != null && !o.toString().isBlank() ? o.toString().trim() : null;
    }

    private static NodeContext failure(String nodeId, Map<String, Object> input, String message) {
        return NodeContext.builder()
                .nodeId(nodeId).nodeType(NodeType.MESSAGE.name())
                .status(NodeStatus.FAILURE)
                .input(input)
                .failureOutput(Map.of("error", message))
                .errorMessage(message)
                .errorDetail(message)
                .build();
    }
}
