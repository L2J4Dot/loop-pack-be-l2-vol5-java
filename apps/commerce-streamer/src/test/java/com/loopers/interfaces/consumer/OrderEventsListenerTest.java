package com.loopers.interfaces.consumer;

import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.loopers.support.OrderConfirmedMessages.json;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderEventsListenerTest {

    private static final String TOPIC = "order-events-listener-" + UUID.randomUUID();

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private OrderEventsListenerFixture fixture;
    private PurchaseActivityTables tables;

    @DynamicPropertySource
    static void orderEventsTopic(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-events", () -> TOPIC);
    }

    @BeforeEach
    void setUp() {
        fixture = new OrderEventsListenerFixture(bootstrapServers, TOPIC, kafkaListenerEndpointRegistry);
        tables = new PurchaseActivityTables(jdbcTemplate);
        fixture.awaitAssigned();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("정상 이벤트 1건을 보내면 구매 활동이 저장되고 group committed offset이 n+1이 된다")
    @Test
    void validEventIsStoredAndCommitted() throws Exception {
        long n = fixture.send("42", json("evt-42", 42, 7, 2, 8, 1));

        fixture.awaitCommitted(n + 1);

        System.out.println("[실험③ 정상] n=" + n + ", committed=" + fixture.committed());
        assertThat(tables.confirmations()).isEqualTo(1);
        assertThat(tables.activities()).isEqualTo(2);
        assertThat(tables.anomalies()).isZero();
    }

    @DisplayName("key와 payload orderId가 다른 record는 KEY_MISMATCH 이상 1행을 남기고 offset n+1로 정상 진행한다")
    @Test
    void keyMismatchIsRecordedAndCommitted() throws Exception {
        long n = fixture.send("99", json("evt-42", 42, 7, 2));

        fixture.awaitCommitted(n + 1);

        System.out.println("[실험③ 형식 오류] n=" + n + ", committed=" + fixture.committed());
        assertThat(tables.activities()).isZero();
        assertThat(tables.anomalyRows()).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "KEY_MISMATCH")
            .containsEntry("event_id", "evt-42")
            .containsEntry("order_id", 42L)
            .containsEntry("topic", TOPIC)
            .containsEntry("offset_no", n));
    }

    @DisplayName("깨진 JSON record는 INVALID_PAYLOAD 이상에 원문을 남기고 offset n+1로 정상 진행한다")
    @Test
    void malformedJsonIsRecordedAndCommitted() throws Exception {
        long n = fixture.send("42", "{\"eventType\":\"OrderConfirmed\",");

        fixture.awaitCommitted(n + 1);

        assertThat(tables.activities()).isZero();
        assertThat(tables.anomalyRows()).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "INVALID_PAYLOAD")
            .containsEntry("raw_payload", "{\"eventType\":\"OrderConfirmed\",")
            .containsEntry("offset_no", n));
    }
}
