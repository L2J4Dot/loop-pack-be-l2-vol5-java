package com.loopers.interfaces.consumer;

import com.loopers.confg.kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class RepublishRequestsListener {
    public static final String LISTENER_ID = "order-event-republish-requests";

    @KafkaListener(
        id = LISTENER_ID,
        topics = "${commerce.kafka.topics.order-event-republish-requests}",
        groupId = "${commerce.kafka.groups.order-event-republish}",
        containerFactory = KafkaConfig.RECORD_LISTENER,
        properties = "auto.offset.reset=earliest"
    )
    public void onRequest(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        acknowledgment.acknowledge();
    }
}
