package com.loopers.job.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.batch.job.reconcile.OrderEventReconcileJobConfig;
import com.loopers.job.reconcile.ReconcileTestConfig.FailingCommandTemplate;
import com.loopers.job.reconcile.ReconcileTestConfig.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 MySQL·Kafka로 대사 Job을 직접 실행한다. 청크 경계를 보려고 청크 크기를 2로 둔다. 시작 시 Job 자동 실행은 끈다.
@SpringBootTest
@SpringBatchTest
@ExtendWith(OutputCaptureExtension.class)
@Import(ReconcileTestConfig.class)
@TestPropertySource(properties = {
    "spring.batch.job.name=" + OrderEventReconcileJobConfig.JOB_NAME,
    "spring.batch.job.enabled=false",
    "commerce.reconcile.chunk-size=2"
})
class OrderEventReconcileJobTest {
    private static final String TOPIC = "order-event-republish-requests-it-" + UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;
    @Autowired private JobLauncherTestUtils jobLauncherTestUtils;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;
    @Autowired private FailingCommandTemplate failingCommands;

    private CommandTopicProbe probe;

    @DynamicPropertySource
    static void commandTopic(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-event-republish-requests", () -> TOPIC);
    }

    @BeforeEach
    void prepare() {
        new ResourceDatabasePopulator(new ClassPathResource("sql/reconcile-source-tables.sql")).execute(dataSource);
        clock.set(NOW);
        failingCommands.disarm();
        probe = new CommandTopicProbe(bootstrapServers, TOPIC);
    }

    @AfterEach
    void clean() {
        probe.close();
        failingCommands.disarm();
        jdbc.execute("truncate table orders");
        jdbc.execute("truncate table processed_order_confirmation");
        jdbc.execute("truncate table reconcile_checkpoint");
    }

