package com.loopers.domain.payment;

import com.loopers.domain.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRecordTest {
    @Test
    @DisplayName("주문 7000원을 포인트로 결제하면 수단 POINT·결과 SUCCESS·결제액 7000원인 결제 기록이 된다")
    void recordsPointPayment() {
        PaymentRecord payment = PaymentRecord.point(42, new Money(7000));

        assertThat(payment.getId()).isNull();
        assertThat(payment.getOrderId()).isEqualTo(42);
        assertThat(payment.getMethod()).isEqualTo(PaymentRecord.Method.POINT);
        assertThat(payment.getResult()).isEqualTo(PaymentRecord.Result.SUCCESS);
        assertThat(payment.getAmount()).isEqualTo(new Money(7000));
    }

    @Test
    @DisplayName("결제액이 없거나 주문 ID가 양수가 아니면 결제 기록을 만들지 않는다")
    void rejectsMissingAmountAndInvalidOrderId() {
        assertThatThrownBy(() -> PaymentRecord.point(42, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.point(0, new Money(7000))).isInstanceOf(IllegalArgumentException.class);
    }
}
