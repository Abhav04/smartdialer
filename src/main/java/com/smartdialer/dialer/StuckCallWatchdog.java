package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.call.CallStatus;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class StuckCallWatchdog {

    private static final Logger log = Logger.getLogger(StuckCallWatchdog.class.getName());

    // A stuck/timed-out call watchdog operates as a periodic sweep over a shared timestamp map,
    // not as one scheduled task per call (a deliberate scale tradeoff, not an oversight).
    private final long staleThresholdMillis;

    // Progressive and predictive modes track different context types with different agent-release
    // semantics (DIALING vs CONNECTED), so the watchdog handles both context variants.
    public StuckCallWatchdog(ScheduledExecutorService scheduler, long checkIntervalMillis,
                              long staleThresholdMillis,
                              ConcurrentHashMap<String, Long> callStartTimestamps,
                              ConcurrentHashMap<String, ?> activeCalls) {
        this.staleThresholdMillis = staleThresholdMillis;
        scheduler.scheduleAtFixedRate(() -> sweep(callStartTimestamps, activeCalls),
            checkIntervalMillis, checkIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sweep(ConcurrentHashMap<String, Long> startTimestamps,
                        ConcurrentHashMap<String, ?> activeCalls) {
        long now = System.currentTimeMillis();
        for (var entry : startTimestamps.entrySet()) {
            String callId = entry.getKey();
            if (now - entry.getValue() < staleThresholdMillis) continue;

            Object rawCtx = activeCalls.get(callId);
            if (rawCtx == null) continue;

            if (rawCtx instanceof ActiveCallContext ctx) {
                log.warning("Watchdog: call " + callId + " stuck for over " + staleThresholdMillis +
                    "ms with no provider event — likely provider outage/timeout. Forcing CANCELLED.");
                ctx.call().applyEvent(CallStatus.CANCELLED);
                ctx.agent().tryTransition(AgentStatus.DIALING, AgentStatus.AVAILABLE);
            } else if (rawCtx instanceof PredictiveCallContext ctx) {
                log.warning("Watchdog: call " + callId + " stuck for over " + staleThresholdMillis +
                    "ms with no provider event — likely provider outage/timeout. Forcing CANCELLED.");
                ctx.call().applyEvent(CallStatus.CANCELLED);
                Agent boundAgent = ctx.getBoundAgent();
                if (boundAgent != null) {
                    boundAgent.tryTransition(AgentStatus.CONNECTED, AgentStatus.AVAILABLE);
                }
            }
        }
    }
}
