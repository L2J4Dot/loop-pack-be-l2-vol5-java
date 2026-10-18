package com.loopers.application.payment.port;

// 결제 생성 요청의 결과 분류(설계 21절). 의심스러우면 Unknown이다.
public sealed interface PaymentCreateResult {
    // 연결 단계에서 실패해 요청이 PG에 닿지 않았음이 확실하다.
    record NotSent(String reason) implements PaymentCreateResult {
    }

    // PG가 거래를 만들고 접수했다.
    record Accepted(PaymentTransaction transaction) implements PaymentCreateResult {
    }

    // PG가 거래를 만들지 않았다고 명시하며 거절했다.
    record RejectedNoEffect(String code) implements PaymentCreateResult {
    }

    // 거래가 만들어졌는지 알 수 없다. reason은 원인 요약이며 카드 번호·응답 본문을 담지 않는다.
    record Unknown(String reason) implements PaymentCreateResult {
    }
}
