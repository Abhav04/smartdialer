package com.smartdialer.dialer;

import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.allocation.CallAllocator;
import com.smartdialer.provider.TelecomProvider;
import com.smartdialer.queue.BorrowerClaimRegistry;
import com.smartdialer.queue.CallJob;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class ProgressiveDialer {

    private static final Logger log = Logger.getLogger(ProgressiveDialer.class.getName());

    private final BlockingQueue<CallJob> jobQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Long> callStartTimestamps = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService workerPool;
    private final ScheduledExecutorService requeueScheduler;
    private final CallLifecycleCoordinator coordinator;

    public ProgressiveDialer(AgentRegistry agentRegistry,
                              TelecomProvider provider,
                              int workerCount,
                              long requeueBackoffMillis,
                              long wrapUpMillis) {
        BorrowerClaimRegistry claimRegistry = new BorrowerClaimRegistry();
        CallAllocator allocator = new CallAllocator(agentRegistry);
        this.requeueScheduler = Executors.newScheduledThreadPool(2);
        this.coordinator = new CallLifecycleCoordinator(
            claimRegistry, requeueScheduler, wrapUpMillis, callStartTimestamps);

        new StuckCallWatchdog(requeueScheduler, 1000L, 8000L, callStartTimestamps,
            coordinator.getActiveCallsMap());

        this.workerPool = Executors.newFixedThreadPool(workerCount);

        for (int i = 0; i < workerCount; i++) {
            submitResilientWorker(new com.smartdialer.worker.DialerWorker(
                jobQueue, claimRegistry, allocator, provider, coordinator,
                requeueScheduler, requeueBackoffMillis, running));
        }
    }

    private void submitResilientWorker(com.smartdialer.worker.DialerWorker worker) {
        workerPool.submit(() -> {
            try {
                worker.run();
            } catch (RuntimeException e) {
                log.severe("Dialer worker thread crashed unexpectedly: " + e + " — resubmitting a replacement.");
                if (running.get()) {
                    submitResilientWorker(worker);
                }
            }
        });
    }

    public void submitCampaign(List<String> borrowerIds) {
        for (String borrowerId : borrowerIds) {
            jobQueue.add(new CallJob(UUID.randomUUID().toString(), borrowerId));
        }
    }

    public int activeCallCount() {
        return coordinator.activeCallCount();
    }

    public void shutdown() {
        running.set(false);
        workerPool.shutdown();
        requeueScheduler.shutdown();
    }
}
