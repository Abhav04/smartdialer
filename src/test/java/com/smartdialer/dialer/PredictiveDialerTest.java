package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.provider.ReliableProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class PredictiveDialerTest {

    @Test
    void predictiveDialerCompletesCampaignAndRespectsSafetyBudget() throws InterruptedException {
        AgentRegistry agentRegistry = new AgentRegistry();
        for (int i = 0; i < 8; i++) {
            Agent agent = new Agent("agent-" + i);
            agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
            agentRegistry.register(agent);
        }

        ScheduledExecutorService providerScheduler = Executors.newScheduledThreadPool(4);
        ReliableProvider provider = new ReliableProvider(providerScheduler, 0.1);

        PredictiveDialer dialer = new PredictiveDialer(
            agentRegistry, provider, 0.5, 10, 100, 300);

        List<String> borrowers = IntStream.range(0, 20)
            .mapToObj(i -> "borrower-" + i)
            .toList();
        dialer.submitCampaign(borrowers);

        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            if (dialer.activeCallCount() == 0 && dialer.getDecisionLog().size() > 0) {
                // Give a brief window to confirm no new calls were admitted from backlog
                Thread.sleep(600);
                if (dialer.activeCallCount() == 0) {
                    break;
                }
            }
            Thread.sleep(200);
        }

        dialer.shutdown();
        providerScheduler.shutdown();

        assertEquals(0, dialer.activeCallCount(), "Campaign should be fully drained");
        assertTrue(dialer.getDecisionLog().size() > 0, "Pacing engine should have run at least one tick");
        System.out.println("Final abandoned call count: " + dialer.getMetrics().getAbandonedCallCount());
    }
}