    @Test
    @DisplayName("CONFIRMED·구간 안·완료 기록 없음인 주문마다 명령 1건(key=주문 ID)을 보내고, 유예 안·확정 시각 없음·완료 기록 있음·CONFIRMED 아님은 빼며 워터마크는 구간 끝이다")
    void sendsOneCommandPerMissingConfirmedOrder() throws Exception {
        long first = order("CONFIRMED", NOW.minus(Duration.ofMinutes(10)));
        long second = order("CONFIRMED", NOW.minus(Duration.ofMinutes(6)));
        order("CONFIRMED", NOW.minus(Duration.ofMinutes(4)));
        order("CONFIRMED", null);
        long processed = order("CONFIRMED", NOW.minus(Duration.ofMinutes(10)));
        processed(processed);
        order("PAYMENT_PENDING", null);
        order("PAYMENT_FAILED", null);
        order("PAYMENT_EXPIRED", null);
        long from = probe.mark();

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<ConsumerRecord<String, String>> commands = probe.readFrom(from);
        assertThat(commands).extracting(ConsumerRecord::key).containsExactly(String.valueOf(first), String.valueOf(second));
        JsonNode command = JSON.readTree(commands.get(0).value());
        assertThat(command.path("commandType").asText()).isEqualTo("RepublishOrderConfirmed");
        assertThat(command.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(command.path("orderId").asLong()).isEqualTo(first);
        assertThat(command.path("requestedAt").asText()).isEqualTo("2026-10-29T01:00:00Z");
        assertThat(watermark()).isEqualTo(Map.of("at", utc(NOW.minus(Duration.ofMinutes(5))), "orderId", Long.MAX_VALUE));
    }

    @Test
    @DisplayName("같은 확정 시각 주문 3건이 청크(2건) 경계에 걸려도 빠지거나 겹치지 않고 각각 명령 1건이다")
    void tiedConfirmedAtAcrossChunkBoundary() throws Exception {
        Instant tied = NOW.minus(Duration.ofMinutes(10));
        long a = order("CONFIRMED", tied);
        long b = order("CONFIRMED", tied);
        long c = order("CONFIRMED", tied);
        long d = order("CONFIRMED", NOW.minus(Duration.ofMinutes(9)));
        long from = probe.mark();

        run();

        assertThat(probe.readFrom(from)).extracting(ConsumerRecord::key)
            .containsExactly(String.valueOf(a), String.valueOf(b), String.valueOf(c), String.valueOf(d));
    }

    @Test
    @DisplayName("지난 워터마크보다 조금 이른 시각으로 찍혔지만 그 뒤에 commit된 확정을 다음 실행이 겹침 구간에서 잡는다")
    void overlapCatchesLateCommittedConfirmation() throws Exception {
        run();
        assertThat(watermark()).containsEntry("at", utc(NOW.minus(Duration.ofMinutes(5))));
        long late = order("CONFIRMED", NOW.minus(Duration.ofMinutes(5)).minusSeconds(30));
        clock.set(NOW.plusSeconds(10));
        long from = probe.mark();

        run();

        assertThat(probe.readFrom(from)).extracting(ConsumerRecord::key).containsExactly(String.valueOf(late));
        assertThat(watermark()).containsEntry("at", utc(NOW.plusSeconds(10).minus(Duration.ofMinutes(5))));
    }

    @Test
    @DisplayName("둘째 청크의 명령 send가 실패하면 step이 실패하고 워터마크는 첫 청크 마지막 위치이며, 대역을 풀고 다시 실행하면 나머지를 보내고 구간 끝까지 간다")
    void failedSendKeepsWatermarkAtLastSuccessfulChunk() throws Exception {
        long o1 = order("CONFIRMED", NOW.minus(Duration.ofMinutes(20)));
        long o2 = order("CONFIRMED", NOW.minus(Duration.ofMinutes(18)));
        long o3 = order("CONFIRMED", NOW.minus(Duration.ofMinutes(16)));
        long o4 = order("CONFIRMED", NOW.minus(Duration.ofMinutes(14)));
        long o5 = order("CONFIRMED", NOW.minus(Duration.ofMinutes(12)));
        failingCommands.failFor(o4);
        long firstFrom = probe.mark();

        JobExecution failed = run();

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(watermark()).isEqualTo(Map.of("at", utc(NOW.minus(Duration.ofMinutes(18))), "orderId", o2));
        assertThat(probe.readFrom(firstFrom)).extracting(ConsumerRecord::key)
            .contains(String.valueOf(o1), String.valueOf(o2)).doesNotContain(String.valueOf(o4), String.valueOf(o5));

        failingCommands.disarm();
        clock.set(NOW.plusSeconds(1));
        long secondFrom = probe.mark();
        JobExecution retried = run();

        assertThat(retried.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(probe.readFrom(secondFrom)).extracting(ConsumerRecord::key)
            .contains(String.valueOf(o3), String.valueOf(o4), String.valueOf(o5)).doesNotContain(String.valueOf(o1));
        assertThat(watermark()).containsEntry("at", utc(NOW.plusSeconds(1).minus(Duration.ofMinutes(5))));
    }

    @Test
    @DisplayName("즉시 실행을 연달아 두 번 하면 두 번째는 지난 구간 끝 − 겹침부터의 새 구간만 보고 이미 지난 누락을 다시 보내지 않으며 요약 한 줄을 남긴다")
    void secondImmediateRunSeesOnlyNewWindow(CapturedOutput output) throws Exception {
        long missing = order("CONFIRMED", NOW.minus(Duration.ofMinutes(10)));
        long firstFrom = probe.mark();
        run();
        assertThat(probe.readFrom(firstFrom)).extracting(ConsumerRecord::key).containsExactly(String.valueOf(missing));
        clock.set(NOW.plusSeconds(1));
        long secondFrom = probe.mark();

        JobExecution second = run();

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(probe.readFrom(secondFrom)).isEmpty();
        assertThat(output.getAll())
            .contains("[대사 요약] 구간=[2026-10-29T00:54:00Z, 2026-10-29T00:55:01Z), 확정 주문=0건, 누락 orderId=[]");
        assertThat(output.getAll()).contains("[대사 요약] 구간=[1970-01-01T00:00:00Z, 2026-10-29T00:55:00Z), 확정 주문=1건, 누락 orderId=["
            + missing + "], 명령 send=1건 성공");
    }

    private JobExecution run() throws Exception {
        return jobLauncherTestUtils.launchJob(jobLauncherTestUtils.getUniqueJobParameters());
    }

    private long order(String status, Instant confirmedAt) {
        jdbc.update("insert into orders(user_id, total, status, confirmed_at) values (1, 1000, ?, ?)", status,
            confirmedAt == null ? null : utc(confirmedAt));
        return jdbc.queryForObject("select max(id) from orders", Long.class);
    }

    private void processed(long orderId) {
        jdbc.update("insert into processed_order_confirmation(event_id, order_id, payload_fingerprint, confirmed_at, processed_at)"
            + " values (?, ?, 'fp', ?, ?)", "order-confirmed:v1:" + orderId, orderId, utc(NOW), utc(NOW));
    }

    private Map<String, Object> watermark() {
        List<Map<String, Object>> rows = jdbc.queryForList("select watermark_confirmed_at, watermark_order_id"
            + " from reconcile_checkpoint where name = 'order-confirmed-republish'");
        assertThat(rows).hasSize(1);
        return Map.of("at", rows.get(0).get("watermark_confirmed_at"), "orderId", rows.get(0).get("watermark_order_id"));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
