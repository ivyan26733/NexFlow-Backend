package com.nexflow.nexflow_backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Logs at startup whether Redis is available for the WebSocket bridge.
 * If REDIS_URL is set but RedisConnectionFactory is missing, connection likely failed.
 */
@Slf4j
@Component
public class RedisStartupLogger implements ApplicationRunner {

    private final Environment env;
    private final org.springframework.context.ApplicationContext context;

    public RedisStartupLogger(Environment env, org.springframework.context.ApplicationContext context) {
        this.env = env;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String redisUrl = env.getProperty("REDIS_URL", env.getProperty("REDIS_PRIVATE_URL", ""));
        String springRedisUrl = env.getProperty("spring.data.redis.url", "");
        boolean hasRedisBean;
        try {
            hasRedisBean = context.getBean(RedisConnectionFactory.class) != null;
        } catch (Exception e) {
            hasRedisBean = false;
        }
        if (hasRedisBean) {
            log.info("[WS-DEBUG] Redis: connection OK — WebSocket will use Redis (via=Redis)");
        } else {
            String urlSource = (redisUrl.isEmpty() && springRedisUrl.isEmpty())
                    ? "REDIS_URL and REDIS_PRIVATE_URL not set or empty"
                    : "Redis connection failed or RedisAutoConfiguration excluded";
            log.warn("[WS-DEBUG] Redis: WebSocket will use Direct only (single instance). Reason: {}", urlSource);
        }
    }
}
