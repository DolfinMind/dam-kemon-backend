package com.damKemon.dam.kemon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Best-effort indexes for high-volume operational collections.
 *
 * <p>The custom {@link MongoConfig} bypasses Boot's auto-index flag. This list
 * stays explicit because legacy production rows cannot satisfy every annotated
 * unique index.
 */
@Component
public class OperationalIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(OperationalIndexInitializer.class);
    private final MongoTemplate mongo;

    public OperationalIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void create() {
        ensure("events", new Index().on("ts", Sort.Direction.DESC)
                .expire(Duration.ofDays(30)).named("events_ts_ttl"));
        ensure("events", new Index().on("type", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("events_type_ts"));
        ensure("events", new Index().on("userId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("events_user_ts"));
        ensure("events", new Index().on("anonId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("events_anon_ts"));

        ensure("request_log", new Index().on("ts", Sort.Direction.DESC)
                .expire(Duration.ofDays(30)).named("request_log_ts_ttl"));
        ensure("request_log", new Index().on("userId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("request_log_user_ts"));

        ensure("affiliate_clicks", new Index().on("ts", Sort.Direction.DESC)
                .named("affiliate_clicks_ts"));
        ensure("affiliate_clicks", new Index().on("productId", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("affiliate_clicks_product_ts"));
        ensure("affiliate_clicks", new Index().on("siteSlug", Sort.Direction.ASC)
                .on("ts", Sort.Direction.DESC).named("affiliate_clicks_shop_ts"));

        ensure("reviews", new Index().on("source", Sort.Direction.ASC).named("reviews_source"));
        ensure("reviews", new Index().on("status", Sort.Direction.ASC).named("reviews_status"));
        ensure("shops", new Index().on("status", Sort.Direction.ASC).named("shops_status"));
        ensure("sellers", new Index().on("slug", Sort.Direction.ASC).named("sellers_slug"));
    }

    private void ensure(String collection, Index index) {
        try {
            mongo.indexOps(collection).createIndex(index);
        } catch (Exception e) {
            log.warn("Could not ensure index on '{}': {}", collection, e.getMessage());
        }
    }
}
