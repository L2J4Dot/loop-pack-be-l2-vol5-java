package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.port.OrderConfirmedPublisher;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {
    @Override
    public void send(OrderConfirmedPayload payload) {
    }
}
