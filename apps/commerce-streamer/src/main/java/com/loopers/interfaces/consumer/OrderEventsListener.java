package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.confg.kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OrderEventsListener {

    public static final String LISTENER_ID = "purchase-activity-order-events";

    private final OrderConfirmedEventParser orderConfirmedEventParser;
    private final PurchaseActivityHandler purchaseActivityHandler;

    public OrderEventsListener(
        OrderConfirmedEventParser orderConfirmedEventParser,
        PurchaseActivityHandler purchaseActivityHandler
    ) {
        this.orderConfirmedEventParser = orderConfirmedEventParser;
        this.purchaseActivityHandler = purchaseActivityHandler;
    }

    @KafkaListener(
        id = LISTENER_ID,
        topics = "${commerce.kafka.topics.order-events}",
        groupId = "purchase-activity",
        containerFactory = KafkaConfig.RECORD_LISTENER
    )
    public void onOrderEvent(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
    }
}
