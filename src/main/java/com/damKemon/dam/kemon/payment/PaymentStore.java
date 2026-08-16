package com.damKemon.dam.kemon.payment;

import com.damKemon.dam.kemon.payment.model.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentStore {
    public record Slice<T>(List<T> items, long total, int offset, int limit) {}
    public record RevenueTotal(String currency, long gross, long refunded, long net, long orders) {}

    private final MongoTemplate mongo;

    public PaymentStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Optional<PaymentApplication> application(String appId) {
        return Optional.ofNullable(mongo.findById(appId, PaymentApplication.class));
    }

    public List<PaymentApplication> applications() {
        return mongo.findAll(PaymentApplication.class);
    }

    public PaymentApplication save(PaymentApplication value) { return mongo.save(value); }

    public Optional<PaymentProduct> product(String appId, String code) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("appId").is(appId).and("code").is(code)), PaymentProduct.class));
    }

    public List<PaymentProduct> products(String appId) {
        return mongo.find(Query.query(Criteria.where("appId").is(appId)), PaymentProduct.class);
    }

    public PaymentProduct save(PaymentProduct value) { return mongo.save(value); }

    public PaymentCheckout insert(PaymentCheckout value) { return mongo.insert(value); }
    public PaymentCheckout save(PaymentCheckout value) { return mongo.save(value); }

    public Optional<PaymentCheckout> checkout(String id) {
        return Optional.ofNullable(mongo.findById(id, PaymentCheckout.class));
    }

    public Optional<PaymentCheckout> checkoutByIdempotency(String appId, String subjectId, String key) {
        Criteria criteria = Criteria.where("appId").is(appId).and("subjectId").is(subjectId).and("idempotencyKey").is(key);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), PaymentCheckout.class));
    }

    public PaymentOrder save(PaymentOrder value) { return mongo.save(value); }

    public Optional<PaymentOrder> order(String provider, String providerOrderId) {
        Criteria criteria = Criteria.where("provider").is(provider).and("providerOrderId").is(providerOrderId);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), PaymentOrder.class));
    }

    public Optional<PaymentOrder> orderByCheckout(String checkoutId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("checkoutId").is(checkoutId)), PaymentOrder.class));
    }

    public PaymentSubscription save(PaymentSubscription value) { return mongo.save(value); }

    public Optional<PaymentSubscription> subscription(String provider, String providerSubscriptionId) {
        Criteria criteria = Criteria.where("provider").is(provider)
                .and("providerSubscriptionId").is(providerSubscriptionId);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), PaymentSubscription.class));
    }

    public PaymentLicense save(PaymentLicense value) { return mongo.save(value); }

    public Optional<PaymentLicense> license(String provider, String providerLicenseId) {
        Criteria criteria = Criteria.where("provider").is(provider).and("providerLicenseId").is(providerLicenseId);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), PaymentLicense.class));
    }

    public Optional<PaymentLicense> licenseByFingerprint(String appId, String fingerprint) {
        Criteria criteria = Criteria.where("appId").is(appId).and("licenseKeyFingerprint").is(fingerprint);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), PaymentLicense.class));
    }

    public PaymentEntitlement save(PaymentEntitlement value) { return mongo.save(value); }

    public Optional<PaymentEntitlement> entitlement(String appId, String subjectId, String instanceId) {
        Criteria criteria = Criteria.where("appId").is(appId).and("subjectId").is(subjectId)
                .and("providerInstanceId").is(instanceId);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), PaymentEntitlement.class));
    }

    public Optional<PaymentEntitlement> entitlementByLicense(String appId, String subjectId, String providerLicenseId) {
        Criteria criteria = Criteria.where("appId").is(appId).and("subjectId").is(subjectId)
                .and("providerLicenseId").is(providerLicenseId);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria), PaymentEntitlement.class));
    }

    public Optional<PaymentEntitlement> entitlementBySubscription(String providerSubscriptionId) {
        return Optional.ofNullable(mongo.findOne(Query.query(
                Criteria.where("providerSubscriptionId").is(providerSubscriptionId)), PaymentEntitlement.class));
    }

    public Optional<PaymentEntitlement> entitlementByProduct(String appId, String subjectId, String productCode) {
        Criteria criteria = Criteria.where("appId").is(appId).and("subjectId").is(subjectId)
                .and("productCode").is(productCode);
        return Optional.ofNullable(mongo.findOne(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "updatedAt")),
                PaymentEntitlement.class));
    }

    public List<PaymentEntitlement> entitlementsByCheckout(String checkoutId) {
        return mongo.find(Query.query(Criteria.where("checkoutId").is(checkoutId)), PaymentEntitlement.class);
    }

    public PaymentWebhookEvent insert(PaymentWebhookEvent value) { return mongo.insert(value); }
    public PaymentWebhookEvent save(PaymentWebhookEvent value) { return mongo.save(value); }
    public PaymentAdminAction save(PaymentAdminAction value) { return mongo.save(value); }

    public Optional<PaymentWebhookEvent> webhook(String payloadSha256) {
        return Optional.ofNullable(mongo.findById(payloadSha256, PaymentWebhookEvent.class));
    }

    public Slice<PaymentCheckout> checkouts(String appId, Boolean testMode, String status, int offset, int limit) {
        return slice(PaymentCheckout.class, paymentQuery(appId, testMode, status), offset, limit, "createdAt");
    }

    public Slice<PaymentOrder> orders(String appId, Boolean testMode, String status, int offset, int limit) {
        return slice(PaymentOrder.class, paymentQuery(appId, testMode, status), offset, limit, "providerCreatedAt");
    }

    public Slice<PaymentSubscription> subscriptions(String appId, Boolean testMode, String status, int offset, int limit) {
        return slice(PaymentSubscription.class, paymentQuery(appId, testMode, status), offset, limit, "providerCreatedAt");
    }

    public Slice<PaymentLicense> licenses(String appId, Boolean testMode, String status, int offset, int limit) {
        return slice(PaymentLicense.class, paymentQuery(appId, testMode, status), offset, limit, "createdAt");
    }

    public Slice<PaymentEntitlement> entitlements(String appId, Boolean testMode, String status, int offset, int limit) {
        return slice(PaymentEntitlement.class, paymentQuery(appId, testMode, status), offset, limit, "createdAt");
    }

    public Slice<PaymentWebhookEvent> webhooks(String appId, Boolean testMode, String status, int offset, int limit) {
        return slice(PaymentWebhookEvent.class, paymentQuery(appId, testMode, status), offset, limit, "receivedAt");
    }

    public Slice<PaymentAdminAction> adminActions(String appId, Boolean testMode, String status, int offset, int limit) {
        return slice(PaymentAdminAction.class, paymentQuery(appId, testMode, status), offset, limit, "createdAt");
    }

    public long count(Class<?> type, String appId, Boolean testMode, String status) {
        return mongo.count(paymentQuery(appId, testMode, status), type);
    }

    public long countWebhooks(String appId, Boolean testMode, String status) {
        return mongo.count(paymentQuery(appId, testMode, status), PaymentWebhookEvent.class);
    }

    public List<RevenueTotal> revenue(String appId, Boolean testMode) {
        List<Criteria> filters = paymentCriteria(appId, testMode, null);
        filters.add(Criteria.where("status").in("paid", "partial_refund", "refunded"));
        List<AggregationOperation> operations = new ArrayList<>();
        if (!filters.isEmpty()) operations.add(Aggregation.match(new Criteria().andOperator(filters)));
        operations.add(Aggregation.group("currency").sum("total").as("gross")
                .sum("refundedAmount").as("refunded").count().as("orders"));
        operations.add(Aggregation.project("gross", "refunded", "orders").and("_id").as("currency"));
        return mongo.aggregate(Aggregation.newAggregation(operations), PaymentOrder.class, Document.class)
                .getMappedResults().stream()
                .map(row -> {
                    long gross = number(row.get("gross"));
                    long refunded = number(row.get("refunded"));
                    return new RevenueTotal(row.getString("currency"), gross, refunded,
                            Math.max(0, gross - refunded), number(row.get("orders")));
                })
                .toList();
    }

    private <T> Slice<T> slice(Class<T> type, Query base, int offset, int limit, String sortField) {
        long total = mongo.count(base, type);
        Query page = Query.of(base).with(Sort.by(Sort.Direction.DESC, sortField)).skip(offset).limit(limit);
        return new Slice<>(mongo.find(page, type), total, offset, limit);
    }

    private static Query paymentQuery(String appId, Boolean testMode, String status) {
        Query query = new Query();
        for (Criteria criterion : paymentCriteria(appId, testMode, status)) query.addCriteria(criterion);
        return query;
    }

    private static List<Criteria> paymentCriteria(String appId, Boolean testMode, String status) {
        List<Criteria> filters = new ArrayList<>();
        if (appId != null && !appId.isBlank()) filters.add(Criteria.where("appId").is(appId));
        if (testMode != null) filters.add(Criteria.where("testMode").is(testMode));
        if (status != null && !status.isBlank()) filters.add(Criteria.where("status").is(status));
        return filters;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
