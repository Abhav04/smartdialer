package com.smartdialer.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Optional;

public class ScalableAgentRegistry {

    private final ConcurrentHashMap<String, Agent> agents = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> availableAgentIds = new ConcurrentLinkedQueue<>();

    public void register(Agent agent) {
        agents.put(agent.getId(), agent);
    }

    public void markAvailable(String agentId) {
        availableAgentIds.offer(agentId);
    }

    public Optional<Agent> reserveAnyAvailable() {
        String candidateId;
        while ((candidateId = availableAgentIds.poll()) != null) {
            Agent agent = agents.get(candidateId);
            if (agent == null) continue; // shouldn't happen, defensive
            if (agent.tryTransition(AgentStatus.AVAILABLE, AgentStatus.RESERVED)) {
                return Optional.of(agent);
            }
            // Hint was stale (agent already taken/offline by the time we got here) — loop,
            // try the next queued hint rather than falling back to a full scan.
        }
        return Optional.empty();
    }

    public int countByStatus(AgentStatus status) {
        return (int) agents.values().stream().filter(a -> a.getStatus() == status).count();
    }
}
