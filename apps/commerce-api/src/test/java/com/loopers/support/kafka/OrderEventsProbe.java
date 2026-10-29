package com.loopers.support.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;

// 단일 partition 테스트 topic을 고유 group의 consumer로 읽는다. 테스트마다 주문 ID가 다시 1부터 시작하므로
// 장면 시작 전 끝 위치(mark)부터만 읽는다. "없음"은 같은 producer가 나중에 보낸 다른 주문의 record(경계)가 도착할 때까지
// 읽은 뒤 판단한다. 한 partition 안에서는 보낸 순서대로 쌓이므로 경계보다 먼저 보낸 record는 경계 앞에 있다.
public class OrderEventsProbe implements AutoCloseable {
    public static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TopicPartition partition;
    private final KafkaConsumer<String, String> consumer;

    public OrderEventsProbe(String bootstrapServers, String topic) {
        createTopicIfAbsent(bootstrapServers, topic);
        this.partition = new TopicPartition(topic, 0);
        this.consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG, "order-events-probe-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        consumer.assign(List.of(partition));
    }

    public long mark() {
        return consumer.endOffsets(List.of(partition)).get(partition);
    }

    public ConsumerRecord<String, String> awaitEvent(long from, String eventId) {
        List<ConsumerRecord<String, String>> records = readUntil(from, found -> indexOf(found, eventId) >= 0,
            "eventId=" + eventId + " record 도착");
        return records.get(indexOf(records, eventId));
    }

    public List<ConsumerRecord<String, String>> awaitEvents(long from, String eventId, int count) {
        List<ConsumerRecord<String, String>> records = readUntil(from,
            found -> found.stream().filter(record -> eventId.equals(eventId(record))).count() >= count,
            "eventId=" + eventId + " record " + count + "건 도착");
        return records.stream().filter(record -> eventId.equals(eventId(record))).toList();
    }

    // 경계 record까지 읽어 그 사이(경계 포함)의 record를 돌려준다.
    public List<ConsumerRecord<String, String>> readThrough(long from, String boundaryEventId) {
        List<ConsumerRecord<String, String>> records = readUntil(from, found -> indexOf(found, boundaryEventId) >= 0,
            "경계 eventId=" + boundaryEventId + " record 도착");
        return records.subList(0, indexOf(records, boundaryEventId) + 1);
    }

    public static String eventId(ConsumerRecord<String, String> record) {
        return json(record).path("eventId").asText();
    }

    public static JsonNode json(ConsumerRecord<String, String> record) {
        try {
            return JSON.readTree(record.value());
        } catch (Exception e) {
            throw new IllegalStateException("record 값이 JSON이 아닙니다. offset=" + record.offset(), e);
        }
    }

    public static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON이 아닙니다.", e);
        }
    }

    @Override
    public void close() {
        consumer.close();
    }

    private List<ConsumerRecord<String, String>> readUntil(long from, Predicate<List<ConsumerRecord<String, String>>> done,
        String description) {
        consumer.seek(partition, from);
        List<ConsumerRecord<String, String>> records = new ArrayList<ConsumerRecord<String, String>>();
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            consumer.poll(POLL_TIMEOUT).forEach(records::add);
            if (done.test(records)) {
                return records;
            }
        }
        throw new AssertionError(DEADLINE.toSeconds() + "초 안에 도달하지 못함: " + description + ", 읽은 eventId="
            + records.stream().map(OrderEventsProbe::eventId).toList());
    }

    private static int indexOf(List<ConsumerRecord<String, String>> records, String eventId) {
        for (int index = 0; index < records.size(); index++) {
            if (eventId.equals(eventId(records.get(index)))) {
                return index;
            }
        }
        return -1;
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
