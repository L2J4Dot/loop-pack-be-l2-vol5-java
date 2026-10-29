package com.loopers.interfaces.consumer;

import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedEventParser {

    public OrderEventParseResult parse(String key, byte[] payload) {
        return null;
    }
}
