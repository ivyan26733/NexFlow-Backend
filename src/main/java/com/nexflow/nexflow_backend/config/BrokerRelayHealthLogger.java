package com.nexflow.nexflow_backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class BrokerRelayHealthLogger implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(BrokerRelayHealthLogger.class);

    @Value("${rabbitmq.host:}")
    private String rabbitHost;

    @Value("${rabbitmq.port:61613}")
    private int rabbitPort;

    @Override
    public Health health() {
        boolean useRabbit = rabbitHost != null && !rabbitHost.isBlank();
        if (useRabbit) {
            return Health.up()
                    .withDetail("broker",    "RabbitMQ StompBrokerRelay")
                    .withDetail("host",      rabbitHost)
                    .withDetail("stompPort", rabbitPort)
                    .withDetail("mode",      "multi-instance safe")
                    .build();
        } else {
            return Health.up()
                    .withDetail("broker",  "in-memory SimpleBroker")
                    .withDetail("warning", "Set RABBITMQ_HOST for multi-instance prod")
                    .build();
        }
    }

    @PostConstruct
    public void logBrokerMode() {
        boolean useRabbit = rabbitHost != null && !rabbitHost.isBlank();
        if (useRabbit) {
            log.info("WebSocket broker: RabbitMQ StompBrokerRelay — host={}:{} — multi-instance SAFE",
                    rabbitHost, rabbitPort);
        } else {
            log.warn("WebSocket broker: in-memory SimpleBroker — single-instance ONLY. Set RABBITMQ_HOST to fix prod.");
        }
    }
}

