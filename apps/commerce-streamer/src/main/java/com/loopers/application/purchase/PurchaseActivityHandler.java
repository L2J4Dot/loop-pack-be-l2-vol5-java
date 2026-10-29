package com.loopers.application.purchase;

import com.loopers.application.purchase.port.OrderEventAnomalyRepository;
import com.loopers.application.purchase.port.PurchaseActivityRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PurchaseActivityHandler {

    private final PurchaseActivityRepository purchaseActivityRepository;
    private final OrderEventAnomalyRepository orderEventAnomalyRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PurchaseActivityHandler(
        PurchaseActivityRepository purchaseActivityRepository,
        OrderEventAnomalyRepository orderEventAnomalyRepository,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        this.purchaseActivityRepository = purchaseActivityRepository;
        this.orderEventAnomalyRepository = orderEventAnomalyRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public ConsumeResult consume(OrderConfirmedEvent event, EventSource source) {
        return null;
    }
}
