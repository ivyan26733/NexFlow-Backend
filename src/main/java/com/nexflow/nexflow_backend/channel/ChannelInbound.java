package com.nexflow.nexflow_backend.channel;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns each platform's webhook body into one shape, {@link Message}, so every flow sees the same
 * fields whichever chat app the message came from. Pure parsing — no network, no database.
 */
public final class ChannelInbound {

    private ChannelInbound() {}

    /** A file attached to an incoming message. {@code ref} is the platform's file id or URL used to download it. */
    public record Attachment(String name, String mimeType, String ref, Long size) {}

    /** One incoming chat message, normalised. */
    public record Message(
            String messageId,
            String text,
            String command,           // "/report" when the message is a slash command, else null
            String args,              // text after the command
            String chatId,            // where replies go: Slack channel, Telegram chat, WhatsApp number, Discord channel
            String threadId,          // Slack thread ts (if any)
            String fromId,
            String fromName,
            String fromHandle,        // @username / phone number
            Instant timestamp,
            List<Attachment> attachments,
            Map<String, Object> replyContext) {

        public String dedupeKey() {
            return chatId + ":" + messageId;
        }
    }

    // ── Slack ────────────────────────────────────────────────────────────────

    /** Events API "event_callback". Returns null for events that shouldn't start a flow (bots, edits, joins…). */
    public static Message slackEvent(JsonNode root) {
        JsonNode event = root.path("event");
        String type = event.path("type").asText();
        if (!type.equals("message") && !type.equals("app_mention")) return null;
        if (event.hasNonNull("bot_id") || event.has("bot_profile")) return null; // our own replies, other bots
        String subtype = event.path("subtype").asText("");
        if (!subtype.isEmpty() && !subtype.equals("file_share") && !subtype.equals("thread_broadcast")) return null;

        String text = event.path("text").asText("");
        if (type.equals("app_mention")) text = text.replaceAll("<@[A-Z0-9]+>", "").trim();
        String ts = event.path("ts").asText();

        List<Attachment> files = new ArrayList<>();
        for (JsonNode f : event.path("files")) {
            String url = f.path("url_private_download").asText(f.path("url_private").asText(""));
            if (!url.isEmpty()) files.add(new Attachment(f.path("name").asText("file"), f.path("mimetype").asText(null), url, longOrNull(f.path("size"))));
        }

        String channel = event.path("channel").asText();
        String thread = event.hasNonNull("thread_ts") ? event.path("thread_ts").asText() : null;
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("channel", channel);
        reply.put("threadTs", thread != null ? thread : ts);

        String[] cmd = splitCommand(text);
        return new Message(ts, text, cmd[0], cmd[1], channel, thread,
                event.path("user").asText(null), null, event.path("user").asText(null),
                epochSeconds(ts), files, reply);
    }

    /** Slash command (application/x-www-form-urlencoded). */
    public static Message slackCommand(Map<String, String> form) {
        String command = form.getOrDefault("command", "");
        String args = form.getOrDefault("text", "");
        String channel = form.get("channel_id");
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("channel", channel);
        if (form.containsKey("response_url")) reply.put("responseUrl", form.get("response_url"));
        return new Message(form.getOrDefault("trigger_id", String.valueOf(System.nanoTime())),
                (command + " " + args).trim(), command, args, channel, null,
                form.get("user_id"), form.get("user_name"), form.get("user_name"),
                Instant.now(), List.of(), reply);
    }

