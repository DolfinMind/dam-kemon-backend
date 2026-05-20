package com.damKemon.dam.kemon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.zip.GZIPOutputStream;

/**
 * Nightly MongoDB backup to S3. Iterates every collection, serializes each
 * document as a JSON line, gzips the result, and uploads under
 * {@code s3://$BACKUP_S3_BUCKET/$prefix/yyyy-MM-dd/<collection>.jsonl.gz}.
 *
 * <p>No-op when {@code BACKUP_S3_BUCKET} is unset — operator sees a single
 * INFO log line on boot and the scheduler keeps quiet thereafter. With S3
 * configured, the AWS SDK picks up credentials from the standard chain
 * (env vars, IAM role, ~/.aws/credentials).
 */
@Service
public class MongoBackupService {

    private static final Logger log = LoggerFactory.getLogger(MongoBackupService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final MongoTemplate mongo;

    @Value("${backup.s3-bucket:}")
    private String bucket;

    @Value("${backup.s3-prefix:damkemon-backup}")
    private String prefix;

    @Value("${backup.s3-region:us-east-1}")
    private String region;

    public MongoBackupService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public boolean isConfigured() { return bucket != null && !bucket.isBlank(); }

    /**
     * Run nightly at 02:00, well before the indexer fires at 03:00.
     */
    @Scheduled(cron = "${backup.cron:0 0 2 * * *}")
    public void backup() {
        if (!isConfigured()) {
            log.debug("MongoBackup: BACKUP_S3_BUCKET unset — skipping");
            return;
        }
        S3Client s3;
        try {
            s3 = S3Client.builder()
                    .region(Region.of(region))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
        } catch (Exception e) {
            log.warn("MongoBackup: AWS credentials not available ({}). "
                    + "Skipping run.", e.getMessage());
            return;
        }

        String day = LocalDate.now().toString();
        int collections = 0;
        long totalDocs = 0;
        long totalBytes = 0;

        try {
            for (String collectionName : mongo.getCollectionNames()) {
                try {
                    BackupResult r = dumpCollection(collectionName);
                    if (r.bytes.length == 0) continue;
                    String key = prefix.replaceAll("/$", "") + "/" + day + "/" + collectionName + ".jsonl.gz";
                    s3.putObject(
                            PutObjectRequest.builder()
                                    .bucket(bucket).key(key)
                                    .contentType("application/x-ndjson")
                                    .contentEncoding("gzip")
                                    .build(),
                            RequestBody.fromBytes(r.bytes));
                    collections++;
                    totalDocs += r.docs;
                    totalBytes += r.bytes.length;
                    log.info("MongoBackup: uploaded s3://{}/{} ({} docs, {} bytes)",
                            bucket, key, r.docs, r.bytes.length);
                } catch (Exception e) {
                    log.warn("MongoBackup: collection '{}' failed: {}", collectionName, e.getMessage());
                }
            }
            log.info("MongoBackup: done. {} collections, {} docs, {} bytes",
                    collections, totalDocs, totalBytes);
        } catch (Exception e) {
            log.warn("MongoBackup: top-level failure: {}", e.getMessage());
        } finally {
            try { s3.close(); } catch (Exception ignored) {}
        }
    }

    private BackupResult dumpCollection(String name) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long docs = 0;
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            for (Document d : mongo.getCollection(name).find()) {
                byte[] line = MAPPER.writeValueAsString(d).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                gz.write(line);
                gz.write('\n');
                docs++;
            }
        }
        return new BackupResult(out.toByteArray(), docs);
    }

    private record BackupResult(byte[] bytes, long docs) {}
}
