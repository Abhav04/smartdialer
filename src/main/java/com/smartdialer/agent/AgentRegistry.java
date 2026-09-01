package com.smartdialer.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

public class AgentRegistry {
    private final ConcurrentHashMap<String, Agent> agents = new ConcurrentHashMap<>();

    public void register(Agent agent) {
        agents.put(agent.getId(), agent);
    }

    public Optional<Agent> reserveAnyAvailable() {
        for (Agent agent : agents.values()) {
            if (agent.tryTransition(AgentStatus.AVAILABLE, AgentStatus.RESERVED)) {
                return Optional.of(agent);
            }
        }
        return Optional.empty();
    }

    public int countByStatus(AgentStatus status) {
        return (int) agents.values().stream().filter(a -> a.getStatus() == status).count();
    }
}