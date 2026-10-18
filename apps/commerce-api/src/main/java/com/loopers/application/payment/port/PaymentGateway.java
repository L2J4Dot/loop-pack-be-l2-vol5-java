package com.loopers.application.payment.port;

// 외부 PG 연결 포트(설계 21절). 구현(어댑터)은 PG 응답을 사실 그대로 분류해 돌려줄 뿐 재시도·fallback·다른 PG 호출을 하지 않는다.
// 결과 해석(재조회·실패 확정·서브 PG 전환)은 호출하는 애플리케이션의 몫이다.
public interface PaymentGateway {
    // 레지스트리가 어댑터를 찾는 고유 식별자. 설정의 payment.providers.priority 항목과 같다.
    String providerId();

    // 한 번만 보낸다. 결과를 모르면 Unknown이며 같은 요청을 다시 보내지 않는다.
    PaymentCreateResult create(PaymentCreateCommand command);

    PaymentTransactionLookup findByTransactionKey(String transactionKey);

    PaymentTransactionsLookup findByOrderId(String providerOrderId);
}
