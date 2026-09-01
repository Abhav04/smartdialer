package com.smartdialer.simulation;

public record ScenarioConfig(double answerRate, double avgTalkTimeSec) {

    public static ScenarioConfig forLetter(String letter) {
        return switch (letter.toUpperCase()) {
            case "A" -> new ScenarioConfig(0.20, 120);
            case "B" -> new ScenarioConfig(0.50, 90);
            case "C" -> new ScenarioConfig(0.70, 180);
            // "D" represents the brief's "changing" scenario, approximated here as a fixed mid-point
            // rather than truly dynamic conditions — a stated simplification, not a full time-varying model
            case "D" -> new ScenarioConfig(0.45, 130);
            default -> throw new IllegalArgumentException("Unknown scenario: " + letter);
        };
    }
}
