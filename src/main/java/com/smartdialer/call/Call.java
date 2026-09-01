package com.smartdialer.call;

import java.util.concurrent.atomic.AtomicReference;

public class Call {
    private final String id;
    private final String borrowerId;
    private final AtomicReference<CallStatus> status;

    public Call(String id, String borrowerId) {
        this.id = id;
        this.borrowerId = borrowerId;
        this.status = new AtomicReference<>(CallStatus.QUEUED);
    }

    public String getId() {
        return id;
    }

    public String getBorrowerId() {
        return borrowerId;
    }

    public CallStatus getStatus() {
        return status.get();
    }

    public boolean tryTransition(CallStatus expectedCurrent, CallStatus target) {
        if (!expectedCurrent.canTransitionTo(target)) {
            return false;
        }
        return status.compareAndSet(expectedCurrent, target);
    }

    // Unlike tryTransition, which requires the caller to already know the expected current
    // state (used when WE are driving a known transition, e.g. the allocator), applyEvent is
    // for externally-driven events (provider callbacks) where we don't know the current state
    // in advance — it reads the actual current state fresh on every loop iteration and retries
    // the CAS if it loses a race, re-validating legality each time.
    public boolean applyEvent(CallStatus target) {
        while (true) {
            CallStatus current = status.get();
            if (!current.canTransitionTo(target)) {
                return false; // illegal from current state — reject, don't guess
            }
            if (status.compareAndSet(current, target)) {
                return true; // won the race, transition applied
            }
            // Someone else changed status between our read and our CAS attempt — loop and retry
            // against the new current value. This is safe because canTransitionTo is re-checked
            // fresh on every iteration.
        }
    }
}