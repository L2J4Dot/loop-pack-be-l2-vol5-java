package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPayload;

public final class OrderConfirmedMessageSerializer {
    private OrderConfirmedMessageSerializer() {
    }

    public static String serialize(OrderConfirmedPayload payload) {
        return "{}";
    }
}
