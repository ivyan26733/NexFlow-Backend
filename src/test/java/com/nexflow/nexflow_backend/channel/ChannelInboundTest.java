package com.nexflow.nexflow_backend.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelInboundTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void telegramTextCommandAndDocument() throws Exception {
        var m = ChannelInbound.telegram(json.readTree("""
                {"update_id":1,"message":{"message_id":55,"date":1700000000,
                 "from":{"id":9,"is_bot":false,"first_name":"Ana","last_name":"Lee","username":"ana"},
                 "chat":{"id":-100123,"type":"group"},"caption":"/report@MyBot march",
                 "document":{"file_id":"F1","file_name":"sales.csv","mime_type":"text/csv","file_size":120}}}"""));
        assertThat(m.text()).isEqualTo("/report@MyBot march");
        assertThat(m.command()).isEqualTo("/report");
        assertThat(m.args()).isEqualTo("march");
        assertThat(m.chatId()).isEqualTo("-100123");
        assertThat(m.fromName()).isEqualTo("Ana Lee");
        assertThat(m.fromHandle()).isEqualTo("@ana");
        assertThat(m.attachments()).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("sales.csv");
            assertThat(a.ref()).isEqualTo("F1");
        });
        assertThat(m.replyContext()).containsEntry("messageId", "55");
    }

    @Test
    void telegramIgnoresBotsAndNonMessages() throws Exception {
        assertThat(ChannelInbound.telegram(json.readTree("{\"edited_message\":{}}"))).isNull();
        assertThat(ChannelInbound.telegram(json.readTree("{\"message\":{\"from\":{\"is_bot\":true},\"chat\":{\"id\":1},\"text\":\"x\"}}"))).isNull();
    }

    @Test
    void slackEventSkipsBotsAndStripsMentions() throws Exception {
        var mention = ChannelInbound.slackEvent(json.readTree("""
                {"type":"event_callback","event":{"type":"app_mention","user":"U1","text":"<@U999> summarise this",
                 "ts":"1700000000.000100","channel":"C1"}}"""));
        assertThat(mention.text()).isEqualTo("summarise this");
        assertThat(mention.replyContext()).containsEntry("channel", "C1").containsEntry("threadTs", "1700000000.000100");

        assertThat(ChannelInbound.slackEvent(json.readTree("""
                {"event":{"type":"message","bot_id":"B1","text":"our own reply","ts":"1","channel":"C1"}}"""))).isNull();
        assertThat(ChannelInbound.slackEvent(json.readTree("""
                {"event":{"type":"message","subtype":"message_changed","ts":"1","channel":"C1"}}"""))).isNull();
    }

    @Test
    void slackSlashCommandForm() {
        var m = ChannelInbound.slackCommand(ChannelInbound.parseForm(
                "command=%2Freport&text=last+week&user_id=U1&user_name=ana&channel_id=C9&response_url=https%3A%2F%2Fhooks.slack.com%2Fx"));
        assertThat(m.command()).isEqualTo("/report");
        assertThat(m.args()).isEqualTo("last week");
        assertThat(m.chatId()).isEqualTo("C9");
        assertThat(m.replyContext()).containsEntry("responseUrl", "https://hooks.slack.com/x");
    }

    @Test
    void whatsappTextAndDocumentMessages() throws Exception {
        List<ChannelInbound.Message> list = ChannelInbound.whatsapp(json.readTree("""
                {"entry":[{"changes":[{"value":{
                  "contacts":[{"wa_id":"919876543210","profile":{"name":"Ravi"}}],
                  "messages":[
                    {"id":"wamid.A","from":"919876543210","timestamp":"1700000000","type":"text","text":{"body":"hello"}},
                    {"id":"wamid.B","from":"919876543210","type":"document","document":{"id":"M1","filename":"inv.pdf","mime_type":"application/pdf","caption":"invoice"}},
                    {"id":"wamid.C","from":"919876543210","type":"reaction","reaction":{"emoji":"👍"}}
                  ]}}]}]}"""));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).text()).isEqualTo("hello");
        assertThat(list.get(0).fromName()).isEqualTo("Ravi");
        assertThat(list.get(0).replyContext()).containsEntry("to", "919876543210");
        assertThat(list.get(1).text()).isEqualTo("invoice");
        assertThat(list.get(1).attachments().get(0).name()).isEqualTo("inv.pdf");
    }

    @Test
    void discordSlashCommandWithOptions() throws Exception {
        var m = ChannelInbound.discordCommand(json.readTree("""
                {"id":"I1","type":2,"token":"tok","application_id":"A1","channel_id":"CH1",
                 "member":{"user":{"id":"U1","username":"ana","global_name":"Ana"}},
                 "data":{"name":"ask","options":[{"name":"question","type":3,"value":"why did it fail?"}]}}"""));
        assertThat(m.command()).isEqualTo("/ask");
        assertThat(m.args()).isEqualTo("why did it fail?");
        assertThat(m.fromName()).isEqualTo("Ana");
        assertThat(m.replyContext()).containsEntry("interactionToken", "tok").containsEntry("applicationId", "A1");
    }

    @Test
    void plainTextIsNotACommand() {
        assertThat(ChannelInbound.splitCommand("hello")[0]).isNull();
        assertThat(ChannelInbound.splitCommand("/ path")[0]).isNull();
    }
}
