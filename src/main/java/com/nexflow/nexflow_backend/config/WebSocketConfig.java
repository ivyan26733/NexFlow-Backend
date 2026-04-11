package com.nexflow.nexflow_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private String allowedOrigins;

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
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
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
                    // Heartbeats disabled: Reactor Netty TCP does not reliably send STOMP
                    // heartbeat frames, causing RabbitMQ to close the connection every ~60s
                    // and drop all in-flight execution events. On a co-located Docker network
                    // (same EC2 host) the TCP connection itself is sufficient liveness signal.
                    .setSystemHeartbeatSendInterval(0)
                    .setSystemHeartbeatReceiveInterval(0);
        } else {
            // For the in-memory simple broker we don't need explicit heartbeats.
            // Avoid configuring them to prevent requiring a TaskScheduler in tests.
            registry.enableSimpleBroker("/topic", "/queue");
        }
    }
}

