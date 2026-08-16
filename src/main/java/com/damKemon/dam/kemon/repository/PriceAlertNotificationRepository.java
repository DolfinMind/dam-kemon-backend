package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.PriceAlertNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PriceAlertNotificationRepository
        extends MongoRepository<PriceAlertNotification, String> {

    List<PriceAlertNotification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserIdAndUnreadTrue(String userId);

    /** Used by the scheduler to enforce a same-day debounce. */
    List<PriceAlertNotification> findByUserIdAndProductIdAndCreatedAtAfter(
            String userId, String productId, LocalDateTime after);

    List<PriceAlertNotification> findTop100ByDeliveryStateInAndNextDeliveryAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<String> states, LocalDateTime due);
}
