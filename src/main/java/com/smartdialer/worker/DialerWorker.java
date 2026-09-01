package com.smartdialer.worker;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.allocation.CallAllocator;
import com.smartdialer.call.Call;
import com.smartdialer.call.CallStatus;
import com.smartdialer.dialer.ActiveCallContext;
import com.smartdialer.dialer.CallLifecycleCoordinator;
import com.smartdialer.provider.TelecomProvider;
import com.smartdialer.queue.BorrowerClaimRegistry;
import com.smartdialer.queue.CallJob;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class DialerWorker implements Runnable {

    private static final Logger log = Logger.getLogger(DialerWorker.class.getName());

    private final BlockingQueue<CallJob> jobQueue;
    private final BorrowerClaimRegistry claimRegistry;
    private final CallAllocator callAllocator;
    private final TelecomProvider provider;
    private final CallLifecycleCoordinator coordinator;
    private final ScheduledExecutorService requeueScheduler;
    private final long requeueBackoffMillis;
    private final AtomicBoolean running;

    public DialerWorker(BlockingQueue<CallJob> jobQueue,
                         BorrowerClaimRegistry claimRegistry,
                         CallAllocator callAllocator,
                         TelecomProvider provider,
                         CallLifecycleCoordinator coordinator,
                         ScheduledExecutorService requeueScheduler,
                         long requeueBackoffMillis,
                         AtomicBoolean running) {
        this.jobQueue = jobQueue;
        this.claimRegistry = claimRegistry;
        this.callAllocator = callAllocator;
        this.provider = provider;
        this.coordinator = coordinator;
        this.requeueScheduler = requeueScheduler;
        this.requeueBackoffMillis = requeueBackoffMillis;
        this.running = running;
    }

    @Override
    public void run() {
        while (running.get()) {
            CallJob job;
            try {
                job = jobQueue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (job != null) {
                processJob(job);
            }
        }
    }

    // registeredForAsyncCompletion is an explicit ownership-transfer flag. Before coordinator.register()
    // runs, this method owns the borrower claim and must release it on any exit path including an
    // unexpected exception (simulating a worker crash mid-flow). Once registration succeeds and placeCall
    // is dispatched, ownership of releasing the claim transfers to CallLifecycleCoordinator, which
    // releases it only when the call reaches a terminal event — so exactly one component is ever
    // responsible for the claim's release at any point in time.
    private void processJob(CallJob job) {
        if (!claimRegistry.tryClaim(job.borrowerId())) {
            return;
        }

        boolean registeredForAsyncCompletion = false;
        try {
            Call call = new Call(job.jobId(), job.borrowerId());
            Optional<Agent> agentOpt;
            try {
                agentOpt = callAllocator.reserveAgentForCall(call);
            } catch (IllegalStateException e) {
                log.severe("Allocation inconsistency for job " + job.jobId() + ": " + e.getMessage());
                return;
            }

            if (agentOpt.isEmpty()) {
                requeueScheduler.schedule(() -> jobQueue.add(job), requeueBackoffMillis, TimeUnit.MILLISECONDS);
                return;
            }

            Agent agent = agentOpt.get();
            boolean callInitiated = call.tryTransition(CallStatus.RESERVED, CallStatus.INITIATED);
            boolean agentDialing = agent.tryTransition(AgentStatus.RESERVED, AgentStatus.DIALING);

            if (!callInitiated || !agentDialing) {
                log.severe("Inconsistent state before dial for job " + job.jobId() +
                    " — call=" + call.getStatus() + " agent=" + agent.getStatus());
                return;
            }

            coordinator.register(job.jobId(), new ActiveCallContext(call, agent, job.borrowerId()));
            registeredForAsyncCompletion = true; // ownership of the claim transfers to the coordinator
            provider.placeCall(job.jobId(), job.borrowerId(), coordinator);

        } catch (RuntimeException unexpected) {
            log.severe("Worker crashed mid-processJob for job " + job.jobId() + ": " + unexpected);
        } finally {
            if (!registeredForAsyncCompletion) {
                claimRegistry.release(job.borrowerId());
            }
        }
    }
}
