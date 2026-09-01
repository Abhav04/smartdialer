package com.smartdialer.agent;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {

    @Test
    void twoWorkersRacingForOneAgentOnlyOneWins() throws InterruptedException {
        AgentRegistry registry = new AgentRegistry();
        Agent agent = new Agent("agent-1");
        agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        registry.register(agent);

        int workerCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < workerCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (registry.reserveAnyAvailable().isPresent()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        startGate.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(1, successCount.get(), "Exactly one worker should have reserved the agent");
        assertEquals(AgentStatus.RESERVED, agent.getStatus());
    }
}