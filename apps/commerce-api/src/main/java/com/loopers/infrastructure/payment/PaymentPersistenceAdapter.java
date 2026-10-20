package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.Payment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class PaymentPersistenceAdapter implements PaymentRepository {
    private final PaymentJpaRepository paymentJpaRepository;

    public PaymentPersistenceAdapter(PaymentJpaRepository paymentJpaRepository) {
        this.paymentJpaRepository = paymentJpaRepository;
    }

    @Override
    @Transactional
    public Payment save(Payment payment) {
        if (payment.getId() != null) {
            throw new IllegalStateException("저장된 결제 기록의 변경은 아직 지원하지 않습니다.");
        }
        PaymentJpaEntity entity = new PaymentJpaEntity(payment.getOrderId(), payment.getMethod().name(),
            payment.getAmount().value(), payment.getResult().name());
        return toDomain(paymentJpaRepository.save(entity));
    }

    @Override
    public Optional<Payment> findByOrderId(long orderId) {
        return paymentJpaRepository.findByOrderId(orderId).map(this::toDomain);
    }

    @Override
    public List<Payment> findByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return paymentJpaRepository.findByOrderIdIn(orderIds).stream().map(this::toDomain).toList();
    }

    private Payment toDomain(PaymentJpaEntity entity) {
        return Payment.restore(entity.getId(), entity.getOrderId(), Payment.Method.valueOf(entity.getMethod()),
            new Money(entity.getAmount()), Payment.Result.valueOf(entity.getResult()));
    }
}
