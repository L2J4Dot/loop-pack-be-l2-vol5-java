package com.loopers.batch.job.reconcile;

import java.time.Instant;

public final class RepublishCommandSerializer {
    private RepublishCommandSerializer() {
    }

    public static String serialize(long orderId, Instant requestedAt) {
        return "{}";
    }
}
