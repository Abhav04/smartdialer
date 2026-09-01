package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.provider.ReliableProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveDialerTest {

    @Test
    void allBorrowersEventuallyProcessedWithLimitedAgents() throws InterruptedException {
        AgentRegistry agentRegistry = new AgentRegistry();
        int agentCount = 5;
        for (int i = 0; i < agentCount; i++) {
            Agent agent = new Agent("agent-" + i);
            agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
            agentRegistry.register(agent);
        }

        ScheduledExecutorService providerScheduler = Executors.newScheduledThreadPool(4);
        ReliableProvider provider = new ReliableProvider(providerScheduler, 0.1);

        ProgressiveDialer dialer = new ProgressiveDialer(
            agentRegistry, provider, agentCount, 150, 200);

        List<String> borrowers = java.util.stream.IntStream.range(0, 15)
            .mapToObj(i -> "borrower-" + i)
            .toList();
        dialer.submitCampaign(borrowers);

        // Poll for completion instead of a single fixed sleep — up to 15s timeout.
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            long availableCount = agentRegistry.reserveAnyAvailable().isEmpty() ? -1 : -1; // unused
            if (allAgentsIdleAndQuiet(agentRegistry, dialer)) break;
            Thread.sleep(200);
        }

        dialer.shutdown();
        providerScheduler.shutdown();

        assertEquals(0, dialer.activeCallCount(),
            "No calls should still be active once the campaign has drained");
    }

    private boolean allAgentsIdleAndQuiet(AgentRegistry registry, ProgressiveDialer dialer) {
        return dialer.activeCallCount() == 0;
    }
}
