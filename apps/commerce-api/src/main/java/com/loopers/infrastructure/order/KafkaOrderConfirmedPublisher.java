package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.port.OrderConfirmedPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaOrderConfirmedPublisher(KafkaTemplate<String, String> kafkaTemplate,
        @Value("${commerce.kafka.topics.order-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void send(OrderConfirmedPayload payload) {
        kafkaTemplate.send(topic, payload.key(), OrderConfirmedMessageSerializer.serialize(payload));
    }
}
