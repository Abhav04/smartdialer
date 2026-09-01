package com.smartdialer.allocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.call.Call;
import com.smartdialer.call.CallStatus;

import java.util.Optional;
import org.junit.jupiter.api.Test;

public class CallAllocatorTest {

    @Test
    public void successfulReservationMovesBothAgentAndCallToReserved() {
        AgentRegistry registry = new AgentRegistry();
        Agent agent = new Agent("agent-1");
        registry.register(agent);
        boolean transitionResult = agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        assertTrue(transitionResult);

        Call call = new Call("call-1", "borrower-1");
        CallAllocator allocator = new CallAllocator(registry);

        Optional<Agent> allocatedAgent = allocator.reserveAgentForCall(call);

        assertTrue(allocatedAgent.isPresent());
        assertEquals("agent-1", allocatedAgent.get().getId());
        assertEquals(AgentStatus.RESERVED, agent.getStatus());
        assertEquals(CallStatus.RESERVED, call.getStatus());
    }

    @Test
    public void callAlreadyCancelledCausesAgentRollback() {
        AgentRegistry registry = new AgentRegistry();
        Agent agent = new Agent("agent-1");
        registry.register(agent);
        boolean transitionResult = agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        assertTrue(transitionResult);

        Call call = new Call("call-1", "borrower-1");
        boolean cancelled = call.tryTransition(CallStatus.QUEUED, CallStatus.CANCELLED);
        assertTrue(cancelled);

        CallAllocator allocator = new CallAllocator(registry);
        Optional<Agent> allocatedAgent = allocator.reserveAgentForCall(call);

        assertFalse(allocatedAgent.isPresent());
        assertEquals(AgentStatus.AVAILABLE, agent.getStatus());
        assertEquals(CallStatus.CANCELLED, call.getStatus());
    }

    @Test
    public void noAvailableAgentsMeansNoCallMutationAttempted() {
        AgentRegistry registry = new AgentRegistry();
        Call call = new Call("call-1", "borrower-1");

        CallAllocator allocator = new CallAllocator(registry);
        Optional<Agent> allocatedAgent = allocator.reserveAgentForCall(call);

        assertFalse(allocatedAgent.isPresent());
        assertEquals(CallStatus.QUEUED, call.getStatus());
    }

    @Test
    public void rollbackFailureThrowsIllegalStateException() {
        AgentRegistry registry = new AgentRegistry();
        
        // Use an anonymous subclass of Agent to simulate rollback failure
        Agent agent = new Agent("agent-flaky") {
            @Override
            public boolean tryTransition(AgentStatus expectedCurrent, AgentStatus target) {
                if (expectedCurrent == AgentStatus.RESERVED && target == AgentStatus.AVAILABLE) {
                    return false;
                }
                return super.tryTransition(expectedCurrent, target);
            }
        };
        
        registry.register(agent);
        boolean transitionResult = agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        assertTrue(transitionResult);

        Call call = new Call("call-1", "borrower-1");
        // Pre-cancel the call to force rollback execution
        boolean cancelled = call.tryTransition(CallStatus.QUEUED, CallStatus.CANCELLED);
        assertTrue(cancelled);

        CallAllocator allocator = new CallAllocator(registry);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            allocator.reserveAgentForCall(call);
        });

        assertTrue(ex.getMessage().contains("agent-flaky"));
    }
}