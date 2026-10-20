package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentRecordRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.PaymentRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class PaymentRecordPersistenceAdapter implements PaymentRecordRepository {
    private final PaymentRecordJpaRepository paymentRecordJpaRepository;

    public PaymentRecordPersistenceAdapter(PaymentRecordJpaRepository paymentRecordJpaRepository) {
        this.paymentRecordJpaRepository = paymentRecordJpaRepository;
    }

    @Override
    @Transactional
    public PaymentRecord save(PaymentRecord payment) {
        if (payment.getId() != null) {
            throw new IllegalStateException("저장된 결제 기록의 변경은 아직 지원하지 않습니다.");
        }
        PaymentRecordJpaEntity entity = new PaymentRecordJpaEntity(payment.getOrderId(), payment.getMethod().name(),
            payment.getAmount().value(), payment.getResult().name());
        return toDomain(paymentRecordJpaRepository.save(entity));
    }

    @Override
    public Optional<PaymentRecord> findByOrderId(long orderId) {
        return paymentRecordJpaRepository.findByOrderId(orderId).map(this::toDomain);
    }

    @Override
    public List<PaymentRecord> findByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return paymentRecordJpaRepository.findByOrderIdIn(orderIds).stream().map(this::toDomain).toList();
    }

    private PaymentRecord toDomain(PaymentRecordJpaEntity entity) {
        return PaymentRecord.restore(entity.getId(), entity.getOrderId(), PaymentRecord.Method.valueOf(entity.getMethod()),
            new Money(entity.getAmount()), PaymentRecord.Result.valueOf(entity.getResult()), null, null, null, null, null);
    }
}
