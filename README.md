# SmartDialer

SmartDialer is a progressive and predictive telecom dialer prototype built in Java 21 and Spring Boot. It demonstrates distributed-systems-style concurrency control, in-memory atomic state machines, and a safety-controller-gated predictive pacing architecture where correctness and failure handling take absolute priority over feature breadth.

## Quick start

### Prerequisites
- **Java 21** (JDK 21+)
- **Apache Maven 3.9+**

```bash
# Clone the repository
git clone <repo-url>
cd smartdialer

# Run the complete test suite across all phases (state machines, chaos survivability, dialer campaigns)
mvn test

# Run the load test harness comparing scan-based vs queue-based agent registries at 100 / 1,000 / 10,000 agents
mvn compile exec:java -Dexec.mainClass=com.smartdialer.load.LoadTestHarness
```

## Running a simulation

The project includes an interactive CLI simulation runner that executes campaigns against simulated telecommunication providers under various operating conditions:

```bash
mvn compile exec:java -Dexec.mainClass=com.smartdialer.simulation.Simulation -Dexec.args="predictive C"
```

- **Mode**: `"progressive"` (1:1 agent-to-call reservation before dialing) or `"predictive"` (pacing-driven with deferred agent binding upon answer). Defaults to `"predictive"`.
- **Scenario**: `"A"` (20% answer rate), `"B"` (50% answer rate), `"C"` (70% answer rate), or `"D"` (45% changing mid-point approximation). Defaults to `"B"`.

## Project structure

- `com.smartdialer.agent`: Agent model, atomic state machine transitions (`AgentStatus`), and thread-safe registries (`AgentRegistry`, `ScalableAgentRegistry`).
- `com.smartdialer.call`: Call domain model, atomic state machine transitions (`CallStatus`), and idempotency-guaranteed event processing (`applyEvent`).
- `com.smartdialer.allocation`: `CallAllocator` handling double-reservation coordination with immediate compensating rollback on race conflicts.
- `com.smartdialer.provider`: `TelecomProvider` and callback listener interfaces alongside `ReliableProvider`, `FlakyProvider` (chaos simulation), and `CallEventProcessor`.
- `com.smartdialer.queue`: `CallJob` record and `BorrowerClaimRegistry` enforcing single-in-flight borrower deduplication.
- `com.smartdialer.worker`: `DialerWorker` polling job queues, managing claim ownership transfer, and dispatching calls.
- `com.smartdialer.dialer`: Orchestration layer including `ProgressiveDialer`, `PredictiveDialer`, lifecycle coordinators, context holders, and `StuckCallWatchdog`.
- `com.smartdialer.pacing`: Mathematically isolated `PredictivePacingEngine`, `SafetyController`, `SnapshotAssembler`, and immutable pacing value records.
- `com.smartdialer.metrics`: `RollingMetrics` sliding-window statistics tracking answer rates, call durations, provider success rates, and abandoned calls.
- `com.smartdialer.load`: `LoadTestHarness` measuring throughput (ops/sec) and p50/p99/max latency percentiles for registry scalability analysis.
- `com.smartdialer.simulation`: `Simulation` CLI entry point and `ScenarioConfig` managing scenario execution.

## Testing strategy

The codebase is validated through a four-layer testing pyramid:
1. **Unit Tests (State Machines & Concurrency)**: Verifies exact state transition validity, terminal-state immutability, and thread-safe lock-free CAS semantics (e.g. `AgentRegistryTest`, `ScalableAgentRegistryTest`, `CallAllocatorTest`).
2. **Integration Tests (Chaos & Survivability)**: Verifies that duplicate and out-of-order webhook events under real async scheduling never corrupt call states (e.g. `CallEventProcessorTest`, `DuplicateAndOutOfOrderEventScenarioTest`).
3. **End-to-End Tests (Campaign Draining & Safety)**: Verifies complete campaign draining, backoff requeue loops, safety budget clamping, and agent cliff-drop response latency (e.g. `ProgressiveDialerTest`, `PredictiveDialerTest`, `AgentCliffDropTest`, `SafetyControllerTest`).
4. **Load & Bottleneck Discovery**: Measures concurrency limits and latency degradation curves under high thread contention (e.g. `LoadTestHarness`).

## Synthesis: Maximum Utilization with Progressive Safety

To achieve the utilization gains of predictive dialing while retaining the deterministic safety of progressive dialing:
1. **Layered Hybrid Dispatch**: Treat progressive 1:1 reservation as the baseline floor. Overdial only against agents in bounded deterministic `WRAP_UP` states (or high-confidence duration decay), capping total unassigned ringing calls strictly to the immediate available buffer.
2. **Event-Driven Immediate Dispatch**: Eliminate periodic tick-interval latency by triggering opportunistic admissions immediately upon agent state transitions to `AVAILABLE` or `WRAP_UP`.
3. **Adaptive Planning Horizons**: Scale pacing horizons dynamically with `avgCallDurationSeconds` to prevent capacity starvation in long-call campaigns.
4. **Non-Bypassable Safety Boundary**: Enforce compile-time isolation between pacing recommendations and call dispatch, with automatic fallback to progressive dialing on provider jitter or agent cliff drops.

## Design docs

- [ARCHITECTURE.md](ARCHITECTURE.md): Detailed system pipeline architecture, dataflow graph, and state machine specifications.
- [ADR.md](ADR.md): Architecture Decision Records covering in-memory guarantees, CAS concurrency, claim ownership, isolation boundaries, and scalability fixes.
- [SIMULATION_REPORT.md](SIMULATION_REPORT.md): Scenario specifications, reproduction steps, empirical simulation data, and operational trade-off analysis.
