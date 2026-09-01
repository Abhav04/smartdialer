package com.smartdialer.agent;

import java.util.Map;
import java.util.Set;

public enum AgentStatus {
    OFFLINE,
    AVAILABLE,
    RESERVED,
    DIALING,
    CONNECTED,
    WRAP_UP,
    PAUSED;

    private static final Map<AgentStatus, Set<AgentStatus>> ALLOWED_TRANSITIONS = Map.of(
        OFFLINE, Set.of(AVAILABLE),
        AVAILABLE, Set.of(RESERVED, PAUSED, OFFLINE),
        // Predictive dialing binds an agent to a call that has already been answered by the provider
        // (deferred binding), so the agent transitions directly from RESERVED to CONNECTED without
        // ever passing through DIALING — this represents "agent bound to an already-answered call,"
        // a distinct real-world transition from progressive mode's "agent actively dialing."
        RESERVED, Set.of(DIALING, CONNECTED, AVAILABLE, OFFLINE),
        DIALING, Set.of(CONNECTED, AVAILABLE, OFFLINE),
        CONNECTED, Set.of(WRAP_UP, OFFLINE),
        WRAP_UP, Set.of(AVAILABLE, OFFLINE),
        PAUSED, Set.of(AVAILABLE, OFFLINE)
    );

    public boolean canTransitionTo(AgentStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
