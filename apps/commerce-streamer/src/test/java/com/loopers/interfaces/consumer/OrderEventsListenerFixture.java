package com.loopers.interfaces.consumer;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static com.loopers.kafka.KafkaLearningSupport.admin;
import static com.loopers.kafka.KafkaLearningSupport.awaitUntil;
import static com.loopers.kafka.KafkaLearningSupport.closeAll;
import static com.loopers.kafka.KafkaLearningSupport.committedOffset;
import static com.loopers.kafka.KafkaLearningSupport.producer;

// 실제 listener 컨테이너를 관찰하고 테스트 record를 topic에 직접 넣는 도구. 공개 endpoint 없이 Kafka로만 이벤트를 보낸다.
final class OrderEventsListenerFixture implements AutoCloseable {

    static final String GROUP_ID = "purchase-activity";

    private final String topic;
    private final Admin admin;
    private final KafkaProducer<String, String> producer;
    private final MessageListenerContainer container;

    OrderEventsListenerFixture(String bootstrapServers, String topic, KafkaListenerEndpointRegistry registry) {
        this.topic = topic;
        this.admin = admin(bootstrapServers);
        this.producer = producer(bootstrapServers);
        this.container = registry.getListenerContainer(OrderEventsListener.LISTENER_ID);
    }

    MessageListenerContainer container() {
        return container;
    }

    // kafka.yml의 auto.offset.reset=latest라 배정 전에 보낸 record는 새 group이 읽지 않는다. 배정을 확인한 뒤 보낸다.
    void awaitAssigned() {
        awaitUntil("order-events listener 배정", () -> {
            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            return container.isRunning() && assigned != null && !assigned.isEmpty();
        });
    }

    long send(String key, String json) throws Exception {
        return producer.send(new ProducerRecord<>(topic, key, json)).get(10, TimeUnit.SECONDS).offset();
    }

    Long committed() {
        try {
            return committedOffset(admin, GROUP_ID, new TopicPartition(topic, 0));
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 조회 실패", e);
        }
    }

    void awaitCommitted(long expected) {
        awaitUntil("committed offset " + expected, () -> Long.valueOf(expected).equals(committed()));
    }

    void awaitStopped() {
        awaitUntil("listener 컨테이너 정지", () -> !container.isRunning());
    }

    @Override
    public void close() {
        closeAll(List.of(producer, admin));
    }
}
