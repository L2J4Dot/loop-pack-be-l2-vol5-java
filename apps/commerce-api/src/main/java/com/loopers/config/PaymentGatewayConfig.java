package com.loopers.config;

import com.loopers.application.payment.PaymentGatewayRegistry;
import com.loopers.application.payment.port.PaymentGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 등록된 모든 PaymentGateway 빈과 우선순위 설정을 레지스트리로 묶는다. 어댑터가 없어도 레지스트리가 시작 실패 이유를 알리도록
// List 주입 대신 ObjectProvider로 모은다.
@Configuration
@EnableConfigurationProperties(PaymentProvidersProperties.class)
public class PaymentGatewayConfig {
    @Bean
    PaymentGatewayRegistry paymentGatewayRegistry(ObjectProvider<PaymentGateway> gateways,
        PaymentProvidersProperties properties) {
        return new PaymentGatewayRegistry(gateways.orderedStream().toList(), properties.priority());
    }
}
