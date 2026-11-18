package com.nexflow.nexflow_backend.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.ChannelConnection;
import com.nexflow.nexflow_backend.model.domain.ChannelPlatform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the chat platforms' APIs: send a message (optionally with a file), download an incoming
 * attachment, check credentials, and register the Telegram webhook.
 *
 * Failures throw {@link ChannelException} with a message a user can act on.
 */
@Slf4j
@Component
public class ChannelMessenger {

    /** Platform text limits; longer messages are cut with an ellipsis rather than rejected. */
    private static final Map<ChannelPlatform, Integer> TEXT_LIMIT = Map.of(
            ChannelPlatform.SLACK, 39_000,
            ChannelPlatform.TELEGRAM, 4_096,
            ChannelPlatform.WHATSAPP, 4_096,
            ChannelPlatform.DISCORD, 2_000);

    private final RestTemplate http;
    private final ObjectMapper json;
    private final String slackApi;
    private final String telegramApi;
    private final String whatsappApi;
    private final String discordApi;

    public ChannelMessenger(RestTemplate restTemplate, ObjectMapper objectMapper,
                            @Value("${app.channels.slack-api:https://slack.com/api}") String slackApi,
                            @Value("${app.channels.telegram-api:https://api.telegram.org}") String telegramApi,
                            @Value("${app.channels.whatsapp-api:https://graph.facebook.com/v21.0}") String whatsappApi,
                            @Value("${app.channels.discord-api:https://discord.com/api/v10}") String discordApi) {
        this.http = restTemplate;
        this.json = objectMapper;
        this.slackApi = slackApi;
        this.telegramApi = telegramApi;
        this.whatsappApi = whatsappApi;
        this.discordApi = discordApi;
    }

    public static class ChannelException extends RuntimeException {
        public ChannelException(String message) {
            super(message);
        }
    }

    /** A file to attach to an outgoing message. */
    public record OutgoingFile(String name, String mimeType, byte[] bytes) {}

    // ══ SEND ═════════════════════════════════════════════════════════════════

    /**
     * Sends {@code text} (and {@code file}, if given) to {@code to}.
     * {@code replyContext} comes from the incoming message when replying; it lets the reply land in the
     * same thread (Slack), quote the message (Telegram / WhatsApp) or answer the slash command (Discord).
     */
    public Map<String, Object> send(ChannelConnection c, String to, String text, OutgoingFile file,
                                    Map<String, Object> replyContext) {
        String body = limit(c.getPlatform(), text != null ? text : "");
        Map<String, Object> reply = replyContext != null ? replyContext : Map.of();
        return switch (c.getPlatform()) {
            case SLACK -> sendSlack(c, to, body, file, str(reply.get("threadTs")));
            case TELEGRAM -> sendTelegram(c, to, body, file, str(reply.get("messageId")));
            case WHATSAPP -> sendWhatsapp(c, to, body, file, str(reply.get("messageId")));
            case DISCORD -> sendDiscord(c, to, body, file, str(reply.get("applicationId")), str(reply.get("interactionToken")));
        };
    }

