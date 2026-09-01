# SmartDialer

SmartDialer is a progressive and predictive telecom dialer prototype built in Java 21 and Spring Boot. It demonstrates distributed-systems-style concurrency control, in-memory atomic state machines, and a safety-controller-gated predictive pacing architecture where correctness and failure handling take absolute priority over feature breadth.

## Submission Deliverables & Implementation Map

| Deliverable | Description / Location in Repository |
| :--- | :--- |
| **Working Source Code** | Pure in-memory lock-free domain and dialer implementation under [`src/main/java/com/smartdialer`](src/main/java/com/smartdialer). |
| **README with Setup Instructions** | [`README.md`](README.md) (Prerequisites, build, test, and simulation instructions). |
| **Architecture Diagram** | Complete dataflow pipeline graph & component interactions in [`ARCHITECTURE.md`](ARCHITECTURE.md#architecture). |
| **Agent State Machine** | Implementation in [`AgentStatus.java`](src/main/java/com/smartdialer/agent/AgentStatus.java) & [`Agent.java`](src/main/java/com/smartdialer/agent/Agent.java); visual state diagrams in [`ARCHITECTURE.md`](ARCHITECTURE.md#agent-state-machine). |
| **Call State Machine** | Implementation in [`CallStatus.java`](src/main/java/com/smartdialer/call/CallStatus.java) & [`Call.java`](src/main/java/com/smartdialer/call/Call.java); visual state diagrams in [`ARCHITECTURE.md`](ARCHITECTURE.md#call-state-machine). |
| **Progressive Dialer** | 1:1 agent allocation and resilient worker pool orchestration in [`ProgressiveDialer.java`](src/main/java/com/smartdialer/dialer/ProgressiveDialer.java) and [`CallAllocator.java`](src/main/java/com/smartdialer/allocation/CallAllocator.java). |
| **Predictive Pacing Engine** | Structurally isolated pacing algorithm in [`PredictivePacingEngine.java`](src/main/java/com/smartdialer/pacing/PredictivePacingEngine.java) (zero imports from dialer/provider/allocator packages). |
| **Safety Controller** | Non-bypassable safety boundary enforcing hard caps and provider failure fallbacks in [`SafetyController.java`](src/main/java/com/smartdialer/pacing/SafetyController.java). |
| **Mock Telecom Providers** | Deterministic [`ReliableProvider.java`](src/main/java/com/smartdialer/provider/ReliableProvider.java) and chaos-simulating [`FlakyProvider.java`](src/main/java/com/smartdialer/provider/FlakyProvider.java) (timeouts, duplicates, out-of-order webhooks). |
| **Tests** | 17 unit, chaos integration, and end-to-end campaign test suites across 9 test classes in [`src/test/java`](src/test/java). |
| **Basic Simulation** | Interactive CLI simulation runner in [`Simulation.java`](src/main/java/com/smartdialer/simulation/Simulation.java) and empirical results across Scenarios A–D in [`SIMULATION_REPORT.md`](SIMULATION_REPORT.md). |
| **Basic Load Test** | Percentile latency and throughput benchmark harness in [`LoadTestHarness.java`](src/main/java/com/smartdialer/load/LoadTestHarness.java) proving scalability at 100, 1,000, and 10,000 agents. |
| **Architecture Decision Document** | Architecture Decision Records (ADR-1 through ADR-9 + empirical roadmap) in [`ADR.md`](ADR.md). |

---

## Core Question: Maximizing Predictive Utilization while Retaining Progressive Deterministic Safety

> **"How would you build a SmartDialer that gets as much of the utilization benefit of predictive dialing as possible, while retaining the deterministic safety characteristics of progressive dialing?"**

To capture predictive throughput gains without sacrificing progressive safety guarantees:

1. **Layered Two-Tier Allocation (Progressive Floor + Wrap-Up Overdial Cushion)**:
   - **Tier 1 (Deterministic Base)**: Reserve 1:1 against all currently `AVAILABLE` agents, establishing a guaranteed zero-abandonment baseline.
   - **Tier 2 (Bounded Predictive Cushion)**: Pre-dial *only* against agents entering deterministic `WRAP_UP` (or late-stage calls with high-confidence duration decay), strictly capping total unassigned ringing calls to `AvailableAgents + RingingHeadroom`.
2. **Event-Driven Immediate Dispatch (Eliminating Inter-Tick Latency)**:
   - Rather than waiting for a periodic 500ms pacing tick (which introduces idle air when safety caps collapse), trigger instant opportunistic dispatch the moment an agent completes wrap-up or transitions to `AVAILABLE`.
3. **Adaptive Planning Horizons**:
   - Scale the lookahead horizon dynamically with average call length (`horizon = max(5.0, avgTalkTime * 0.2)`) instead of using a static window, eliminating the artificial capacity starvation that throttles predictive dialing on long calls.
4. **Compile-Time Isolated Safety Boundary with Circuit Breaker**:
   - Enforce compile-time structural isolation between pacing recommendations and call dispatch (`PredictivePacingEngine` has zero dialer/provider dependencies). The `SafetyController` automatically drops back to 1:1 progressive dialing the moment provider failure rates spike or ringing queues fill.
5. **Auditable Zero-Limbo Compliance Fallback**:
   - In the rare event of an unexpected answer burst with zero agents free, immediately force-cancel the call with a tagged `[COMPLIANCE]` audit log, dedicated metric emission, and instant claim release rather than subjecting the customer to dead air.

---

## Local Setup & Execution Guide

SmartDialer requires **zero external infrastructure** (no Docker, no Redis, no Kafka, no database). It runs purely in-memory on standard Java 21 and Maven.

### Prerequisites
- **Java 21** (JDK 21+): Verify via `java -version`
- **Apache Maven 3.9+**: Verify via `mvn -version`

---

### Step 1: Clone & Build
```bash
git clone https://github.com/Abhav04/smartdialer.git
cd smartdialer

# Compile all source classes and verify build
mvn clean compile
```

---

### Step 2: Run the Full Test Suite
Execute all 17 unit and chaos integration tests:
```bash
mvn test
```
*Expected Result:*
```text
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

### Step 3: Run the Live Campaign Simulation
The interactive CLI simulation runs a 100-borrower, 20-agent campaign against simulated telecommunication providers under realistic network chaos (timeouts, duplicates, and out-of-order webhooks).

#### Command Syntax:
```bash
mvn compile exec:java -Dexec.mainClass="com.smartdialer.simulation.Simulation" -Dexec.args="<mode> <scenario>"
```

#### Available Parameters:
- **`<mode>`**: `predictive` (default) or `progressive`
- **`<scenario>`**:
  - `A`: 20% answer rate, 120s avg talk time (low answer rate)
  - `B`: 50% answer rate, 90s avg talk time (standard baseline)
  - `C`: 70% answer rate, 180s avg talk time (high answer rate, maximum predictive lift)
  - `D`: 45% answer rate, 130s avg talk time (changing conditions mid-point)

#### Example Run (Predictive Mode, Scenario C):
```bash
mvn compile exec:java -Dexec.mainClass="com.smartdialer.simulation.Simulation" -Dexec.args="predictive C"
```

#### Example Run (Progressive Baseline, Scenario B):
```bash
mvn compile exec:java -Dexec.mainClass="com.smartdialer.simulation.Simulation" -Dexec.args="progressive B"
```

---

### Step 4: Run the Scalability & Concurrency Load Test
Compare the scan-based (`AgentRegistry`) vs queue-hinted (`ScalableAgentRegistry`) agent reservation performance under 16 concurrent threads:
```bash
mvn compile exec:java -Dexec.mainClass="com.smartdialer.load.LoadTestHarness"
```
*Expected Output:*
```text
agents=100   | SCAN throughput=~2.49M/s p99=4us    | QUEUE throughput=~4.61M/s p99=0us
agents=1000  | SCAN throughput=~393k/s  p99=48us   | QUEUE throughput=~4.40M/s p99=0us
agents=10000 | SCAN throughput=~63.5k/s p99=5240us | QUEUE throughput=~4.76M/s p99=0us
```

---

### Step 5: Running in IDE (IntelliJ IDEA / VS Code / Eclipse)
You can also run classes directly from your IDE without Maven CLI:
1. Open the `smartdialer` directory in IntelliJ IDEA or VS Code.
2. Navigate to [`Simulation.java`](src/main/java/com/smartdialer/simulation/Simulation.java) or [`LoadTestHarness.java`](src/main/java/com/smartdialer/load/LoadTestHarness.java).
3. Right-click the `main` method and click **Run 'Simulation.main()'** or **Run 'LoadTestHarness.main()'**.
4. To pass arguments in IntelliJ: Edit Run Configuration $\to$ Program arguments $\to$ `predictive C`.

---

## Concurrency, Failure Modes & Distributed Design

### 1. Two Workers Racing on the Same Available Agent
- **Mechanism**: Agent reservation uses `agent.tryTransition(AgentStatus.AVAILABLE, AgentStatus.RESERVED)` backed by `AtomicReference.compareAndSet`.
- **Outcome**: Exactly one worker succeeds at the hardware CPU level. The losing worker receives `false`, releases any local claims via compensating rollback (`CallAllocator`), and either polls the next hint (in `ScalableAgentRegistry`) or requeues the job with exponential backoff.

### 2. Worker Thread Crash
- **Mechanism**: `DialerWorker.processJob` utilizes an explicit `registeredForAsyncCompletion` flag inside a `try-catch-finally` block.
- **Outcome**: If the thread crashes before registering with `CallLifecycleCoordinator`, the claim is guaranteed to be released in `finally`. Once registered, ownership transfers to the coordinator, and `ProgressiveDialer.submitResilientWorker` automatically respawns a replacement worker thread.

### 3. Telecom Provider Outage & Timeouts
- **Mechanism**: `StuckCallWatchdog` runs a periodic background sweep (`scheduleAtFixedRate`) checking active call durations against a 8,000ms threshold.
- **Outcome**: Calls orphaned by provider packet loss or dropped webhooks are force-cancelled via `applyEvent(CANCELLED)`, returning bound agents to `AVAILABLE` and releasing borrower claims.

### 4. Sudden Agent Availability Drops (Cliff Drop: 100 $\to$ 60 agents)
- **Mechanism**: `SnapshotAssembler` polls live agent statuses at every pacing tick.
- **Outcome**: The `SafetyController` instantly recalculates hard caps (`approvedCalls = min(recommended, availableAgents, maxRingingUnbound)`), dropping dispatch admissions to zero until ringing headroom recovers.

### 5. Duplicate and Out-of-Order Provider Webhooks
- **Mechanism**: `Call.applyEvent` validates transitions against an immutable whitelist (`ALLOWED_TRANSITIONS`). Terminal states (`COMPLETED`, `FAILED`, `CANCELLED`) have empty transition sets.
- **Outcome**: Duplicate webhooks (e.g., `ANSWERED` $\times 3$) and inverted events (e.g., `COMPLETED` followed by late `RINGING`) return `false` and are logged as benign info without mutating agent or call states.

---

## Scalability & Performance Benchmarking (100 $\to$ 10,000 Agents)

We evaluated thread contention and latency degradation under 16 concurrent workers using [`LoadTestHarness`](src/main/java/com/smartdialer/load/LoadTestHarness.java):

| Agent Count | Scan-Based Throughput | Scan-Based p99 Latency | Queue-Based Throughput | Queue-Based p99 Latency |
| :--- | :--- | :--- | :--- | :--- |
| **100** | ~2.49M ops/sec | 4 µs | ~4.61M ops/sec | < 1 µs |
| **1,000** | ~393k ops/sec | 48 µs | ~4.40M ops/sec | < 1 µs |
| **10,000** | ~63.5k ops/sec | **5,240 µs (5.24 ms)** | **~4.76M ops/sec** | **< 1 µs** |

- **The Bottleneck**: Scan-based registries (`AgentRegistry`) suffer an **~1,300x increase in p99 latency** due to super-linear $O(n)$ map walk contention.
- **The Solution**: [`ScalableAgentRegistry`](src/main/java/com/smartdialer/agent/ScalableAgentRegistry.java) uses a `ConcurrentLinkedQueue<String>` hint queue with atomic CAS verification, delivering flat sub-microsecond p99 latency across all pool sizes.

---

## Project Structure

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

## Testing Strategy

The codebase is validated through a four-layer testing pyramid:
1. **Unit Tests (State Machines & Concurrency)**: Verifies exact state transition validity, terminal-state immutability, and thread-safe lock-free CAS semantics (e.g. `AgentRegistryTest`, `ScalableAgentRegistryTest`, `CallAllocatorTest`).
2. **Integration Tests (Chaos & Survivability)**: Verifies that duplicate and out-of-order webhook events under real async scheduling never corrupt call states (e.g. `CallEventProcessorTest`, `DuplicateAndOutOfOrderEventScenarioTest`).
3. **End-to-End Tests (Campaign Draining & Safety)**: Verifies complete campaign draining, backoff requeue loops, safety budget clamping, and agent cliff-drop response latency (e.g. `ProgressiveDialerTest`, `PredictiveDialerTest`, `AgentCliffDropTest`, `SafetyControllerTest`).
4. **Load & Bottleneck Discovery**: Measures concurrency limits and latency degradation curves under high thread contention (e.g. `LoadTestHarness`).

## Design Docs

- [ARCHITECTURE.md](ARCHITECTURE.md): Detailed system pipeline architecture, dataflow graph, and state machine specifications.
- [ADR.md](ADR.md): Architecture Decision Records covering in-memory guarantees, CAS concurrency, claim ownership, isolation boundaries, and scalability fixes.
- [SIMULATION_REPORT.md](SIMULATION_REPORT.md): Scenario specifications, reproduction steps, empirical simulation data, and operational trade-off analysis.
