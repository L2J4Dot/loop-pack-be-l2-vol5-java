package com.loopers.infrastructure.purchase;

import com.loopers.application.purchase.EventSource;
import com.loopers.application.purchase.port.OrderEventAnomalyRepository;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class OrderEventAnomalyPersistenceAdapter implements OrderEventAnomalyRepository {

    @Override
    public void record(Kind kind, String eventId, Long orderId, EventSource source, String detail, Instant detectedAt) {
    }
}
