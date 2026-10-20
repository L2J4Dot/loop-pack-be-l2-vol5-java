package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentRecordRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentRecordPersistenceIntegrationTest {
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() { cleanup.truncateAllTables(); }

    @Test
    @DisplayName("포인트 결제 기록을 저장한 뒤 새 트랜잭션에서 주문 ID로 다시 읽으면 수단·결제액·결과가 같다")
    void savesAndRestoresPointPayment() {
        PaymentRecord saved = paymentRecordRepository.save(PaymentRecord.point(42, new Money(7000)));

        PaymentRecord found = paymentRecordRepository.findByOrderId(42).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(42);
        assertThat(found.getMethod()).isEqualTo(PaymentRecord.Method.POINT);
        assertThat(found.getAmount()).isEqualTo(new Money(7000));
        assertThat(found.getResult()).isEqualTo(PaymentRecord.Result.SUCCESS);
        assertThat(paymentRecordRepository.findByOrderId(43)).isEmpty();
    }

    @Test
    @DisplayName("주문 ID 목록으로 조회하면 결제 기록이 있는 주문만 한 번에 돌려준다")
    void findsByOrderIds() {
        paymentRecordRepository.save(PaymentRecord.point(1, new Money(1000)));
        paymentRecordRepository.save(PaymentRecord.point(3, new Money(3000)));

        List<PaymentRecord> found = paymentRecordRepository.findByOrderIds(List.of(1L, 2L, 3L));

        assertThat(found).extracting(PaymentRecord::getOrderId).containsExactlyInAnyOrder(1L, 3L);
        assertThat(paymentRecordRepository.findByOrderIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("같은 주문에 결제 기록을 두 번 저장하면 유일 제약으로 거절하고 첫 기록만 남는다")
    void rejectsSecondPaymentForSameOrder() {
        paymentRecordRepository.save(PaymentRecord.point(42, new Money(7000)));

        assertThatThrownBy(() -> paymentRecordRepository.save(PaymentRecord.point(42, new Money(7000))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from payment_records where order_id = 42", Long.class))
            .isEqualTo(1);
    }
}