    private Map<String, Object> sendSlack(ChannelConnection c, String channel, String text, OutgoingFile file, String threadTs) {
        String token = need(c, "botToken", "Slack bot token (xoxb-…)");
        requireTarget(channel, "a Slack channel ID (e.g. C0123456789)");
        if (file != null) {
            // Slack's upload flow: get an upload URL, send the bytes there, then share the file in the channel.
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("filename", file.name());
            form.add("length", String.valueOf(file.bytes().length));
            JsonNode upload = slackOk(postForm(slackApi + "/files.getUploadURLExternal", form, bearer(token)), "start the file upload");
            HttpHeaders raw = new HttpHeaders();
            raw.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            call(upload.path("upload_url").asText(), HttpMethod.POST, new HttpEntity<>(file.bytes(), raw), "upload the file to Slack");

            Map<String, Object> complete = new LinkedHashMap<>();
            complete.put("files", List.of(Map.of("id", upload.path("file_id").asText(), "title", file.name())));
            complete.put("channel_id", channel);
            if (!text.isBlank()) complete.put("initial_comment", text);
            if (threadTs != null) complete.put("thread_ts", threadTs);
            slackOk(postJson(slackApi + "/files.completeUploadExternal", complete, bearer(token)), "share the file");
            return result(ChannelPlatform.SLACK, channel, upload.path("file_id").asText());
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("channel", channel);
        msg.put("text", requireText(text));
        if (threadTs != null) msg.put("thread_ts", threadTs);
        JsonNode res = slackOk(postJson(slackApi + "/chat.postMessage", msg, bearer(token)), "send the message");
        return result(ChannelPlatform.SLACK, res.path("channel").asText(channel), res.path("ts").asText(null));
    }

    private Map<String, Object> sendTelegram(ChannelConnection c, String chatId, String text, OutgoingFile file, String replyTo) {
        String token = need(c, "botToken", "Telegram bot token");
        requireTarget(chatId, "a Telegram chat ID");
        JsonNode res;
        if (file != null) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("chat_id", chatId);
            if (!text.isBlank()) parts.add("caption", text.length() > 1024 ? text.substring(0, 1021) + "…" : text);
            if (replyTo != null) parts.add("reply_to_message_id", replyTo);
            parts.add("document", fileResource(file));
            res = telegramOk(postMultipart(telegramApi + "/bot" + token + "/sendDocument", parts, new HttpHeaders()), "send the file");
        } else {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("chat_id", chatId);
            msg.put("text", requireText(text));
            if (replyTo != null) msg.put("reply_parameters", Map.of("message_id", Long.parseLong(replyTo), "allow_sending_without_reply", true));
            res = telegramOk(postJson(telegramApi + "/bot" + token + "/sendMessage", msg, new HttpHeaders()), "send the message");
        }
        return result(ChannelPlatform.TELEGRAM, chatId, res.path("result").path("message_id").asText(null));
    }

