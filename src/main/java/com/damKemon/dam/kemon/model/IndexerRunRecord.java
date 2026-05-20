package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One persisted row per indexer run. Powers the "last 30 nights" timeline
 * on the admin console. The in-memory {@code RunSummary} on
 * {@link com.damKemon.dam.kemon.indexer.BulkIndexer} is still the source of
 * truth for the live in-flight run; this collection is the historical record.
 *
 * <p>TTL: 90 days. Anything longer should be rolled into a monthly summary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "indexer_runs")
public class IndexerRunRecord {

    @Id
    private String id;

    /** "full" | "retry" | "single" — what kind of run this was. */
    private String kind;

    private Integer shopsAttempted;
    private Integer shopsSucceeded;
    private Integer shopsFailed;
    private Integer urlsScraped;
    private Integer productsInserted;
    private Integer productsMerged;

    @Indexed
    private Instant startedAt;

    private Instant finishedAt;

    /** Seconds. -1 if interrupted. */
    private Long tookSeconds;

    @Indexed(expireAfterSeconds = 60 * 60 * 24 * 90)
    private Instant expireAt;
}
