package com.loopers.application.payment.port;

import com.loopers.domain.common.Money;

// PG가 보고한 거래 사실
public record PaymentTransaction(String transactionKey, String providerOrderId, Money amount, PaymentTransactionStatus status) {
}