    private Map<String, Object> sendWhatsapp(ChannelConnection c, String to, String text, OutgoingFile file, String replyTo) {
        String token = need(c, "accessToken", "WhatsApp access token");
        String phoneId = need(c, "phoneNumberId", "WhatsApp phone number ID");
        requireTarget(to, "a phone number with country code (e.g. 919876543210)");
        String number = to.replaceAll("[^0-9]", "");

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("messaging_product", "whatsapp");
        msg.put("to", number);
        if (replyTo != null) msg.put("context", Map.of("message_id", replyTo));
        if (file != null) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("messaging_product", "whatsapp");
            parts.add("type", file.mimeType() != null ? file.mimeType() : "application/octet-stream");
            parts.add("file", fileResource(file));
            String mediaId = parseOrFail(postMultipart(whatsappApi + "/" + phoneId + "/media", parts, bearer(token)), "upload the file to WhatsApp")
                    .path("id").asText();
            boolean image = file.mimeType() != null && file.mimeType().startsWith("image/");
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("id", mediaId);
            if (!image) media.put("filename", file.name());
            if (!text.isBlank()) media.put("caption", text);
            msg.put("type", image ? "image" : "document");
            msg.put(image ? "image" : "document", media);
        } else {
            msg.put("type", "text");
            msg.put("text", Map.of("body", requireText(text), "preview_url", false));
        }
        JsonNode res = parseOrFail(postJson(whatsappApi + "/" + phoneId + "/messages", msg, bearer(token)), "send the WhatsApp message");
        return result(ChannelPlatform.WHATSAPP, number, res.path("messages").path(0).path("id").asText(null));
    }

    private Map<String, Object> sendDiscord(ChannelConnection c, String channelId, String text, OutgoingFile file,
                                            String applicationId, String interactionToken) {
        String url;
        HttpHeaders headers = new HttpHeaders();
        if (applicationId != null && interactionToken != null) {
            url = discordApi + "/webhooks/" + applicationId + "/" + interactionToken; // follow-up to the slash command
        } else {
            String token = need(c, "botToken", "Discord bot token (needed to post outside a slash-command reply)");
            requireTarget(channelId, "a Discord channel ID");
            url = discordApi + "/channels/" + channelId + "/messages";
            headers.set(HttpHeaders.AUTHORIZATION, "Bot " + token);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!text.isBlank()) payload.put("content", text);
        String response;
        if (file != null) {
            payload.put("attachments", List.of(Map.of("id", 0, "filename", file.name())));
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            HttpHeaders jsonPart = new HttpHeaders();
            jsonPart.setContentType(MediaType.APPLICATION_JSON);
            parts.add("payload_json", new HttpEntity<>(toJson(payload), jsonPart));
            parts.add("files[0]", fileResource(file));
            response = postMultipart(url, parts, headers);
        } else {
            payload.put("content", requireText(text));
            response = postJson(url, payload, headers);
        }
        JsonNode res = parseOrFail(response, "send the Discord message");
        return result(ChannelPlatform.DISCORD, res.path("channel_id").asText(channelId), res.path("id").asText(null));
    }

    // ══ DOWNLOAD (incoming attachments) ══════════════════════════════════════

    public byte[] download(ChannelConnection c, ChannelInbound.Attachment a) {
        return switch (c.getPlatform()) {
            case SLACK -> bytes(a.ref(), bearer(need(c, "botToken", "Slack bot token")), "download the Slack file");
            case TELEGRAM -> {
                String token = need(c, "botToken", "Telegram bot token");
                JsonNode info = telegramOk(get(telegramApi + "/bot" + token + "/getFile?file_id=" + a.ref(), new HttpHeaders()), "find the Telegram file");
                yield bytes(telegramApi + "/file/bot" + token + "/" + info.path("result").path("file_path").asText(), new HttpHeaders(), "download the Telegram file");
            }
            case WHATSAPP -> {
                HttpHeaders auth = bearer(need(c, "accessToken", "WhatsApp access token"));
                JsonNode info = parseOrFail(get(whatsappApi + "/" + a.ref(), auth), "find the WhatsApp media");
                yield bytes(info.path("url").asText(), auth, "download the WhatsApp media");
            }
            case DISCORD -> bytes(a.ref(), new HttpHeaders(), "download the Discord attachment");
        };
    }

    // ══ CHECK / SETUP ════════════════════════════════════════════════════════

    /** Calls a cheap "who am I" endpoint. Returns a short description of the bot / account. */
    public String check(ChannelConnection c) {
        return switch (c.getPlatform()) {
            case SLACK -> {
                JsonNode res = slackOk(postForm(slackApi + "/auth.test", new LinkedMultiValueMap<>(), bearer(need(c, "botToken", "Slack bot token"))), "check the token");
                yield "Connected to Slack workspace " + res.path("team").asText() + " as @" + res.path("user").asText();
            }
            case TELEGRAM -> {
                JsonNode res = telegramOk(get(telegramApi + "/bot" + need(c, "botToken", "Telegram bot token") + "/getMe", new HttpHeaders()), "check the token");
                yield "Connected to Telegram bot @" + res.path("result").path("username").asText();
            }
            case WHATSAPP -> {
                JsonNode res = parseOrFail(get(whatsappApi + "/" + need(c, "phoneNumberId", "WhatsApp phone number ID")
                        + "?fields=display_phone_number,verified_name", bearer(need(c, "accessToken", "WhatsApp access token"))), "check the credentials");
                yield "Connected to WhatsApp number " + res.path("display_phone_number").asText() + " (" + res.path("verified_name").asText() + ")";
            }
            case DISCORD -> {
                if (!ChannelSignatures.isDiscordPublicKey(c.credential("publicKey"))) {
                    throw new ChannelException("The Discord public key should be 64 hex characters (General Information → Public Key).");
                }
                String token = c.credential("botToken");
                if (token == null) yield "Public key looks valid. Add a bot token to also post outside slash-command replies.";
                HttpHeaders auth = new HttpHeaders();
                auth.set(HttpHeaders.AUTHORIZATION, "Bot " + token);
                JsonNode res = parseOrFail(get(discordApi + "/users/@me", auth), "check the bot token");
                yield "Connected to Discord bot " + res.path("username").asText();
            }
        };
    }

    public void registerTelegramWebhook(ChannelConnection c, String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("secret_token", need(c, "secretToken", "webhook secret"));
        body.put("allowed_updates", List.of("message", "channel_post"));
        telegramOk(postJson(telegramApi + "/bot" + need(c, "botToken", "Telegram bot token") + "/setWebhook", body, new HttpHeaders()), "register the webhook");
    }

    // ══ HTTP helpers ═════════════════════════════════════════════════════════

    private String postJson(String url, Object body, HttpHeaders headers) {
        HttpHeaders h = copy(headers);
        h.setContentType(MediaType.APPLICATION_JSON);
        return call(url, HttpMethod.POST, new HttpEntity<>(toJson(body), h), null);
    }

    private String postForm(String url, MultiValueMap<String, String> form, HttpHeaders headers) {
        HttpHeaders h = copy(headers);
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return call(url, HttpMethod.POST, new HttpEntity<>(form, h), null);
    }

    private String postMultipart(String url, MultiValueMap<String, Object> parts, HttpHeaders headers) {
        HttpHeaders h = copy(headers);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return call(url, HttpMethod.POST, new HttpEntity<>(parts, h), null);
    }

    private String get(String url, HttpHeaders headers) {
        return call(url, HttpMethod.GET, new HttpEntity<>(headers), null);
    }

    private String call(String url, HttpMethod method, HttpEntity<?> entity, String what) {
        try {
            ResponseEntity<String> res = http.exchange(url, method, entity, String.class);
            return res.getBody() != null ? res.getBody() : "";
        } catch (HttpStatusCodeException e) {
            String reason = platformError(e.getResponseBodyAsString());
            throw new ChannelException((what != null ? "Couldn't " + what + ": " : "")
                    + "the platform answered " + e.getStatusCode().value() + (reason != null ? " — " + reason : ""));
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("Couldn't reach the platform" + (what != null ? " to " + what : "") + ": " + e.getMessage());
        }
    }

    private byte[] bytes(String url, HttpHeaders headers, String what) {
        try {
            ResponseEntity<byte[]> res = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            return res.getBody() != null ? res.getBody() : new byte[0];
        } catch (Exception e) {
            throw new ChannelException("Couldn't " + what + ": " + e.getMessage());
        }
    }

    /** Pulls the human-readable reason out of a platform error body. */
    private String platformError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = json.readTree(body);
            if (n.hasNonNull("description")) return n.path("description").asText();           // Telegram
            if (n.path("error").isTextual()) return n.path("error").asText();                   // Slack
            if (n.path("error").hasNonNull("message")) return n.path("error").path("message").asText(); // Meta
            if (n.hasNonNull("message")) return n.path("message").asText();                     // Discord
        } catch (Exception ignored) {
            // not JSON
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private JsonNode parseOrFail(String body, String what) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new ChannelException("Couldn't " + what + ": unexpected response from the platform.");
        }
    }

    private JsonNode slackOk(String body, String what) {
        JsonNode n = parseOrFail(body, what);
        if (!n.path("ok").asBoolean(false)) {
            String error = n.path("error").asText("unknown_error");
            throw new ChannelException("Slack couldn't " + what + ": " + error + slackHint(error));
        }
        return n;
    }

    private static String slackHint(String error) {
        return switch (error) {
            case "not_in_channel" -> " — invite the bot to the channel first (/invite @your-bot).";
            case "channel_not_found" -> " — use the channel ID (e.g. C0123456789), not its name.";
            case "invalid_auth", "not_authed" -> " — the bot token is wrong or was revoked.";
            case "missing_scope" -> " — add the chat:write (and files:write for files) scope and reinstall the app.";
            default -> "";
        };
    }

    private JsonNode telegramOk(String body, String what) {
        JsonNode n = parseOrFail(body, what);
        if (!n.path("ok").asBoolean(false)) {
            throw new ChannelException("Telegram couldn't " + what + ": " + n.path("description").asText("unknown error"));
        }
        return n;
    }

    private static ByteArrayResource fileResource(OutgoingFile file) {
        return new ByteArrayResource(file.bytes()) {
            @Override
            public String getFilename() {
                return file.name();
            }
        };
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new ChannelException("Couldn't build the message: " + e.getMessage());
        }
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static HttpHeaders copy(HttpHeaders headers) {
        HttpHeaders h = new HttpHeaders();
        h.putAll(headers);
        return h;
    }

    private static String need(ChannelConnection c, String key, String what) {
        String value = c.credential(key);
        if (value == null) throw new ChannelException("The channel '" + c.getLabel() + "' has no " + what + ". Add it on the Channels page.");
        return value;
    }

    private static void requireTarget(String to, String what) {
        if (to == null || to.isBlank()) throw new ChannelException("Say where to send the message: " + what + ".");
    }

    private static String requireText(String text) {
        if (text == null || text.isBlank()) throw new ChannelException("The message is empty. Write some text or attach a file.");
        return text;
    }

    private static String limit(ChannelPlatform platform, String text) {
        int max = TEXT_LIMIT.get(platform);
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String str(Object o) {
        return o != null && !o.toString().isBlank() ? o.toString() : null;
    }

    private static Map<String, Object> result(ChannelPlatform platform, String to, String messageId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);
        out.put("platform", platform.name());
        out.put("to", to);
        if (messageId != null) out.put("messageId", messageId);
        return out;
    }
}
