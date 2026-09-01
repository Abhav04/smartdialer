package com.smartdialer.pacing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafetyControllerTest {

    @Test
    void neverApprovesMoreThanAvailableAgents() {
        SafetyController controller = new SafetyController(0.3, 100);
        SystemSnapshot snapshot = new SystemSnapshot(10, 5, 5, 3, 0.5, 120, 5, 0.05);
        PacingRecommendation rec = new PacingRecommendation(50, "aggressive test recommendation");

        SafetyDecision decision = controller.evaluate(rec, snapshot);

        assertTrue(decision.approvedCalls() <= snapshot.availableAgents());
        assertEquals(SafetyOutcome.REDUCED, decision.outcome());
    }

    @Test
    void fallsBackToProgressiveAfterSustainedProviderDegradation() {
        SafetyController controller = new SafetyController(0.3, 100);
        SystemSnapshot degradedSnapshot = new SystemSnapshot(10, 5, 5, 3, 0.5, 120, 5, 0.9);
        PacingRecommendation rec = new PacingRecommendation(8, "test recommendation");

        controller.evaluate(rec, degradedSnapshot);
        controller.evaluate(rec, degradedSnapshot);
        SafetyDecision third = controller.evaluate(rec, degradedSnapshot);

        assertEquals(SafetyOutcome.FALLBACK_TO_PROGRESSIVE, third.outcome());
        assertTrue(third.approvedCalls() <= degradedSnapshot.availableAgents());
    }

    @Test
    void respectsRingingHeadroomCapIndependentOfAvailableAgents() {
        SafetyController controller = new SafetyController(0.3, 5);
        SystemSnapshot snapshot = new SystemSnapshot(20, 5, 4, 3, 0.5, 120, 5, 0.05);
        PacingRecommendation rec = new PacingRecommendation(20, "test recommendation");

        SafetyDecision decision = controller.evaluate(rec, snapshot);

        assertTrue(decision.approvedCalls() <= 1,
            "Only 1 slot of ringing headroom left (cap 5, 4 already dialing)");
    }
}
