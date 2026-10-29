package com.loopers.job.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;

// 단일 partition 명령 topic을 고유 group consumer로 읽는다. Job의 writer는 broker 확인을 받은 뒤에 끝나므로
// Job이 끝난 시점의 끝 위치까지 읽으면 그 실행이 보낸 명령이 모두 있다.
final class CommandTopicProbe implements AutoCloseable {
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private final TopicPartition partition;
    private final KafkaConsumer<String, String> consumer;

    CommandTopicProbe(String bootstrapServers, String topic) {
        createTopicIfAbsent(bootstrapServers, topic);
        this.partition = new TopicPartition(topic, 0);
        this.consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG, "republish-command-probe-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        consumer.assign(List.of(partition));
    }

    long mark() {
        return consumer.endOffsets(List.of(partition)).get(partition);
    }

    List<ConsumerRecord<String, String>> readFrom(long from) {
        long end = mark();
        consumer.seek(partition, from);
        List<ConsumerRecord<String, String>> records = new ArrayList<ConsumerRecord<String, String>>();
        Instant deadline = Instant.now().plus(DEADLINE);
        while (records.size() < end - from) {
            if (!Instant.now().isBefore(deadline)) {
                throw new AssertionError(DEADLINE.toSeconds() + "초 안에 끝 위치 " + end + "까지 읽지 못함");
            }
            consumer.poll(POLL_TIMEOUT).forEach(records::add);
        }
        return records;
    }

    @Override
    public void close() {
        consumer.close();
    }

    private static void createTopicIfAbsent(String bootstrapServers, String topic) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("테스트 topic을 만들지 못했습니다. topic=" + topic, e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("테스트 topic을 만들지 못했습니다. topic=" + topic, e);
        }
    }
}
