package com.smartdialer.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class BorrowerClaimRegistry {

    private final ConcurrentHashMap<String, AtomicBoolean> claims = new ConcurrentHashMap<>();

    public boolean tryClaim(String borrowerId) {
        AtomicBoolean claimFlag = claims.computeIfAbsent(borrowerId, id -> new AtomicBoolean(false));
        return claimFlag.compareAndSet(false, true);
    }

    public void release(String borrowerId) {
        AtomicBoolean claimFlag = claims.get(borrowerId);
        if (claimFlag != null) {
            claimFlag.set(false);
        }
    }
}
