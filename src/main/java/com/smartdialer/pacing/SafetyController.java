package com.smartdialer.pacing;

import java.util.concurrent.atomic.AtomicInteger;

public class SafetyController {

    private final double providerFailureThreshold;
    private final int maxRingingUnbound;
    private final AtomicInteger consecutiveHighFailureChecks = new AtomicInteger(0);
    private static final int FALLBACK_TRIGGER_COUNT = 3;

    public SafetyController(double providerFailureThreshold, int maxRingingUnbound) {
        this.providerFailureThreshold = providerFailureThreshold;
        this.maxRingingUnbound = maxRingingUnbound;
    }

    public SafetyDecision evaluate(PacingRecommendation recommendation, SystemSnapshot snapshot) {
        boolean providerDegraded = snapshot.providerFailureRate() > providerFailureThreshold;

        if (providerDegraded) {
            consecutiveHighFailureChecks.incrementAndGet();
        } else {
            consecutiveHighFailureChecks.set(0);
        }

        if (consecutiveHighFailureChecks.get() >= FALLBACK_TRIGGER_COUNT) {
            return new SafetyDecision(
                Math.min(snapshot.availableAgents(), recommendation.suggestedNewCalls()),
                SafetyOutcome.FALLBACK_TO_PROGRESSIVE,
                "Provider failure rate " + snapshot.providerFailureRate() +
                " exceeded threshold " + providerFailureThreshold + " for " +
                consecutiveHighFailureChecks.get() + " consecutive checks — capping to available agents only.");
        }

        int ringingHeadroom = Math.max(0, maxRingingUnbound - snapshot.dialingAgents());
        int hardCap = Math.min(snapshot.availableAgents(), ringingHeadroom);

        if (recommendation.suggestedNewCalls() <= 0) {
            return new SafetyDecision(0, SafetyOutcome.REJECTED,
                "Pacing engine suggested 0 or fewer calls: " + recommendation.reasoning());
        }

        if (recommendation.suggestedNewCalls() > hardCap) {
            return new SafetyDecision(hardCap, SafetyOutcome.REDUCED,
                "Requested " + recommendation.suggestedNewCalls() + " but hard cap is " + hardCap +
                " (availableAgents=" + snapshot.availableAgents() +
                ", ringingHeadroom=" + ringingHeadroom + "). " + recommendation.reasoning());
        }

        return new SafetyDecision(recommendation.suggestedNewCalls(), SafetyOutcome.APPROVED,
            recommendation.reasoning());
    }
}
