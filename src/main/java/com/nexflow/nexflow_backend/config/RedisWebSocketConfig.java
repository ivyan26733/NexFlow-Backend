package com.nexflow.nexflow_backend.config;

import com.nexflow.nexflow_backend.engine.RedisWebSocketBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisWebSocketConfig {

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
