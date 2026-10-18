package com.loopers.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// PG 우선순위 설정(설계 21절). 항목은 PaymentGateway.providerId()와 같다.
@ConfigurationProperties("payment.providers")
public record PaymentProvidersProperties(List<String> priority) {
    public PaymentProvidersProperties {
        priority = priority == null ? List.of() : List.copyOf(priority);
    }
}
