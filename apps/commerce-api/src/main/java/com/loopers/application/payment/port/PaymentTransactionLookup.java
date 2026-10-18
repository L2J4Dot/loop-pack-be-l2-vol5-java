package com.loopers.application.payment.port;

// 거래 키 조회 결과. NotFound는 PG가 그 키를 모른다고 답했다는 사실일 뿐 "결제 효과 없음"을 뜻하지 않는다.
public sealed interface PaymentTransactionLookup {
    record Found(PaymentTransaction transaction) implements PaymentTransactionLookup {
    }

    record NotFound() implements PaymentTransactionLookup {
    }

    record Failed(String reason) implements PaymentTransactionLookup {
    }
}
