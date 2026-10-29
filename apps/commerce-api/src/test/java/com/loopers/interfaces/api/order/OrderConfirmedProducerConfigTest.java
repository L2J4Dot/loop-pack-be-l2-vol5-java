package com.loopers.interfaces.api.order;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OrderConfirmedProducerConfigTest extends OrderEventsIntegrationSupport {
    @Autowired private KafkaTemplate<String, String> orderEventsKafkaTemplate;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Test
    @DisplayName("주문 이벤트 producer의 실제 설정은 acks=all(-1)·idempotence 켬·max.block.ms 2초·문자열 값 직렬화다")
    void producerGuaranteesAndShortBlock() {
        ProducerConfig config = new ProducerConfig(orderEventsKafkaTemplate.getProducerFactory().getConfigurationProperties());

        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(2000L);
        assertThat(config.getClass(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(config.getClass(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
    }

    @Test
    @DisplayName("kafka-clients 3.8 기본값도 acks=all(-1)·idempotence 켬이다(명시 설정은 공통 설정 변경에 대비한 것)")
    void kafkaClientDefaultsMatch() {
        ProducerConfig defaults = new ProducerConfig(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));

        assertThat(defaults.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(defaults.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(defaults.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("commerce-api는 order-events topic을 설정한 partition 수·복제 1로 선언하고 Kafka listener(consumer)는 하나도 띄우지 않는다")
    void declaresTopicWithoutListeners() {
        assertThat(applicationContext.getBeansOfType(NewTopic.class).values()).singleElement().satisfies(topic -> {
            assertThat(topic.name()).isEqualTo(TOPIC);
            assertThat(topic.numPartitions()).isEqualTo(1);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        });
        assertThat(kafkaListenerEndpointRegistry.getListenerContainers()).isEmpty();
    }
}
