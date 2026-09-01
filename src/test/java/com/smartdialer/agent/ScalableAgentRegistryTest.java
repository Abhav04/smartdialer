package com.smartdialer.agent;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ScalableAgentRegistryTest {

    @Test
    void reservationOnlySucceedsForAgentsExplicitlyMarkedAvailable() {
        ScalableAgentRegistry registry = new ScalableAgentRegistry();
        Agent agent0 = new Agent("agent-0");
        Agent agent1 = new Agent("agent-1");
        Agent agent2 = new Agent("agent-2");

        registry.register(agent0);
        registry.register(agent1);
        registry.register(agent2);

        agent0.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        agent1.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        // agent-2 intentionally left OFFLINE and not marked available

        registry.markAvailable(agent0.getId());
        registry.markAvailable(agent1.getId());

        Optional<Agent> r1 = registry.reserveAnyAvailable();
        Optional<Agent> r2 = registry.reserveAnyAvailable();
        Optional<Agent> r3 = registry.reserveAnyAvailable();

        assertTrue(r1.isPresent());
        assertTrue(r2.isPresent());
        assertTrue(r1.get().getId().equals("agent-0") || r1.get().getId().equals("agent-1"));
        assertTrue(r2.get().getId().equals("agent-0") || r2.get().getId().equals("agent-1"));
        assertNotEquals(r1.get().getId(), r2.get().getId());
        assertFalse(r3.isPresent(), "Third reservation should be empty as availability is exhausted");
    }

    @Test
    void staleHintIsSkippedWithoutErrorWhenAgentAlreadyReservedByAnotherPath() {
        ScalableAgentRegistry registry = new ScalableAgentRegistry();
        Agent agent = new Agent("agent-0");
        registry.register(agent);

        agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        registry.markAvailable(agent.getId());

        // Simulate concurrent path reserving the agent directly, making the queued hint stale
        boolean directReserved = agent.tryTransition(AgentStatus.AVAILABLE, AgentStatus.RESERVED);
        assertTrue(directReserved);

        Optional<Agent> reserved = registry.reserveAnyAvailable();
        assertFalse(reserved.isPresent(), "Stale hint should be skipped cleanly without returning an already-reserved agent");
    }
}
