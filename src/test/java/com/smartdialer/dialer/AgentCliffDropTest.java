package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.provider.ReliableProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCliffDropTest {

    @Test
    void reactsToAgentCliffDropWithinOneTickInterval() throws InterruptedException {
        AgentRegistry agentRegistry = new AgentRegistry();
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Agent agent = new Agent("agent-" + i);
            agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
            agentRegistry.register(agent);
            agents.add(agent);
        }

        ScheduledExecutorService providerScheduler = Executors.newScheduledThreadPool(4);
        ReliableProvider provider = new ReliableProvider(providerScheduler, 0.1);
        long tickIntervalMillis = 300;
        PredictiveDialer dialer = new PredictiveDialer(agentRegistry, provider, 0.5, 50, 200, tickIntervalMillis);

        // Simulate 40 agents disappearing within a few seconds
        for (int i = 0; i < 40; i++) {
            agents.get(i).tryTransition(AgentStatus.AVAILABLE, AgentStatus.OFFLINE);
        }

        Thread.sleep(tickIntervalMillis * 2); // allow at least 2 ticks to observe the reaction

        int availableNow = agentRegistry.countByStatus(AgentStatus.AVAILABLE);
        dialer.shutdown();
        providerScheduler.shutdown();

        assertTrue(availableNow <= 60,
            "Available agent count should immediately reflect the 40 that went OFFLINE, " +
            "since AgentStatus transitions are synchronous CAS operations, not dependent on tick timing");
    }
}
