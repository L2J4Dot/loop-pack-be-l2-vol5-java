package com.loopers.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

// 주문 확정 이벤트 전용 producer(설계 27절). 공통 producer는 값을 JsonSerializer로 다시 감싸므로 이미 만든 JSON 문자열을 그대로 보낸다.
// acks·idempotence는 kafka-clients 기본값과 같지만 공통 설정이 바뀌어도 보장이 유지되게 명시한다.
@Configuration
public class OrderEventsKafkaConfig {
    @Bean
    KafkaTemplate<String, String> orderEventsKafkaTemplate(KafkaProperties kafkaProperties,
        @Value("${commerce.kafka.producer.max-block}") Duration maxBlock) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlock.toMillis());
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    // 로컬 단일 broker 기준 복제 1. partition 수는 나중에 늘리면 key 배치가 바뀌므로 처음부터 정한다([임시]).
    @Bean
    NewTopic orderEventsTopic(@Value("${commerce.kafka.topics.order-events}") String topic,
        @Value("${commerce.kafka.topics.order-events-partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic orderEventRepublishRequestsTopic(@Value("${commerce.kafka.topics.order-event-republish-requests}") String topic,
        @Value("${commerce.kafka.topics.order-event-republish-requests-partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
