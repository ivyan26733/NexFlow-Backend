package com.nexflow.nexflow_backend.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed cache for the default transactions list (2-day window) per user.
 * {@link #bumpGeneration()} invalidates all entries by incrementing a global counter
 * stored alongside each cached payload.
 */
@Service
@RequiredArgsConstructor
public class ExecutionListCacheService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionListCacheService.class);

    static final String GENERATION_KEY = "executions:list:generation";
    private static final Duration ENTRY_TTL = Duration.ofHours(49);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    public void bumpGeneration() {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            log.debug("[ExecutionCache] bump skipped — Redis not available");
            return;
        }
        try {
            Long g = redis.opsForValue().increment(GENERATION_KEY);
            log.info("[ExecutionCache] generation bumped -> {} (transaction list caches invalidated)", g);
        } catch (Exception ex) {
            log.warn("[ExecutionCache] failed to bump generation: {}", ex.getMessage());
        }
    }

    public long readGeneration() {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            log.debug("[ExecutionCache] readGeneration — Redis not available");
            return -1L;
        }
        try {
            String v = redis.opsForValue().get(GENERATION_KEY);
            return v != null ? Long.parseLong(v) : 0L;
        } catch (Exception ex) {
            log.warn("[ExecutionCache] failed to read generation: {}", ex.getMessage());
            return -1L;
        }
    }

    public Optional<String> getPayloadJson(String userId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(cacheKey(userId));
            if (raw == null || raw.isBlank()) {
                log.debug("[ExecutionCache] miss userId={} — empty key {}", userId, cacheKey(userId));
                return Optional.empty();
            }
            log.debug("[ExecutionCache] hit raw userId={} bytes={}", userId, raw.length());
            return Optional.of(raw);
        } catch (Exception ex) {
            log.warn("[ExecutionCache] get failed userId={}: {}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void putPayloadJson(String userId, String json) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            log.info("[ExecutionCache] skip store userId={} — Redis not available (list served from DB only)", userId);
            return;
        }
        try {
            redis.opsForValue().set(cacheKey(userId), json, ENTRY_TTL);
            log.info("[ExecutionCache] stored userId={} ttl={} bytes≈{}", userId, ENTRY_TTL, json.length());
        } catch (Exception ex) {
            log.warn("[ExecutionCache] store failed userId={}: {}", userId, ex.getMessage());
        }
    }

    public boolean redisAvailable() {
        return redisTemplateProvider.getIfAvailable() != null;
    }

    static String cacheKey(String userId) {
        return "executions:list:recent:" + userId;
    }
}
