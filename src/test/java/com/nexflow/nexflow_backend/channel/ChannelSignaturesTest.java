package com.nexflow.nexflow_backend.channel;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelSignaturesTest {

    @Test
    void slackSignatureIsCheckedAndOldRequestsRejected() {
        String body = "{\"type\":\"event_callback\"}";
        String sig = "v0=" + ChannelSignatures.hmacHex("secret", "v0:1700000000:" + body);
        assertThat(ChannelSignatures.slack("secret", "1700000000", body, sig, 1700000010)).isTrue();
        assertThat(ChannelSignatures.slack("wrong", "1700000000", body, sig, 1700000010)).isFalse();
        assertThat(ChannelSignatures.slack("secret", "1700000000", body + " ", sig, 1700000010)).isFalse();
        assertThat(ChannelSignatures.slack("secret", "1700000000", body, sig, 1700000000 + 600)).isFalse(); // replay
    }

    @Test
    void whatsappSignature() {
        String body = "{\"entry\":[]}";
        String header = "sha256=" + ChannelSignatures.hmacHex("app-secret", body);
        assertThat(ChannelSignatures.whatsapp("app-secret", body, header)).isTrue();
        assertThat(ChannelSignatures.whatsapp("app-secret", body, "sha256=00")).isFalse();
        assertThat(ChannelSignatures.whatsapp(null, body, header)).isFalse();
    }

    @Test
    void discordEd25519Signature() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = pair.getPublic().getEncoded();
        String publicHex = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(("123" + "{\"type\":1}").getBytes(StandardCharsets.UTF_8));
        String sig = HexFormat.of().formatHex(signer.sign());

        assertThat(ChannelSignatures.isDiscordPublicKey(publicHex)).isTrue();
        assertThat(ChannelSignatures.discord(publicHex, "123", "{\"type\":1}", sig)).isTrue();
        assertThat(ChannelSignatures.discord(publicHex, "124", "{\"type\":1}", sig)).isFalse();
        assertThat(ChannelSignatures.discord(publicHex, "123", "{\"type\":1}", "zz")).isFalse();
    }

    @Test
    void sharedSecretComparison() {
        assertThat(ChannelSignatures.telegram("abc", "abc")).isTrue();
        assertThat(ChannelSignatures.telegram("abc", "abd")).isFalse();
        assertThat(ChannelSignatures.telegram(null, "abc")).isFalse();
    }
}
