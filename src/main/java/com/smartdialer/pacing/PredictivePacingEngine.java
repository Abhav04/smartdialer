package com.smartdialer.pacing;

public class PredictivePacingEngine {

    public PacingRecommendation recommend(SystemSnapshot snapshot) {
        double freeingUpSoon = estimateAgentsFreeingUpSoon(snapshot);
        double nearTermCapacity = snapshot.availableAgents() + freeingUpSoon;

        double effectiveAnswerRate = Math.max(snapshot.recentAnswerRate(), 0.01);
        double alreadyCommitted = snapshot.inFlightCalls() * effectiveAnswerRate;

        double remainingCapacity = nearTermCapacity - alreadyCommitted;
        int suggested = (int) Math.floor(remainingCapacity / effectiveAnswerRate);
        suggested = Math.max(suggested, 0);

        String reasoning = String.format(
            "availableAgents=%d, freeingUpSoon=%.1f, nearTermCapacity=%.1f, " +
            "inFlightCalls=%d, answerRate=%.2f, alreadyCommitted=%.1f, remainingCapacity=%.1f -> suggested=%d",
            snapshot.availableAgents(), freeingUpSoon, nearTermCapacity,
            snapshot.inFlightCalls(), effectiveAnswerRate, alreadyCommitted, remainingCapacity, suggested);

        return new PacingRecommendation(suggested, reasoning);
    }

    private double estimateAgentsFreeingUpSoon(SystemSnapshot snapshot) {
        if (snapshot.avgCallDurationSeconds() <= 0) return 0;
        // Rough fraction of connected agents likely to wrap up within our planning horizon
        // (we use a fixed 10-second horizon as a simplifying assumption for this prototype).
        double planningHorizonSeconds = 10.0;
        double fractionFreeing = Math.min(1.0, planningHorizonSeconds / snapshot.avgCallDurationSeconds());
        return snapshot.connectedAgents() * fractionFreeing;
    }
}
