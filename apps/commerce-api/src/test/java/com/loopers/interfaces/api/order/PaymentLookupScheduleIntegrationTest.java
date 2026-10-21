package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.payment.port.LookupJitter;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class PaymentLookupScheduleIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private PaymentLookupScheduleRepository schedules;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;
    private long productId;
    private long orderId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id().value();
        productId = products.create(brand, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("트랜잭션 A 뒤 create 전 중단돼도 재조회 행과 5초 뒤 첫 시각이 저장된다")
    void storesScheduleBeforeCreate() {
        pg.crashBeforeSend();
        assertThatThrownBy(() -> orders.confirm(1, orderId, TestPgFixture.command())).isInstanceOf(IllegalStateException.class);
        scene("PAYMENT_PENDING", 3, "SEND_STARTED", 0, 0);
        assertThat(schedules.findByOrderId(orderId)).contains(new PaymentLookupScheduleRepository.Schedule(
            orderId, NOW.plusSeconds(5), 0, NOW, NOW));
    }

    @ParameterizedTest(name = "즉시 종결 PG 응답={0}")
    @EnumSource(value = CreateBehavior.class, names = {"ACCEPT_SUCCESS", "ACCEPT_FAILED", "REJECT_BEFORE_CREATE"})
    @DisplayName("create에서 즉시 종결되면 결과 반영 경계가 스케줄 행을 지운다")
    void removesScheduleForImmediateFinalResult(CreateBehavior behavior) {
        server.createBehavior(behavior);
        orders.confirm(1, orderId, TestPgFixture.command());
        boolean success = behavior == CreateBehavior.ACCEPT_SUCCESS;
        scene(success ? "CONFIRMED" : "PAYMENT_FAILED", success ? 3 : 5,
            success ? "SUCCESS" : behavior == CreateBehavior.ACCEPT_FAILED ? "FAILED" : "REJECTED_NO_EFFECT", 1, 0);
        assertNoSchedule();
    }

    @Test
    @DisplayName("callback 성공과 수동 reconcile 실패는 같은 결과 경계에서 스케줄 행을 지운다")
    void removesScheduleForCallbackAndManualResults() throws Exception {
        startPending();
        String key = server.transactions().get(0).transactionKey();
        server.changeStatus(key, "SUCCESS");
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"" + key + "\",\"orderId\":\"" + providerOrderId()
                    + "\",\"amount\":2000,\"status\":\"SUCCESS\"}"))
            .andExpect(status().isOk());
        scene("CONFIRMED", 3, "SUCCESS", 1, 1);
        assertNoSchedule();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        startPending();
        key = server.transactions().stream().filter(t -> t.orderId().equals(providerOrderId())).findFirst().orElseThrow().transactionKey();
        server.changeStatus(key, "FAILED");
        orders.reconcilePayment(orderId);
        scene("PAYMENT_FAILED", 3, "FAILED", 2, 2);
        assertNoSchedule();
    }

    @Test
    @DisplayName("중복 거래로 확인 필요가 되면 스케줄 행을 지우고 예약 재고를 유지한다")
    void removesScheduleForReview() {
        startPending();
        server.addTransaction(providerOrderId(), 2000, "SUCCESS");
        orders.reconcilePayment(orderId);
        scene("PAYMENT_PENDING", 3, "NEEDS_REVIEW", 1, 1);
        assertNoSchedule();
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies where kind = 'DUPLICATE_TRANSACTIONS'", Long.class))
            .isEqualTo(1);
    }

    private void startPending() {
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        orders.confirm(1, orderId, TestPgFixture.command());
        assertThat(schedules.findByOrderId(orderId)).isPresent();
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private void assertNoSchedule() {
        assertThat(jdbc.queryForObject("select count(*) from payment_lookup_schedules where order_id = ?", Long.class, orderId)).isZero();
    }

    private void scene(String status, int stock, String pgStatus, int creates, int lookups) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId)).isEqualTo(status);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(stock);
        assertThat(jdbc.queryForObject("select provider_status from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo(pgStatus);
        assertThat(server.createCalls()).isEqualTo(creates);
        assertThat(server.lookupCalls()).isEqualTo(lookups);
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutablePaymentClock mutablePaymentClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        LookupJitter fixedLookupJitter() {
            return maximum -> maximum;
        }
    }
}
