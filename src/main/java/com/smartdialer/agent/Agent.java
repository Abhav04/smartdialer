package com.smartdialer.agent;

import java.util.concurrent.atomic.AtomicReference;

public class Agent {
    private final String id;
    private final AtomicReference<AgentStatus> status;

    public Agent(String id) {
        this.id = id;
        this.status = new AtomicReference<>(AgentStatus.OFFLINE);
    }

    public String getId() {
        return id;
    }

    public AgentStatus getStatus() {
        return status.get();
    }

    public boolean tryTransition(AgentStatus expectedCurrent, AgentStatus target) {
        if (!expectedCurrent.canTransitionTo(target)) {
            return false;
        }
        return status.compareAndSet(expectedCurrent, target);
    }
}