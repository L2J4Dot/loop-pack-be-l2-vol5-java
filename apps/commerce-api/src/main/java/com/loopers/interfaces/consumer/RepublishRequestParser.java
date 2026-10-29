package com.loopers.interfaces.consumer;

import org.springframework.stereotype.Component;

@Component
public class RepublishRequestParser {
    public RepublishRequestParseResult parse(String key, byte[] payload) {
        return new RepublishRequestParseResult.Invalid("미구현");
    }
}
