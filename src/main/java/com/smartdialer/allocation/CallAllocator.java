package com.smartdialer.allocation;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.call.Call;
import com.smartdialer.call.CallStatus;
import java.util.Optional;

public class CallAllocator {

    private final AgentRegistry agentRegistry;

    public CallAllocator(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    public Optional<Agent> reserveAgentForCall(Call call) {
        Optional<Agent> reservedAgent = agentRegistry.reserveAnyAvailable();
        if (reservedAgent.isEmpty()) {
            return Optional.empty();
        }

        Agent agent = reservedAgent.get();
        boolean callReserved = call.tryTransition(CallStatus.QUEUED, CallStatus.RESERVED);

        if (!callReserved) {
            boolean rolledBack = agent.tryTransition(AgentStatus.RESERVED, AgentStatus.AVAILABLE);
            if (!rolledBack) {
                throw new IllegalStateException(
                    "Failed to roll back agent " + agent.getId() +
                    " after call reservation failure — agent state: " + agent.getStatus());
            }
            return Optional.empty();
        }

        return Optional.of(agent);
    }
}