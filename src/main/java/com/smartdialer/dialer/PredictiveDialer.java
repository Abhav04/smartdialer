package com.smartdialer.dialer;

import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.metrics.RollingMetrics;
import com.smartdialer.pacing.*;
import com.smartdialer.provider.TelecomProvider;
import com.smartdialer.queue.BorrowerClaimRegistry;
import com.smartdialer.queue.CallJob;
import com.smartdialer.call.Call;
import com.smartdialer.call.CallStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class PredictiveDialer {

    private static final Logger log = Logger.getLogger(PredictiveDialer.class.getName());

    private final BlockingQueue<CallJob> backlog = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Long> callStartTimestamps = new ConcurrentHashMap<>();
    private final AgentRegistry agentRegistry;
    private final BorrowerClaimRegistry claimRegistry = new BorrowerClaimRegistry();
    private final RollingMetrics metrics = new RollingMetrics();
    private final SnapshotAssembler snapshotAssembler;
    private final PredictivePacingEngine pacingEngine = new PredictivePacingEngine();
    private final SafetyController safetyController;
    private final PredictiveCallLifecycleCoordinator coordinator;
    private final TelecomProvider provider;
    private final ScheduledExecutorService scheduler;
    private final List<SafetyDecision> decisionLog = new CopyOnWriteArrayList<>();

    public PredictiveDialer(AgentRegistry agentRegistry, TelecomProvider provider,
                             double providerFailureThreshold, int maxRingingUnbound,
                             long wrapUpMillis, long tickIntervalMillis) {
        this.agentRegistry = agentRegistry;
        this.provider = provider;
        this.snapshotAssembler = new SnapshotAssembler(agentRegistry, metrics);
        this.safetyController = new SafetyController(providerFailureThreshold, maxRingingUnbound);
        this.scheduler = Executors.newScheduledThreadPool(3);
        this.coordinator = new PredictiveCallLifecycleCoordinator(
            agentRegistry, claimRegistry, metrics, scheduler, wrapUpMillis, callStartTimestamps);

        new StuckCallWatchdog(scheduler, 1000L, 8000L, callStartTimestamps,
            coordinator.getActiveCallsMap());

        scheduler.scheduleAtFixedRate(this::tick, 0, tickIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public void submitCampaign(List<String> borrowerIds) {
        for (String borrowerId : borrowerIds) {
            backlog.add(new CallJob(UUID.randomUUID().toString(), borrowerId));
        }
    }

    private void tick() {
        SystemSnapshot snapshot = snapshotAssembler.assemble(coordinator.activeCallCount());
        PacingRecommendation recommendation = pacingEngine.recommend(snapshot);
        SafetyDecision decision = safetyController.evaluate(recommendation, snapshot);
        decisionLog.add(decision);

        log.info("TICK — " + decision.outcome() + " approvedCalls=" + decision.approvedCalls() +
            " | " + decision.reasoning());

        int admitted = 0;
        while (admitted < decision.approvedCalls()) {
            CallJob job = backlog.poll();
            if (job == null) break;
            if (!claimRegistry.tryClaim(job.borrowerId())) {
                continue; // duplicate borrower already in flight — skip, doesn't count against budget
            }
            dispatch(job);
            admitted++;
        }
    }

    private void dispatch(CallJob job) {
        Call call = new Call(job.jobId(), job.borrowerId());
        boolean reserved = call.tryTransition(CallStatus.QUEUED, CallStatus.RESERVED);
        boolean initiated = reserved && call.tryTransition(CallStatus.RESERVED, CallStatus.INITIATED);
        if (!initiated) {
            log.severe("Failed to admit call " + job.jobId() + " into INITIATED state — releasing claim.");
            claimRegistry.release(job.borrowerId());
            return;
        }
        coordinator.register(job.jobId(), new PredictiveCallContext(call, job.borrowerId()));
        provider.placeCall(job.jobId(), job.borrowerId(), coordinator);
    }

    public RollingMetrics getMetrics() { return metrics; }
    public int activeCallCount() { return coordinator.activeCallCount(); }
    public List<SafetyDecision> getDecisionLog() { return decisionLog; }

    public void shutdown() {
        scheduler.shutdown();
    }
}
