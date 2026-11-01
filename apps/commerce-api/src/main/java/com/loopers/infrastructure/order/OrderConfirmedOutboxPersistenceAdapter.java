package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.port.OrderConfirmedOutbox;
import org.springframework.stereotype.Repository;

@Repository
public class OrderConfirmedOutboxPersistenceAdapter implements OrderConfirmedOutbox {

    @Override
    public void append(OrderConfirmedPayload payload) {
    }
}
