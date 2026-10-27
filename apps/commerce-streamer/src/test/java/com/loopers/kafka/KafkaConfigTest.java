package com.loopers.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {KafkaConfig.class, KafkaTestContainersConfig.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class KafkaConfigTest {

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @DisplayName("테스트 broker 주소")
    @Nested
    class BootstrapServers {

        @DisplayName("producer·consumer·admin이 모두 테스트 컨테이너의 같은 주소 하나를 쓴다")
        @Test
        void allClientsUseTestContainerAddress() {
            String containerAddress = System.getProperty("spring.kafka.bootstrap-servers");

            Object producerServers = kafkaProperties.buildProducerProperties(null)
                .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG);
            Object consumerServers = kafkaProperties.buildConsumerProperties(null)
                .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG);
            Object adminServers = kafkaProperties.buildAdminProperties(null)
                .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG);

            assertThat(containerAddress).isNotBlank();
            assertThat(String.valueOf(producerServers)).isEqualTo("[" + containerAddress + "]");
            assertThat(String.valueOf(consumerServers)).isEqualTo("[" + containerAddress + "]");
            assertThat(String.valueOf(adminServers)).isEqualTo(containerAddress);
        }
    }

    @DisplayName("기본 consumer 설정")
    @Nested
    class DefaultConsumer {

        @DisplayName("kafka.yml을 읽은 consumer의 실제 enable.auto.commit은 false이고 value는 byte 배열로 읽는다")
        @Test
        void autoCommitIsOffAndValueIsByteArray() {
            Map<String, Object> props = consumerFactory.getConfigurationProperties();
            ConsumerConfig consumerConfig = new ConsumerConfig(props);

            assertThat(consumerConfig.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
            assertThat(consumerConfig.getClass(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(StringDeserializer.class);
            assertThat(consumerConfig.getClass(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                .isEqualTo(ByteArrayDeserializer.class);
        }
    }
}
