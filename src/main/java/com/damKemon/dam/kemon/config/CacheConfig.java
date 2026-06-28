package com.damKemon.dam.kemon.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis-backed cache wiring. The six named caches (search, *-stats, hot-drops)
 * move off-heap to Redis so they survive the restart-based deploys and are shared
 * with the worker unit. Two deliberate choices:
 *
 * <ul>
 *   <li><b>JSON values</b> (not JDK serialization) — so we don't have to make six
 *       DTO trees {@code implements Serializable}. Type info ({@code @class}) is
 *       embedded so RedisCache can rebuild the concrete return type, and
 *       JavaTimeModule handles the {@code LocalDateTime} fields on Product / stats.</li>
 *   <li><b>Best-effort</b> — cache errors are swallowed, so a Redis outage degrades
 *       to "compute every time" instead of 5xx-ing the request. Moving the cache
 *       onto a network dependency must not make the site <i>more</i> fragile.</li>
 * </ul>
 *
 * Active only when {@code CACHE_TYPE=redis} (the default). {@code CACHE_TYPE=caffeine}
 * bypasses Redis for local dev — this config object is then created but unused.
 */
@Configuration
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** 60s TTL matches the old Caffeine {@code expireAfterWrite=60s}: short on
     *  purpose so fresher results show the minute after the indexer re-runs. */
    // ponytail: GenericJackson2JsonRedisSerializer is deprecated-for-removal in
    // Spring Data Redis 4 (migrating to Jackson 3) but ships and works on Boot 4.
    // Upgrade path when it's actually removed: swap to the Jackson 3 generic
    // serializer + tools.jackson ObjectMapper (drops the jsr310 dep — Jackson 3 has
    // java.time built in). Not worth chasing an unreleased API now.
    @Bean
    @SuppressWarnings("removal")
    public RedisCacheConfiguration redisCacheConfiguration() {
        ObjectMapper om = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        // Default typing so polymorphic / Object-valued maps (facets, stats) round-trip.
        // Permissive validator is fine: we only ever read back data we wrote ourselves.
        om.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        GenericJackson2JsonRedisSerializer json = GenericJackson2JsonRedisSerializer.builder().objectMapper(om).build();
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(json));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override public void handleCacheGetError(RuntimeException e, Cache c, Object key) { warn("get", c, e); }
            @Override public void handleCachePutError(RuntimeException e, Cache c, Object key, Object value) { warn("put", c, e); }
            @Override public void handleCacheEvictError(RuntimeException e, Cache c, Object key) { warn("evict", c, e); }
            @Override public void handleCacheClearError(RuntimeException e, Cache c) { warn("clear", c, e); }
            private void warn(String op, Cache c, RuntimeException e) {
                log.warn("Cache {} on '{}' failed ({}) — serving uncached", op,
                        c == null ? "?" : c.getName(), e.getMessage());
            }
        };
    }
}
