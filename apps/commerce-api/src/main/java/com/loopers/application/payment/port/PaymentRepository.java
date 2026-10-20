package com.loopers.application.payment.port;

import com.loopers.domain.payment.Payment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {
    Payment save(Payment payment);

    Optional<Payment> findByOrderId(long orderId);

    List<Payment> findByOrderIds(Collection<Long> orderIds);
}
