package com.voltx.evgenee.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@Order(20)
@RequiredArgsConstructor
@Slf4j
public class RedisCacheCleanupRunner implements CommandLineRunner {

    private static final int BATCH_SIZE = 500;

    private final StringRedisTemplate redisTemplate;

    @Value("${app.redis.cleanup-legacy-prefixes:true}")
    private boolean cleanupLegacyPrefixes;

    @Value("${app.redis.legacy-prefixes:evgenee:v1:,evgenee:v2:,evgenee:v3:,evgenee:v4:,evgenee:v5:}")
    private String legacyPrefixes;

    @Override
    public void run(String... args) {
        if (!cleanupLegacyPrefixes) {
            return;
        }
        Arrays.stream(legacyPrefixes.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isBlank())
                .forEach(this::deletePrefix);
    }

    private void deletePrefix(String prefix) {
        long deleted = 0;
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(BATCH_SIZE)
                .build())) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= BATCH_SIZE) {
                    deleted += deleteBatch(batch);
                }
            }
            deleted += deleteBatch(batch);
            if (deleted > 0) {
                log.info("Deleted {} stale Redis cache keys for prefix {}", deleted, prefix);
            }
        } catch (Exception e) {
            log.warn("Unable to clean stale Redis cache prefix {}: {}", prefix, e.getMessage());
        }
    }

    private long deleteBatch(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        Long deleted = redisTemplate.delete(keys);
        keys.clear();
        return deleted == null ? 0 : deleted;
    }
}
