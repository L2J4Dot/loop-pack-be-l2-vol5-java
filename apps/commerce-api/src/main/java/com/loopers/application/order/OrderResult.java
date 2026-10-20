package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.payment.Payment;
import java.util.List;
import java.util.Optional;

public record OrderResult(long id, long userId, List<Item> items, long total, String status, long paidAmount, String paymentResult) {
    public record Item(long productId, int quantity, long unitPrice) { }

    static OrderResult from(Order order, Optional<Payment> payment) {
        List<Item> items = order.getItems().stream()
            .map(i -> new Item(i.productId().value(), i.quantity().value(), i.unitPrice().value())).toList();
        long paidAmount = payment.map(found -> found.getAmount().value()).orElse(0L);
        String paymentResult = payment.map(found -> found.getResult().name()).orElse("NOT_PAID");
        return new OrderResult(order.getId(), order.getUserId(), items, order.getTotal().value(), order.getStatus().name(),
            paidAmount, paymentResult);
    }
}
