package com.loopers.infrastructure.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {
    Optional<PaymentJpaEntity> findByOrderId(long orderId);

    List<PaymentJpaEntity> findByOrderIdIn(Collection<Long> orderIds);
}
