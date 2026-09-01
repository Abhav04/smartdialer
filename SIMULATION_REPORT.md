# Simulation Report

This report captures empirical performance and compliance metrics obtained by executing `com.smartdialer.simulation.Simulation` across the test scenarios (A, B, C, D) in both progressive and predictive dialing modes against simulated telecom provider chaos (`FlakyProvider` with timeout, failure, duplicate, and out-of-order event distributions).

## Scenario Table

| Scenario | Answer Rate | Avg Talk Time | Description / Operating Regime |
| :--- | :--- | :--- | :--- |
| **A** | 20% | 120s | Low answer rate; high dialing multiplier required to maintain agent utilization. |
| **B** | 50% | 90s | Standard baseline operational regime. |
| **C** | 70% | 180s | High answer rate with long duration; conservative pacing needed to prevent abandoned calls. |
| **D** | 45% | 130s | Changing operating conditions (approximated as a fixed mid-point per ADR.md). |

## Empirical Simulation Results

The table below reflects actual results captured from executing the 100-borrower, 20-agent campaign across all scenarios and modes:

| Scenario | Mode | Calls Completed | Calls Failed / Dropped | Calls Abandoned | Avg Agent Utilization % | Safety Decisions (Approved / Reduced / Rejected / Fallback) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **A** (20%, 120s) | Progressive | 18 | 82 | 0 | 15.3% | N/A (1:1 Progressive) |
| **A** (20%, 120s) | Predictive | 20 | 80 | 0 | 12.0% | Approved: 0 / Reduced: 61 / Rejected: 0 / Fallback: 0 |
| **B** (50%, 90s) | Progressive | 48 | 52 | 0 | 32.7% | N/A (1:1 Progressive) |
| **B** (50%, 90s) | Predictive | 47 | 48 | 5 | 34.7% | Approved: 0 / Reduced: 61 / Rejected: 0 / Fallback: 0 |
| **C** (70%, 180s) | Progressive | 68 | 32 | 0 | 38.0% | N/A (1:1 Progressive) |
| **C** (70%, 180s) | Predictive | 67 | 30 | 3 | 60.3% | Approved: 3 / Reduced: 58 / Rejected: 0 / Fallback: 0 |
| **D** (45%, 130s) | Progressive | 44 | 56 | 0 | 36.3% | N/A (1:1 Progressive) |
| **D** (45%, 130s) | Predictive | 45 | 55 | 0 | 20.2% | Approved: 0 / Reduced: 61 / Rejected: 0 / Fallback: 0 |

## How to reproduce

Run the simulation commands from the project root using Maven:

```bash
# Scenario A (Predictive)
mvn compile exec:java -Dexec.mainClass=com.smartdialer.simulation.Simulation -Dexec.args="predictive A"

# Scenario B (Predictive)
mvn compile exec:java -Dexec.mainClass=com.smartdialer.simulation.Simulation -Dexec.args="predictive B"

# Scenario C (Predictive)
mvn compile exec:java -Dexec.mainClass=com.smartdialer.simulation.Simulation -Dexec.args="predictive C"

# Scenario D (Predictive)
mvn compile exec:java -Dexec.mainClass=com.smartdialer.simulation.Simulation -Dexec.args="predictive D"
```

To run baseline progressive comparisons for any scenario, replace `"predictive"` with `"progressive"` in `-Dexec.args` (e.g. `-Dexec.args="progressive B"`).

## Interpretation notes

Predictive mode only clearly outperformed progressive mode in Scenario C (70% answer rate): **60.3% vs 38.0% utilization**, a genuine ~58% relative improvement. In Scenarios A and D, predictive mode underperformed progressive (12.0% vs 15.3%, and 20.2% vs 36.3% respectively), while in Scenario B the two modes performed similarly (34.7% vs 32.7%).

Two underlying mechanisms explain this pattern, visible directly in the safety decision telemetry (`REDUCED` dominates every scenario except C, where 3 decisions were fully `APPROVED`):

1. **Fixed Planning Horizon Miscalibration (10-second window)**:
   - The Pacing Engine's fixed 10-second planning horizon (`estimateAgentsFreeingUpSoon`) undersells near-term agent availability whenever `avgCallDurationSeconds` is large relative to that window. In Scenario A (120s calls), `fractionFreeing = min(1.0, 10/120) ≈ 0.083`, projecting that almost no connected agents will become free soon.
   - However, the pacing formula simultaneously divides by `effectiveAnswerRate` (0.20), inflating the raw suggestion. These two opposing forces cause the engine to over-suggest wildly against a shrunken capacity estimate, forcing the `SafetyController` to clamp 100% of decisions (`Reduced: 61 / Approved: 0`). The `SafetyController`—not the Pacing Engine—is doing all the active pacing work in low-to-moderate answer rate regimes.
2. **Tick-Interval Latency vs Continuous Worker Polling**:
   - `ProgressiveDialer` workers poll and requeue continuously on a 200ms cadence with zero inter-tick gaps. In contrast, `PredictiveDialer` admits new calls strictly once per `tickIntervalMillis` (500ms).
   - When the `SafetyController`'s hard cap collapses the admission budget down to `availableAgents` (as occurs in A, B, and D), predictive mode admits calls at roughly the same rate as progressive mode, but incurs artificial inter-tick latency gaps, resulting in lower net agent utilization without any compensating predictive lift.

**Core Architectural Takeaway**: Predictive dialing's advantage is real but highly conditional: it pays off specifically when answer rates are high enough (e.g., Scenario C) that near-term capacity and commitment math create genuine headroom that the `SafetyController` can safely Approve rather than clamp.
