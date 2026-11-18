package com.nexflow.nexflow_backend.channel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * Verifies that a webhook call really comes from the chat platform. Each platform signs differently:
 *  - Slack:    X-Slack-Signature = "v0=" + HMAC-SHA256(signingSecret, "v0:" + timestamp + ":" + body)
 *  - WhatsApp: X-Hub-Signature-256 = "sha256=" + HMAC-SHA256(appSecret, body)
 *  - Discord:  Ed25519 signature (X-Signature-Ed25519) of timestamp + body, with the app's public key
 *  - Telegram: X-Telegram-Bot-Api-Secret-Token equals the secret we registered with setWebhook
 */
public final class ChannelSignatures {

    /** Slack rejects requests older than this to stop replays. */
    static final long SLACK_MAX_AGE_SECONDS = 5 * 60;

    /** DER prefix that turns a raw 32-byte Ed25519 key into an X.509 SubjectPublicKeyInfo. */
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private ChannelSignatures() {}

    public static boolean slack(String signingSecret, String timestamp, String body, String signature, long nowEpochSeconds) {
        if (signingSecret == null || timestamp == null || signature == null) return false;
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - ts) > SLACK_MAX_AGE_SECONDS) return false;
        String expected = "v0=" + hmacHex(signingSecret, "v0:" + timestamp.trim() + ":" + body);
        return constantTimeEquals(expected, signature.trim());
    }

    public static boolean whatsapp(String appSecret, String body, String signatureHeader) {
        if (appSecret == null || signatureHeader == null) return false;
        String expected = "sha256=" + hmacHex(appSecret, body);
        return constantTimeEquals(expected, signatureHeader.trim().toLowerCase());
    }

    public static boolean telegram(String expectedSecret, String header) {
        return secretMatches(expectedSecret, header);
    }

    /** Constant-time comparison of a shared secret (Telegram secret header, WhatsApp verify token). */
    public static boolean secretMatches(String expected, String given) {
        return expected != null && given != null && constantTimeEquals(expected, given.trim());
    }

    public static boolean discord(String publicKeyHex, String timestamp, String body, String signatureHex) {
        if (publicKeyHex == null || timestamp == null || signatureHex == null) return false;
        try {
            byte[] raw = HexFormat.of().parseHex(publicKeyHex.trim());
            if (raw.length != 32) return false;
            byte[] der = new byte[ED25519_X509_PREFIX.length + raw.length];
            System.arraycopy(ED25519_X509_PREFIX, 0, der, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(raw, 0, der, ED25519_X509_PREFIX.length, raw.length);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((timestamp + body).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(HexFormat.of().parseHex(signatureHex.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    /** True when {@code hex} looks like a Discord application public key. */
    public static boolean isDiscordPublicKey(String hex) {
        return hex != null && hex.trim().matches("[0-9a-fA-F]{64}");
    }

    static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
