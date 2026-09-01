package com.smartdialer.call;

import java.util.Map;
import java.util.Set;

public enum CallStatus {
    QUEUED,
    RESERVED,
    INITIATED,
    RINGING,
    ANSWERED,
    CONNECTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Map<CallStatus, Set<CallStatus>> ALLOWED_TRANSITIONS = Map.of(
        QUEUED,    Set.of(RESERVED, CANCELLED),
        RESERVED,  Set.of(INITIATED, CANCELLED),
        INITIATED, Set.of(RINGING, FAILED, CANCELLED),
        RINGING,   Set.of(ANSWERED, FAILED, CANCELLED),
        ANSWERED,  Set.of(CONNECTED, FAILED, CANCELLED),
        CONNECTED, Set.of(COMPLETED, CANCELLED),
        COMPLETED, Set.of(),
        FAILED,    Set.of(),
        CANCELLED, Set.of()
    );

    public boolean canTransitionTo(CallStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}