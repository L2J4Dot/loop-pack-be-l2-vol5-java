package com.loopers.application.payment.port;

import java.util.List;

// 주문 식별자 조회 결과. 0건은 PG가 돌려준 목록이 비었다는 사실일 뿐 "결제 효과 없음"을 뜻하지 않는다.
public sealed interface PaymentTransactionsLookup {
    record Found(List<PaymentTransaction> transactions) implements PaymentTransactionsLookup {
        public Found {
            transactions = List.copyOf(transactions);
        }
    }

    record Failed(String reason) implements PaymentTransactionsLookup {
    }
}
