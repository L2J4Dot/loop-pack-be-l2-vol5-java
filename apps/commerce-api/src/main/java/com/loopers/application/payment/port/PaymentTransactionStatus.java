package com.loopers.application.payment.port;

// PG가 보고한 거래 상태
public enum PaymentTransactionStatus {
    PENDING,
    SUCCESS,
    FAILED
}
