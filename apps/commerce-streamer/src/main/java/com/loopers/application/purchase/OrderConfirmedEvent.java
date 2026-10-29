package com.loopers.application.purchase;

import java.time.Instant;
import java.util.List;

public record OrderConfirmedEvent(String eventId, long orderId, Instant confirmedAt, List<PurchasedItem> items) {

    public OrderConfirmedEvent {
        items = List.copyOf(items);
    }

    public String fingerprint() {
        return "";
    }
}
