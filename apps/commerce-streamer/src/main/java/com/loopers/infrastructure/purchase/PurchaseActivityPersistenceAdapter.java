package com.loopers.infrastructure.purchase;

import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchasedItem;
import com.loopers.application.purchase.port.PurchaseActivityRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PurchaseActivityPersistenceAdapter implements PurchaseActivityRepository {

    @Override
    public Optional<ProcessedConfirmation> findConfirmationByEventId(String eventId) {
        return Optional.empty();
    }

    @Override
    public Optional<ProcessedConfirmation> findConfirmationByOrderId(long orderId) {
        return Optional.empty();
    }

    @Override
    public void saveConfirmation(OrderConfirmedEvent event, Instant processedAt) {
    }

    @Override
    public void saveActivity(long orderId, PurchasedItem item, Instant confirmedAt, Instant createdAt) {
    }
}
