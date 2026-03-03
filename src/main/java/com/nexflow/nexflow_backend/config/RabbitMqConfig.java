package com.nexflow.nexflow_backend.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    /**
     * Use Jackson for any Spring AMQP message conversion.
     * The StompBrokerRelay does not depend on this, but it keeps
     * RabbitMQ-related configuration in one place and is useful
     * for future AMQP integrations.
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}

