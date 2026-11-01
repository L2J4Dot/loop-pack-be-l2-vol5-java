package com.loopers.infrastructure.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderConfirmedOutboxJpaRepository extends JpaRepository<OrderConfirmedOutboxJpaEntity, Long> {
}
