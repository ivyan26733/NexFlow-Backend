package com.nexflow.nexflow_backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.engine.RedisWebSocketBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Configuration
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisWebSocketConfig {

    @Bean
    public RedisWebSocketBridge redisWebSocketBridge(
            StringRedisTemplate redisTemplate,
            SimpMessagingTemplate messagingTemplate,
            ObjectMapper objectMapper) {
        return new RedisWebSocketBridge(redisTemplate, messagingTemplate, objectMapper);
    }

    @Bean
    public RedisMessageListenerContainer redisWebSocketListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisWebSocketBridge bridge) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bridge, new ChannelTopic(RedisWebSocketBridge.REDIS_CHANNEL));
        return container;
    }
}
