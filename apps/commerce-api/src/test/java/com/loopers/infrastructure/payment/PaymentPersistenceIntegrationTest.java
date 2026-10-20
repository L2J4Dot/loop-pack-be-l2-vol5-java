package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.Payment;
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
class PaymentPersistenceIntegrationTest {
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() { cleanup.truncateAllTables(); }

    @Test
    @DisplayName("포인트 결제 기록을 저장한 뒤 새 트랜잭션에서 주문 ID로 다시 읽으면 수단·결제액·결과가 같다")
    void savesAndRestoresPointPayment() {
        Payment saved = paymentRepository.save(Payment.point(42, new Money(7000)));

        Payment found = paymentRepository.findByOrderId(42).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getOrderId()).isEqualTo(42);
        assertThat(found.getMethod()).isEqualTo(Payment.Method.POINT);
        assertThat(found.getAmount()).isEqualTo(new Money(7000));
        assertThat(found.getResult()).isEqualTo(Payment.Result.SUCCESS);
        assertThat(paymentRepository.findByOrderId(43)).isEmpty();
    }

    @Test
    @DisplayName("주문 ID 목록으로 조회하면 결제 기록이 있는 주문만 한 번에 돌려준다")
    void findsByOrderIds() {
        paymentRepository.save(Payment.point(1, new Money(1000)));
        paymentRepository.save(Payment.point(3, new Money(3000)));

        List<Payment> found = paymentRepository.findByOrderIds(List.of(1L, 2L, 3L));

        assertThat(found).extracting(Payment::getOrderId).containsExactlyInAnyOrder(1L, 3L);
        assertThat(paymentRepository.findByOrderIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("같은 주문에 결제 기록을 두 번 저장하면 유일 제약으로 거절하고 첫 기록만 남는다")
    void rejectsSecondPaymentForSameOrder() {
        paymentRepository.save(Payment.point(42, new Money(7000)));

        assertThatThrownBy(() -> paymentRepository.save(Payment.point(42, new Money(7000))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from payments where order_id = 42", Long.class))
            .isEqualTo(1);
    }
}
