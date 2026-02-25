package com.nexflow.nexflow_backend.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges WebSocket messages via Redis Pub/Sub so all instances receive them.
 * When execution runs on instance A but the client is connected to instance B,
 * publishing to Redis ensures instance B receives and delivers to the client.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisWebSocketBridge implements MessageListener {

    public static final String REDIS_CHANNEL = "nexflow:websocket:topic";

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public void publish(String destination, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(new StompMessage(destination, payload));
            log.info("[WS-DEBUG] RedisWebSocketBridge.publish: destination={}, nodeId={} -> Redis channel {}",
                    destination, payload.get("nodeId"), REDIS_CHANNEL);
            redisTemplate.convertAndSend(REDIS_CHANNEL, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize WebSocket message for {}", destination, e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            StompMessage stomp = objectMapper.readValue(body, StompMessage.class);
            log.info("[WS-DEBUG] RedisWebSocketBridge.onMessage: received from Redis, forwarding to destination={}, nodeId={}",
                    stomp.destination, stomp.payload.get("nodeId"));
            messagingTemplate.convertAndSend(stomp.destination, stomp.payload);
        } catch (Exception e) {
            log.error("Failed to forward Redis message to WebSocket", e);
        }
    }

    private record StompMessage(String destination, Map<String, Object> payload) {}
}
