package com.loopers.config;

import com.loopers.application.payment.PaymentLookupPolicy;
import com.loopers.application.payment.port.LookupJitter;
import com.loopers.domain.payment.LookupBackoff;
import com.loopers.infrastructure.payment.FullLookupJitter;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentLookupConfig {
    @Bean
    LookupJitter lookupJitter() {
        return new FullLookupJitter(RandomGenerator.getDefault());
    }

    @Bean
    PaymentLookupPolicy paymentLookupPolicy(LookupJitter lookupJitter,
        @Value("${payment.lookup.initial-delay:5s}") Duration initialDelay,
        @Value("${payment.lookup.multiplier:2}") int multiplier,
        @Value("${payment.lookup.max-delay:1m}") Duration maxDelay,
        @Value("${payment.lookup.max-attempts:12}") int maxAttempts,
        @Value("${payment.lookup.batch-size:100}") int batchSize) {
        return new PaymentLookupPolicy(new LookupBackoff(initialDelay, multiplier, maxDelay), lookupJitter, maxAttempts, batchSize);
    }
}
