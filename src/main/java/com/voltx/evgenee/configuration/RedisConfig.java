package com.voltx.evgenee.configuration;

import com.voltx.evgenee.dto.responses.UserResponseDto;
import com.voltx.evgenee.dto.responses.StationResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    public static final String STATIONS_ALL = "stations:all";
    public static final String STATIONS_BY_ID = "stations:by-id";
    public static final String STATIONS_BY_OWNER = "stations:by-owner";
    public static final String STATIONS_NEARBY = "stations:nearby";
    public static final String USERS_PROFILE = "users:profile";
    public static final String ROADSIDE_STATIC = "roadside:static";
    public static final String GEOCODING = "geo:geocode";
    public static final String REVERSE_GEOCODING = "geo:reverse";
    public static final String ROAD_DISTANCE = "geo:road-distance";

    @Value("${app.redis.cache-prefix:evgenee:cache:}")
    private String cachePrefix;

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder().build();
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .prefixCacheNameWith(normalizedCachePrefix())
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        RedisCacheConfiguration userProfileCache = defaults
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(UserResponseDto.class)
                ));
        RedisCacheConfiguration stationByIdCache = defaults
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(StationResponseDto.class)
                ));

        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                STATIONS_ALL, defaults.entryTtl(Duration.ofMinutes(5)),
                STATIONS_BY_ID, stationByIdCache,
                STATIONS_BY_OWNER, defaults.entryTtl(Duration.ofMinutes(5)),
                STATIONS_NEARBY, defaults.entryTtl(Duration.ofMinutes(2)),
                USERS_PROFILE, userProfileCache,
                ROADSIDE_STATIC, defaults.entryTtl(Duration.ofHours(12)),
                GEOCODING, defaults.entryTtl(Duration.ofDays(7)),
                REVERSE_GEOCODING, defaults.entryTtl(Duration.ofDays(7)),
                ROAD_DISTANCE, defaults.entryTtl(Duration.ofHours(6))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()
                .build();
    }

    private String normalizedCachePrefix() {
        String prefix = cachePrefix == null || cachePrefix.isBlank() ? "evgenee:cache:" : cachePrefix.trim();
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Redis cache get failed for cache={} key={}: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Redis cache put failed for cache={} key={}: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Redis cache evict failed for cache={} key={}: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Redis cache clear failed for cache={}: {}", cache.getName(), exception.getMessage());
            }
        };
    }
}
