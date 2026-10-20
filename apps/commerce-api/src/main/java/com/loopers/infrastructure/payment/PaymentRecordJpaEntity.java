package com.loopers.infrastructure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// 주문당 결제 기록은 하나다. 이관 스크립트의 재실행과 confirm 경쟁을 DB 유일 제약으로도 막는다(설계 22절).
@Entity
@Table(name = "payment_records",
    uniqueConstraints = @UniqueConstraint(name = "uk_payment_records_order_id", columnNames = "order_id"))
public class PaymentRecordJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false, length = 20)
    private String method;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Column(nullable = false, length = 20)
    private String result;

    protected PaymentRecordJpaEntity() { }

    PaymentRecordJpaEntity(long orderId, String method, long amount, String result) {
        this.orderId = orderId;
        this.method = method;
        this.amount = amount;
        this.result = result;
    }

    Long getId() { return id; }
    long getOrderId() { return orderId; }
    String getMethod() { return method; }
    long getAmount() { return amount; }
    String getResult() { return result; }
}
