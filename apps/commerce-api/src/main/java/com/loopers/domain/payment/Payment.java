package com.loopers.domain.payment;

import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.common.Money;

public final class Payment {
    public enum Method { POINT, PG }

    public enum Result { SUCCESS }

    private final Long id;
    private final long orderId;
    private final Method method;
    private final Money amount;
    private final Result result;

    private Payment(Long id, long orderId, Method method, Money amount, Result result) {
        if (orderId <= 0) {
            throw new InvalidValueException("결제 기록의 주문 ID는 양수여야 합니다.");
        }
        if (method == null || amount == null || result == null) {
            throw new InvalidValueException("결제 기록에는 결제수단·결제액·결과가 필요합니다.");
        }
        this.id = id;
        this.orderId = orderId;
        this.method = method;
        this.amount = amount;
        this.result = result;
    }

    public static Payment point(long orderId, Money amount) {
        return new Payment(null, orderId, Method.POINT, amount, Result.SUCCESS);
    }

    public static Payment restore(long id, long orderId, Method method, Money amount, Result result) {
        return new Payment(id, orderId, method, amount, result);
    }

    public Long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public Method getMethod() {
        return method;
    }

    public Money getAmount() {
        return amount;
    }

    public Result getResult() {
        return result;
    }
}
