package com.loopers.application.order.port;

import com.loopers.application.order.OrderConfirmedPayload;

public interface OrderConfirmedPublisher {
    void send(OrderConfirmedPayload payload);
}
