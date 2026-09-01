package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.call.CallStatus;
import com.smartdialer.metrics.RollingMetrics;
import com.smartdialer.provider.CallEventListener;
import com.smartdialer.queue.BorrowerClaimRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class PredictiveCallLifecycleCoordinator implements CallEventListener {

    private static final Logger log = Logger.getLogger(PredictiveCallLifecycleCoordinator.class.getName());

    private final ConcurrentHashMap<String, PredictiveCallContext> activeCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> callStartTimestamps;
    private final AgentRegistry agentRegistry;
    private final BorrowerClaimRegistry claimRegistry;
    private final RollingMetrics metrics;
    private final ScheduledExecutorService scheduler;
    private final long wrapUpMillis;

    public PredictiveCallLifecycleCoordinator(AgentRegistry agentRegistry,
                                               BorrowerClaimRegistry claimRegistry,
                                               RollingMetrics metrics,
                                               ScheduledExecutorService scheduler,
                                               long wrapUpMillis) {
        this(agentRegistry, claimRegistry, metrics, scheduler, wrapUpMillis, new ConcurrentHashMap<>());
    }

    public PredictiveCallLifecycleCoordinator(AgentRegistry agentRegistry,
                                               BorrowerClaimRegistry claimRegistry,
                                               RollingMetrics metrics,
                                               ScheduledExecutorService scheduler,
                                               long wrapUpMillis,
                                               ConcurrentHashMap<String, Long> callStartTimestamps) {
        this.agentRegistry = agentRegistry;
        this.claimRegistry = claimRegistry;
        this.metrics = metrics;
        this.scheduler = scheduler;
        this.wrapUpMillis = wrapUpMillis;
        this.callStartTimestamps = callStartTimestamps;
    }

    public void register(String callId, PredictiveCallContext context) {
        activeCalls.put(callId, context);
        callStartTimestamps.put(callId, System.currentTimeMillis());
    }

    public ConcurrentHashMap<String, PredictiveCallContext> getActiveCallsMap() {
        return activeCalls;
    }

    public int activeCallCount() {
        return activeCalls.size();
    }

    @Override
    public void onEvent(String callId, CallStatus eventStatus) {
        PredictiveCallContext ctx = activeCalls.get(callId);
        if (ctx == null) {
            log.warning("Event " + eventStatus + " for unknown/expired call " + callId);
            return;
        }

        boolean applied = ctx.call().applyEvent(eventStatus);
        if (!applied) {
            log.info("Rejected event " + eventStatus + " for call " + callId +
                " — current status " + ctx.call().getStatus() + " (duplicate or out-of-order)");
            return;
        }

        switch (eventStatus) {
            case ANSWERED -> handleAnswered(callId, ctx);
            case COMPLETED -> {
                Agent agent = ctx.getBoundAgent();
                if (agent != null) {
                    agent.tryTransition(AgentStatus.CONNECTED, AgentStatus.WRAP_UP);
                    scheduler.schedule(
                        () -> agent.tryTransition(AgentStatus.WRAP_UP, AgentStatus.AVAILABLE),
                        wrapUpMillis, TimeUnit.MILLISECONDS);
                }
                metrics.recordCallOutcome(true, ctx.elapsedSeconds());
                finishCall(callId, ctx);
            }
            case FAILED -> {
                metrics.recordCallOutcome(false, ctx.elapsedSeconds());
                finishCall(callId, ctx);
            }
            case CANCELLED -> finishCall(callId, ctx);
            default -> { /* RINGING, INITIATED: no side effect yet */ }
        }
    }

    private void handleAnswered(String callId, PredictiveCallContext ctx) {
        var agentOpt = agentRegistry.reserveAnyAvailable();
        if (agentOpt.isEmpty()) {
            log.severe("COMPLIANCE: call " + callId + " for borrower " + ctx.borrowerId() +
                " answered with NO agent available — forcing disconnect to avoid abandoned call.");
            metrics.recordAbandonedCall();
            ctx.call().applyEvent(CallStatus.CANCELLED);
            finishCall(callId, ctx);
            return;
        }

        Agent agent = agentOpt.get();
        boolean bound = ctx.tryBindAgent(agent);
        if (!bound) {
            // Should be unreachable in current single-threaded-per-call event flow, but if it
            // ever happened, we must not strand the agent we just reserved.
            agent.tryTransition(AgentStatus.RESERVED, AgentStatus.AVAILABLE);
            log.severe("Unexpected: call " + callId + " already had a bound agent — released duplicate reservation.");
            return;
        }
        agent.tryTransition(AgentStatus.RESERVED, AgentStatus.CONNECTED);
    }

    private void finishCall(String callId, PredictiveCallContext ctx) {
        activeCalls.remove(callId);
        callStartTimestamps.remove(callId);
        claimRegistry.release(ctx.borrowerId());
    }
}
