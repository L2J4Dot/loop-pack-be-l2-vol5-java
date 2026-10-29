package com.loopers.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaLearningSupport {

    public static final Duration DEADLINE = Duration.ofSeconds(30);
    public static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final long ADMIN_TIMEOUT_SECONDS = 10;

    private KafkaLearningSupport() {
    }

    public static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static Admin admin(String bootstrapServers) {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    public static void createTopic(Admin admin, String topic, int partitions) throws Exception {
        admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
            .all()
            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static KafkaProducer<String, String> producer(String bootstrapServers) {
        return new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
    }

    public static Map<String, Object> consumerConfig(
        String bootstrapServers,
        String groupId,
        String clientId,
        String autoOffsetReset
    ) {
        return Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG, groupId,
            ConsumerConfig.CLIENT_ID_CONFIG, clientId,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1
        );
    }

    public static KafkaConsumer<String, String> consumer(Map<String, Object> config) {
        return new KafkaConsumer<>(config);
    }

    public static Long committedOffset(Admin admin, String groupId, TopicPartition partition) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata()
            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        OffsetAndMetadata committed = offsets.get(partition);
        if (committed == null) {
            return null;
        }
        return committed.offset();
    }

    // 배정·fetch는 poll 안에서 진행되므로 condition이 poll을 부르며 마감 시간까지 다시 확인한다.
    public static void awaitUntil(String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
        }
        throw new AssertionError(DEADLINE.toSeconds() + "초 안에 도달하지 못함: " + description);
    }

    public static void closeAll(List<? extends AutoCloseable> closeables) {
        IllegalStateException failure = null;
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IllegalStateException("Kafka 클라이언트 종료 실패", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
