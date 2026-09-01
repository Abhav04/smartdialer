package com.smartdialer.pacing;

public record SafetyDecision(int approvedCalls, SafetyOutcome outcome, String reasoning) {
}
