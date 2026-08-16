package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.RequestLog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers request-log rows in memory and flushes them to Mongo in batches on a
 * short timer. This keeps "log every request" off the hot path entirely — the
 * filter does an O(1) enqueue and returns; one scheduled thread writes thousands
 * of rows in a single batch insert.
 *
 * <p>The buffer is bounded — under a traffic flood we drop rather than grow
 * without limit (request logging must never OOM the box). Dropped rows are
 * counted and surfaced in a WARN on the next flush.
 */
@Service
public class RequestLogService {

    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);
    private static final int MAX_BUFFER = 20_000;
    private static final int FLUSH_BATCH = 2_000;

    private final MongoTemplate mongo;
    private final Queue<RequestLog> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger buffered = new AtomicInteger(0);
    private final AtomicLong dropped = new AtomicLong(0);

    public RequestLogService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** O(1), lock-free, never throws. Drops the row on buffer overflow. */
    public void enqueue(RequestLog entry) {
        if (entry == null) return;
        if (buffered.get() >= MAX_BUFFER) {
            dropped.incrementAndGet();
            return;
        }
        buffer.add(entry);
        buffered.incrementAndGet();
    }

    @Scheduled(fixedDelay = 2000)
    public void flush() {
        if (buffer.isEmpty()) return;
        List<RequestLog> batch = new ArrayList<>(Math.min(FLUSH_BATCH, Math.max(1, buffered.get())));
        RequestLog e;
        while (batch.size() < FLUSH_BATCH && (e = buffer.poll()) != null) {
            buffered.decrementAndGet();
            batch.add(e);
        }
        if (batch.isEmpty()) return;
        try {
            mongo.insert(batch, RequestLog.class);
        } catch (Exception ex) {
            // Best-effort: a transient Atlas blip just loses this batch.
            log.debug("request-log flush dropped {} rows: {}", batch.size(), ex.getMessage());
        }
        long d = dropped.getAndSet(0);
        if (d > 0) log.warn("request-log buffer overflow: dropped {} rows since last flush", d);
    }

    @PreDestroy
    public void drainOnShutdown() {
        while (!buffer.isEmpty()) flush();
    }
}
