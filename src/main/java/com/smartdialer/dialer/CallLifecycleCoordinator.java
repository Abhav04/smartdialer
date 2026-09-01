package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.call.CallStatus;
import com.smartdialer.provider.CallEventListener;
import com.smartdialer.queue.BorrowerClaimRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class CallLifecycleCoordinator implements CallEventListener {

    private static final Logger log = Logger.getLogger(CallLifecycleCoordinator.class.getName());

    private final ConcurrentHashMap<String, ActiveCallContext> activeCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> callStartTimestamps;
    private final BorrowerClaimRegistry claimRegistry;
    private final ScheduledExecutorService scheduler;
    private final long wrapUpMillis;

    public CallLifecycleCoordinator(BorrowerClaimRegistry claimRegistry,
                                     ScheduledExecutorService scheduler,
                                     long wrapUpMillis) {
        this(claimRegistry, scheduler, wrapUpMillis, new ConcurrentHashMap<>());
    }

    public CallLifecycleCoordinator(BorrowerClaimRegistry claimRegistry,
                                     ScheduledExecutorService scheduler,
                                     long wrapUpMillis,
                                     ConcurrentHashMap<String, Long> callStartTimestamps) {
        this.claimRegistry = claimRegistry;
        this.scheduler = scheduler;
        this.wrapUpMillis = wrapUpMillis;
        this.callStartTimestamps = callStartTimestamps;
    }

    public void register(String callId, ActiveCallContext context) {
        activeCalls.put(callId, context);
        callStartTimestamps.put(callId, System.currentTimeMillis());
    }

    public ConcurrentHashMap<String, ActiveCallContext> getActiveCallsMap() {
        return activeCalls;
    }

    @Override
    public void onEvent(String callId, CallStatus eventStatus) {
        ActiveCallContext ctx = activeCalls.get(callId);
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

        // Only real, first-time transitions reach here — duplicates never trigger side effects.
        switch (eventStatus) {
            case CONNECTED -> ctx.agent().tryTransition(AgentStatus.DIALING, AgentStatus.CONNECTED);
            case COMPLETED -> {
                ctx.agent().tryTransition(AgentStatus.CONNECTED, AgentStatus.WRAP_UP);
                scheduler.schedule(
                    () -> ctx.agent().tryTransition(AgentStatus.WRAP_UP, AgentStatus.AVAILABLE),
                    wrapUpMillis, TimeUnit.MILLISECONDS);
                finishCall(callId, ctx);
            }
            case FAILED -> {
                ctx.agent().tryTransition(AgentStatus.DIALING, AgentStatus.AVAILABLE);
                finishCall(callId, ctx);
            }
            case CANCELLED -> finishCall(callId, ctx);
            default -> { /* RINGING/ANSWERED: call state moved, agent stays as-is */ }
        }
    }

    private void finishCall(String callId, ActiveCallContext ctx) {
        activeCalls.remove(callId);
        callStartTimestamps.remove(callId);
        claimRegistry.release(ctx.borrowerId());
    }

    public int activeCallCount() {
        return activeCalls.size();
    }
}
