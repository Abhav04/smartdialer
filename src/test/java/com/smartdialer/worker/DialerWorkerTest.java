package com.smartdialer.worker;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.allocation.CallAllocator;
import com.smartdialer.dialer.CallLifecycleCoordinator;
import com.smartdialer.provider.TelecomProvider;
import com.smartdialer.queue.BorrowerClaimRegistry;
import com.smartdialer.queue.CallJob;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DialerWorkerTest {

    @Test
    void duplicateJobsForSameBorrowerOnlyResultInOneSuccessfulCall() throws Exception {
        AgentRegistry agentRegistry = new AgentRegistry();
        for (int i = 0; i < 10; i++) {
            Agent agent = new Agent("agent-" + i);
            agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
            agentRegistry.register(agent);
        }

        CallAllocator allocator = new CallAllocator(agentRegistry);
        BorrowerClaimRegistry claimRegistry = new BorrowerClaimRegistry();
        BlockingQueue<CallJob> jobQueue = new LinkedBlockingQueue<>();
        ScheduledExecutorService requeueScheduler = Executors.newScheduledThreadPool(2);
        CallLifecycleCoordinator coordinator = new CallLifecycleCoordinator(claimRegistry, requeueScheduler, 200);

        AtomicInteger placedCallsCount = new AtomicInteger(0);
        TelecomProvider provider = (callId, borrowerId, listener) -> {
            placedCallsCount.incrementAndGet();
        };

        // Simulate the exact bug scenario: the same borrower enqueued 20 times
        for (int i = 0; i < 20; i++) {
            jobQueue.add(new CallJob("job-" + i, "borrower-1"));
        }

        int workerCount = 8;
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            futures.add(pool.submit(new DialerWorker(
                jobQueue, claimRegistry, allocator, provider, coordinator,
                requeueScheduler, 500, running)));
        }

        // Wait briefly for all 20 duplicate jobs in the queue to be polled and processed
        long deadline = System.currentTimeMillis() + 3000;
        while (!jobQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        running.set(false);
        for (Future<?> f : futures) {
            try { f.get(5, TimeUnit.SECONDS); } catch (Exception e) { fail("Worker failed: " + e); }
        }
        pool.shutdown();
        requeueScheduler.shutdown();

        assertEquals(1, placedCallsCount.get(),
            "20 duplicate jobs for the same borrower must yield exactly 1 placed call");
    }
}
