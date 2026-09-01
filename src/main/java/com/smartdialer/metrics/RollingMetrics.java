package com.smartdialer.metrics;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public class RollingMetrics {

    // Deliberately simple fixed-size sliding window (not time-decayed/exponentially-weighted)
    // chosen for transparency and explainability over sophistication, given the prototype's scope.
    private static final int WINDOW_SIZE = 50;

    private final ConcurrentLinkedDeque<Boolean> recentAnswerOutcomes = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Double> recentCallDurationsSeconds = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Boolean> recentProviderOutcomes = new ConcurrentLinkedDeque<>();
    private final AtomicInteger abandonedCallCount = new AtomicInteger(0);

    public void recordCallOutcome(boolean answered, double durationSeconds) {
        pushBounded(recentAnswerOutcomes, answered);
        if (answered) {
            pushBounded(recentCallDurationsSeconds, durationSeconds);
        }
    }

    public void recordProviderOutcome(boolean succeeded) {
        pushBounded(recentProviderOutcomes, succeeded);
    }

    public void recordAbandonedCall() {
        abandonedCallCount.incrementAndGet();
    }

    public int getAbandonedCallCount() {
        return abandonedCallCount.get();
    }

    public double getRecentAnswerRate() {
        return average(recentAnswerOutcomes, 0.3); // sensible default before we have data
    }

    public double getAvgCallDurationSeconds() {
        return recentCallDurationsSeconds.isEmpty() ? 120.0 :
            recentCallDurationsSeconds.stream().mapToDouble(Double::doubleValue).average().orElse(120.0);
    }

    public double getProviderFailureRate() {
        double successRate = average(recentProviderOutcomes, 1.0);
        return 1.0 - successRate;
    }

    private <T> void pushBounded(ConcurrentLinkedDeque<T> deque, T value) {
        deque.addLast(value);
        while (deque.size() > WINDOW_SIZE) {
            deque.pollFirst();
        }
    }

    private double average(ConcurrentLinkedDeque<Boolean> deque, double defaultIfEmpty) {
        if (deque.isEmpty()) return defaultIfEmpty;
        long trueCount = deque.stream().filter(Boolean::booleanValue).count();
        return (double) trueCount / deque.size();
    }
}
