package com.loopers.application.payment.port;

import java.util.List;

// 0건은 "결제 효과 없음"의 증거가 아니다.
public sealed interface PaymentTransactionsLookup {
    record Found(List<PaymentTransaction> transactions) implements PaymentTransactionsLookup {
        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    record Failed(String reason) implements PaymentTransactionsLookup {
    }
}
