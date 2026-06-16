package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional Mongo Atlas Search backend. Activated by
 * {@code SEARCH_ATLAS_ENABLED=true}; requires an Atlas Search index named
 * {@code default} on the {@code products} collection with mappings on
 * {@code name} and {@code description}.
 *
 * <p>The query uses fuzzy matching + autocomplete + compound scoring. If
 * the aggregation throws (no index, free tier, network), we return null
 * so the caller can transparently fall back to the {@code $text} path.
 */
@Service
public class AtlasSearchService {

    private static final Logger log = LoggerFactory.getLogger(AtlasSearchService.class);

    private final MongoTemplate mongo;

    @Value("${search.atlas-enabled:true}")
    private boolean atlasEnabled;

    @Value("${search.atlas-index:default}")
    private String atlasIndexName;

    public AtlasSearchService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public boolean isEnabled() { return atlasEnabled; }

    /** Atlas Search query, or null if disabled / failed (caller falls back). */
    public List<Product> search(String query, int limit) {
        if (!atlasEnabled || query == null || query.isBlank()) return null;

        try {
            // Move scoring logic into the query: Exact phrase matches get massive boost,
            // exact token matches get medium boost, and fuzzy/autocomplete catch typos.
            Document exactPhrase = new Document("phrase", new Document()
                    .append("query", query)
                    .append("path", "name")
                    .append("score", new Document("boost", new Document("value", 10))));

            Document exactTokens = new Document("text", new Document()
                    .append("query", query)
                    .append("path", "name")
                    .append("score", new Document("boost", new Document("value", 5))));

            Document textClause = new Document("text", new Document()
                    .append("query", query)
                    .append("path", List.of("name", "description"))
                    .append("fuzzy", new Document("maxEdits", 1))
                    .append("score", new Document("boost", new Document("value", 1))));

            Document autocompleteClause = new Document("autocomplete", new Document()
                    .append("query", query)
                    .append("path", "name")
                    .append("score", new Document("boost", new Document("value", 2))));

            Document compound = new Document("compound",
                    new Document("should", List.of(exactPhrase, exactTokens, textClause, autocompleteClause))
                    .append("minimumShouldMatch", 1));

            Document searchStage = new Document("$search", new Document()
                    .append("index", atlasIndexName)
                    .append("compound", compound.get("compound")));

            List<Document> pipeline = new ArrayList<>();
            pipeline.add(searchStage);
            pipeline.add(new Document("$limit", limit));

            List<Product> out = mongo.getCollection("products")
                    .aggregate(pipeline)
                    .map(doc -> mongo.getConverter().read(Product.class, doc))
                    .into(new ArrayList<>());
            return out;
        } catch (Exception e) {
            log.debug("Atlas $search failed, falling back to $text: {}", e.getMessage());
            return null;
        }
    }
}
