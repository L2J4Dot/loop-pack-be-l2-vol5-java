package com.loopers.application.order;

import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentRecord;

// PG가 보고한 결과 하나. create 응답뿐 아니라 이후 조회·callback 결과도 이 모양으로 결과 반영 경계에 들어온다.
public record PaymentReport(PaymentRecord.ProviderStatus status, String transactionKey) {
    public PaymentReport {
        if (status == null || status == PaymentRecord.ProviderStatus.SEND_STARTED) {
            throw new IllegalArgumentException("PG 보고에는 발송 시작이 아닌 PG 상태가 필요합니다.");
        }
    }

    public static PaymentReport from(PaymentCreateResult result) {
        return switch (result) {
            case PaymentCreateResult.Accepted accepted -> new PaymentReport(switch (accepted.transaction().status()) {
                case PENDING -> PaymentRecord.ProviderStatus.PENDING;
                case SUCCESS -> PaymentRecord.ProviderStatus.SUCCESS;
                case FAILED -> PaymentRecord.ProviderStatus.FAILED;
            }, accepted.transaction().transactionKey());
            case PaymentCreateResult.RejectedNoEffect rejected ->
                new PaymentReport(PaymentRecord.ProviderStatus.REJECTED_NO_EFFECT, null);
            // 모든 PG 미발송 시 처리(서브 PG 전환 등)는 미결이라 지금은 결제 대기로 남긴다(설계 23절).
            case PaymentCreateResult.NotSent notSent -> new PaymentReport(PaymentRecord.ProviderStatus.NOT_SENT, null);
            case PaymentCreateResult.Unknown unknown -> new PaymentReport(PaymentRecord.ProviderStatus.UNKNOWN, null);
        };
    }

    Order.PaymentOutcome outcome() {
        return switch (status) {
            case SUCCESS -> Order.PaymentOutcome.SUCCEEDED;
            case FAILED, REJECTED_NO_EFFECT -> Order.PaymentOutcome.FAILED;
            case SEND_STARTED, NOT_SENT, PENDING, UNKNOWN -> Order.PaymentOutcome.UNKNOWN;
        };
    }
}
