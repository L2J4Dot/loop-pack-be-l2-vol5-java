package com.loopers.confg.kafka;

import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;

public class RetryThenStopErrorHandler extends DefaultErrorHandler {

    public RetryThenStopErrorHandler(BackOff backOff) {
        super(backOff);
    }
}
