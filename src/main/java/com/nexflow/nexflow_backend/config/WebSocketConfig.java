package com.nexflow.nexflow_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${rabbitmq.host:}")
    private String rabbitHost;

    @Value("${rabbitmq.port:61613}")
    private int rabbitPort;

    @Value("${rabbitmq.username:guest}")
    private String rabbitUsername;

    @Value("${rabbitmq.password:guest}")
    private String rabbitPassword;

    @Value("${rabbitmq.virtual-host:/}")
    private String rabbitVirtualHost;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");

        boolean useRabbit = rabbitHost != null && !rabbitHost.isBlank();

        if (useRabbit) {
            registry.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(rabbitHost)
                    .setRelayPort(rabbitPort)
                    .setClientLogin(rabbitUsername)
                    .setClientPasscode(rabbitPassword)
                    .setSystemLogin(rabbitUsername)
                    .setSystemPasscode(rabbitPassword)
                    .setVirtualHost(rabbitVirtualHost)
                    .setSystemHeartbeatSendInterval(10_000)
                    .setSystemHeartbeatReceiveInterval(10_000);
        } else {
            registry.enableSimpleBroker("/topic", "/queue")
                    .setHeartbeatValue(new long[]{10_000, 10_000});
        }
    }
}

