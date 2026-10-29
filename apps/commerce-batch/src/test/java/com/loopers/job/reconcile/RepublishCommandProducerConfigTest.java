package com.loopers.job.reconcile;

import com.loopers.CommerceBatchApplication;
import com.loopers.batch.job.reconcile.OrderEventReconcileJobConfig;
import java.lang.management.ManagementFactory;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트가 관리하는 컨텍스트를 직접 닫으면 Spring 테스트 리스너가 깨지므로 앱 컨텍스트를 따로 띄워 닫는다.
// 그래서 @SpringBootTest가 해 주던 테스트 전용 설정(@TestConfiguration) 제외를 여기서 하고, 공유 테스트 DB 스키마는 건드리지 않는다.
class RepublishCommandProducerConfigTest {
    // Kafka producer는 만들 때 app-info MBean을 등록하고 close할 때 해제한다. 실제 producer가 닫혔는지 본다.
    @Test
    @DisplayName("컨텍스트를 닫으면 재발행 명령용 producer factory가 정리되어 그 factory가 만든 Kafka producer가 닫힌다")
    void closesRepublishCommandProducerOnContextClose() throws Exception {
        ProducerFactory<?, ?> producerFactory;
        ObjectName appInfo;
        try (ConfigurableApplicationContext context = startBatchApplication()) {
            KafkaTemplate<?, ?> template = context.getBean("republishCommandKafkaTemplate", KafkaTemplate.class);
            producerFactory = template.getProducerFactory();
            appInfo = appInfoName(producerFactory.createProducer());
            assertThat(isRegistered(appInfo)).as("context를 닫기 전에는 producer가 열려 있다").isTrue();
        }

        try {
            assertThat(isRegistered(appInfo)).as("context를 닫은 뒤 재발행 명령 producer가 닫혔는가").isFalse();
        } finally {
            if (producerFactory instanceof DefaultKafkaProducerFactory<?, ?> leftOpen) {
                leftOpen.destroy();
            }
        }
    }

    private static ConfigurableApplicationContext startBatchApplication() {
        return new SpringApplicationBuilder(CommerceBatchApplication.class)
            .initializers(context -> context.getBeanFactory()
                .registerSingleton("testConfigurationExcludeFilter", new TestConfigurationExcludeFilter()))
            .run("--spring.batch.job.name=" + OrderEventReconcileJobConfig.JOB_NAME,
                "--spring.batch.job.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=none");
    }

    private static ObjectName appInfoName(Producer<?, ?> producer) throws MalformedObjectNameException {
        String clientId = producer.metrics().keySet().iterator().next().tags().get("client-id");
        return new ObjectName("kafka.producer:type=app-info,id=" + clientId);
    }

    private static boolean isRegistered(ObjectName name) {
        return ManagementFactory.getPlatformMBeanServer().isRegistered(name);
    }

    private static class TestConfigurationExcludeFilter extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
            return metadataReader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
        }
    }
}