    public static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return form;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
            String value = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            form.put(key, value);
        }
        return form;
    }

    // ── Telegram ─────────────────────────────────────────────────────────────

    public static Message telegram(JsonNode update) {
        JsonNode msg = update.has("message") ? update.path("message")
                : update.has("channel_post") ? update.path("channel_post") : null;
        if (msg == null || msg.isMissingNode()) return null;
        if (msg.path("from").path("is_bot").asBoolean(false)) return null;

        String text = msg.has("text") ? msg.path("text").asText() : msg.path("caption").asText("");
        List<Attachment> files = new ArrayList<>();
        JsonNode doc = msg.path("document");
        if (!doc.isMissingNode()) {
            files.add(new Attachment(doc.path("file_name").asText("document"), doc.path("mime_type").asText(null),
                    doc.path("file_id").asText(), longOrNull(doc.path("file_size"))));
        }
        JsonNode photos = msg.path("photo");
        if (photos.isArray() && photos.size() > 0) {
            JsonNode largest = photos.get(photos.size() - 1);
            files.add(new Attachment("photo-" + msg.path("message_id").asText() + ".jpg", "image/jpeg",
                    largest.path("file_id").asText(), longOrNull(largest.path("file_size"))));
        }
        for (String kind : List.of("audio", "voice", "video")) {
            JsonNode media = msg.path(kind);
            if (media.isMissingNode()) continue;
            String fallback = kind + "-" + msg.path("message_id").asText() + (kind.equals("video") ? ".mp4" : ".ogg");
            files.add(new Attachment(media.path("file_name").asText(fallback), media.path("mime_type").asText(null),
                    media.path("file_id").asText(), longOrNull(media.path("file_size"))));
        }

        JsonNode from = msg.path("from");
        String name = (from.path("first_name").asText("") + " " + from.path("last_name").asText("")).trim();
        if (name.isEmpty()) name = msg.path("chat").path("title").asText(null);
        String chatId = msg.path("chat").path("id").asText();
        String messageId = msg.path("message_id").asText();

        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("chatId", chatId);
        reply.put("messageId", messageId);

        String[] cmd = splitCommand(text);
        return new Message(messageId, text, cmd[0], cmd[1], chatId, null,
                from.path("id").asText(null), name,
                from.hasNonNull("username") ? "@" + from.path("username").asText() : null,
                msg.has("date") ? Instant.ofEpochSecond(msg.path("date").asLong()) : Instant.now(),
                files, reply);
    }

    // ── WhatsApp Cloud API ───────────────────────────────────────────────────

    public static List<Message> whatsapp(JsonNode root) {
        List<Message> out = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                Map<String, String> names = new LinkedHashMap<>();
                for (JsonNode c : value.path("contacts")) names.put(c.path("wa_id").asText(), c.path("profile").path("name").asText(null));
                for (JsonNode m : value.path("messages")) {
                    Message parsed = whatsappMessage(m, names);
                    if (parsed != null) out.add(parsed);
                }
            }
        }
        return out;
    }

    private static Message whatsappMessage(JsonNode m, Map<String, String> names) {
        String type = m.path("type").asText();
        String text = switch (type) {
            case "text" -> m.path("text").path("body").asText("");
            case "button" -> m.path("button").path("text").asText("");
            case "interactive" -> m.path("interactive").path("button_reply").path("title")
                    .asText(m.path("interactive").path("list_reply").path("title").asText(""));
            default -> m.path(type).path("caption").asText("");
        };
        List<Attachment> files = new ArrayList<>();
        if (List.of("image", "document", "audio", "video", "sticker").contains(type)) {
            JsonNode media = m.path(type);
            String mime = media.path("mime_type").asText(null);
            String shortId = m.path("id").asText().replaceAll("[^A-Za-z0-9]", "");
            if (shortId.length() > 12) shortId = shortId.substring(shortId.length() - 12);
            String name = media.path("filename").asText(type + "-" + shortId + extensionFor(mime));
            files.add(new Attachment(name, mime, media.path("id").asText(), null));
        }
        if (text.isEmpty() && files.isEmpty()) return null; // reactions, unsupported types

        String from = m.path("from").asText();
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("to", from);
        reply.put("messageId", m.path("id").asText());
        String[] cmd = splitCommand(text);
        return new Message(m.path("id").asText(), text, cmd[0], cmd[1], from, null,
                from, names.get(from), "+" + from,
                m.has("timestamp") ? Instant.ofEpochSecond(m.path("timestamp").asLong()) : Instant.now(),
                files, reply);
    }

    // ── Discord interactions ─────────────────────────────────────────────────

    /** Slash command (interaction type 2). */
    public static Message discordCommand(JsonNode interaction) {
        JsonNode data = interaction.path("data");
        String command = "/" + data.path("name").asText();
        List<String> values = new ArrayList<>();
        Map<String, Object> reply = new LinkedHashMap<>();
        collectOptions(data.path("options"), values);

        List<Attachment> files = new ArrayList<>();
        data.path("resolved").path("attachments").forEach(a -> files.add(new Attachment(
                a.path("filename").asText("file"), a.path("content_type").asText(null),
                a.path("url").asText(), longOrNull(a.path("size")))));

        JsonNode user = interaction.has("member") ? interaction.path("member").path("user") : interaction.path("user");
        String args = String.join(" ", values);
        String channel = interaction.path("channel_id").asText(interaction.path("channel").path("id").asText(null));
        reply.put("applicationId", interaction.path("application_id").asText());
        reply.put("interactionToken", interaction.path("token").asText());
        reply.put("channelId", channel);
        String name = user.path("global_name").asText(user.path("username").asText(null));
        return new Message(interaction.path("id").asText(), (command + " " + args).trim(), command, args, channel, null,
                user.path("id").asText(null), name,
                user.hasNonNull("username") ? "@" + user.path("username").asText() : null,
                Instant.now(), files, reply);
    }

    private static void collectOptions(JsonNode options, List<String> values) {
        for (JsonNode o : options) {
            if (o.has("options")) collectOptions(o.path("options"), values); // sub-commands
            else if (o.has("value") && o.path("type").asInt() != 11) values.add(o.path("value").asText()); // 11 = attachment
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** "/report march" → ["/report", "march"]; "/start@MyBot" → "/start". Plain text → [null, null]. */
    static String[] splitCommand(String text) {
        if (text == null || !text.startsWith("/") || text.length() < 2 || !Character.isLetterOrDigit(text.charAt(1))) {
            return new String[] { null, null };
        }
        int space = text.indexOf(' ');
        String command = space > 0 ? text.substring(0, space) : text;
        int at = command.indexOf('@');
        if (at > 0) command = command.substring(0, at);
        return new String[] { command, space > 0 ? text.substring(space + 1).trim() : "" };
    }

    private static Long longOrNull(JsonNode n) {
        return n.isNumber() ? n.asLong() : null;
    }

    private static Instant epochSeconds(String slackTs) {
        try {
            return Instant.ofEpochMilli((long) (Double.parseDouble(slackTs) * 1000));
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static String extensionFor(String mime) {
        if (mime == null) return "";
        String m = mime.split(";")[0].trim();
        return switch (m) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            case "audio/ogg" -> ".ogg";
            case "audio/mpeg" -> ".mp3";
            case "video/mp4" -> ".mp4";
            case "text/csv" -> ".csv";
            case "text/plain" -> ".txt";
            default -> "";
        };
    }
}
