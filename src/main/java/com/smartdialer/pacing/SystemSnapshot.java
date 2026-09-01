package com.smartdialer.pacing;

public record SystemSnapshot(
    int availableAgents,
    int connectedAgents,
    int dialingAgents,
    int inFlightCalls,
    double recentAnswerRate,
    double avgCallDurationSeconds,
    double avgSetupTimeSeconds,
    double providerFailureRate
) {
}
