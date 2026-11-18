package com.nexflow.nexflow_backend.model.domain;

/** Chat platforms a ChannelConnection can receive messages from and send messages to. */
public enum ChannelPlatform {
    SLACK,
    TELEGRAM,
    WHATSAPP,   // WhatsApp Business Cloud API (Meta)
    DISCORD     // Discord slash-command interactions + bot messages
}
