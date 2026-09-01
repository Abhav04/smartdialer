# Architecture Decision Record

## ADR-1: In-Memory ConcurrentHashMap as Single Source of Truth
- **Context:** Distributed caching and database sync layers introduce split-brain risks, cache-vs-DB inconsistency, and latency variance during tight telecom pacing loops.
- **Decision:** State lives purely in-memory using dedicated `ConcurrentHashMap` registries as the single source of truth without external databases, Redis, or message brokers.
- **Consequences:** Eliminates distributed state synchronization bugs and I/O bottlenecks. Complete process termination results in state loss by design, which is an accepted constraint of this standalone prototype.

## ADR-2: Lock-Free CAS State Machines via AtomicReference
- **Context:** Coarse-grained manual locks or `synchronized` blocks create thread contention bottlenecks and deadlock risks under high concurrent worker and webhook traffic.
- **Decision:** All domain state transitions (`AgentStatus`, `CallStatus`) are executed through `java.util.concurrent.atomic.AtomicReference` using atomic `compareAndSet` (CAS) loops against immutable `Map<State, Set<State>>` transition tables.
- **Consequences:** Transitions are atomic, lock-free, and non-blocking. Illegal transitions fail fast without throwing exceptions or silently mutating state.

## ADR-3: Compensating-Action Rollback for Coupled Agent/Call Allocation
- **Context:** Reserving an agent and transitioning a call in progressive mode involves coordinating two distinct state machines without distributed multi-entity transactions.
- **Decision:** `CallAllocator` reserves the agent first via CAS; if transitioning the call subsequently fails, it immediately triggers a compensating rollback (`RESERVED -> AVAILABLE` on the agent). If rollback fails, it throws `IllegalStateException` rather than leaving corrupted state.
- **Consequences:** Guarantees atomicity of the combined reservation. Fails loudly on state inconsistency.

## ADR-4: Two-Tier Deduplication (Queue Job Exclusivity + Lifetime Borrower Claim)
- **Context:** Multiple campaign jobs or rapid duplicate enqueues for the same borrower can result in multiple simultaneous calls to the same person.
- **Decision:** Implemented two tiers of defense: queue-level atomic polling (`BlockingQueue.poll()`) and borrower-level locking via `BorrowerClaimRegistry` (`ConcurrentHashMap<String, AtomicBoolean>`). The claim is acquired before allocation and held for the entire lifecycle of the call.
- **Consequences:** Completely prevents duplicate concurrent calls to the same borrower. The claim is released only when the call reaches a terminal state.

## ADR-5: Compile-Time Structural Isolation for Predictive Pacing Engine
- **Context:** Predictive pacing algorithms can inadvertently bypass safety checks if they have direct access to dispatch calls or mutate agent states.
- **Decision:** `PredictivePacingEngine` has zero imports and zero compile-time references to `com.smartdialer.allocation`, `provider`, `worker`, or `dialer` packages. It accepts only an immutable `SystemSnapshot` and returns an inert `PacingRecommendation`.
- **Consequences:** Pacing calculations cannot physically initiate calls. All recommendations must pass through `SafetyController` for hard-cap re-evaluation before call dispatch.

## ADR-6: Deferred Agent Binding in Predictive Mode (RESERVED -> CONNECTED)
- **Context:** In predictive dialing, calls are placed before agents are assigned. Agents are bound only when a call is answered by the borrower.
- **Decision:** Added a deliberate direct transition `RESERVED -> CONNECTED` to `AgentStatus.ALLOWED_TRANSITIONS` representing an agent binding to an already-answered call without passing through `DIALING`.
- **Consequences:** Clearly distinguishes progressive mode's "agent actively dialing" from predictive mode's deferred binding.

## ADR-7: Fail-Fast Compliance Disconnect Path for Unmatched Answered Calls
- **Context:** In predictive mode, probabilistic overdialing can occasionally result in a call being answered when no agent is available in the pool.
- **Decision:** When an `ANSWERED` event arrives and `agentRegistry.reserveAnyAvailable()` returns empty, the system immediately logs a `SEVERE` audit log containing the literal tag `"COMPLIANCE"`, increments a dedicated abandoned-call metric, force-cancels the call via `applyEvent(CANCELLED)`, and releases the borrower claim.
- **Consequences:** Prevents answered calls from being left in dead limbo or dropped silently. Acknowledges real-world compliance constraints openly.

## ADR-8: Claim Ownership-Transfer Flag for Worker-Crash Resilience
- **Context:** If a worker thread crashes or an unexpected exception occurs during job processing, the borrower claim could remain locked forever.
- **Decision:** `DialerWorker.processJob` utilizes an explicit `registeredForAsyncCompletion` boolean flag inside a `try-catch-finally` block. If registration with `CallLifecycleCoordinator` does not complete, the claim is guaranteed to be released in `finally`. Once registered, ownership transfers to the coordinator.
- **Consequences:** Exactly one component owns responsibility for claim release at all times, preventing orphaned claims.

## ADR-9: Queue-Hinted Availability Index (ScalableAgentRegistry) for High Agent Concurrency
- **Context:** As agent pools grow to 10,000 agents, scanning `ConcurrentHashMap.values()` on every reservation causes $O(n)$ map walks and heavy thread contention.
- **Decision:** Created `ScalableAgentRegistry` with a `ConcurrentLinkedQueue<String>` availability index acting as a fast $O(1)$ hint queue. Hints are validated against the `Agent` using CAS (`AVAILABLE -> RESERVED`), and stale hints are skipped in a loop.
- **Measured Results:**
  - **100 agents:** Scan throughput = ~2.49M ops/sec (p99 = 4µs) vs Queue throughput = ~4.61M ops/sec (p99 < 1µs).
  - **1,000 agents:** Scan throughput = ~393k ops/sec (p99 = 48µs) vs Queue throughput = ~4.40M ops/sec (p99 < 1µs).
  - **10,000 agents:** Scan throughput collapsed to ~63.5k ops/sec (39x drop) with p99 surging to 5,240µs (5.24ms, an ~1,300x increase). Queue throughput held flat at ~4.76M ops/sec with p99 under 1µs across all scales.
- **Consequences:** Contention degradation is super-linear due to compounding map segment contention under concurrent scans. Queue hints eliminate this overhead while preserving exact double-booking safety. Traded for the discipline of requiring callers to trigger `markAvailable()`.

## What I'd do differently with another week
1. **Dynamic / Adaptive Planning Horizon:** Replace the fixed 10-second planning horizon with a duration-scaled window (`horizon = max(5.0, avgCallDurationSeconds * 0.2)`). In scenarios with long call durations (e.g. Scenarios A & D with 120s+ calls), the fixed 10s horizon artificially suppressed projected capacity (`fractionFreeing ≈ 0.08`), leading to wild over-suggestion against tiny capacity and forcing the `SafetyController` to clamp 100% of ticks.
2. **Event-Driven Hybrid Dispatch (Eliminating Inter-Tick Latency):** In addition to periodic scheduled ticks (500ms), trigger immediate opportunistic dispatch whenever an agent transitions to `AVAILABLE` (from `WRAP_UP` or `OFFLINE`). This eliminates the idle inter-tick latency gap that caused PredictiveDialer to underperform ProgressiveDialer's continuous 200ms worker poll loops in Scenarios A & D.
3. **Exponentially-Weighted Moving Average (EWMA) Metrics:** Replace the fixed 50-item sliding window in `RollingMetrics` with time-decayed EWMA to adapt faster to sudden shifts in answer rates or agent disconnect cliffs.
4. **Per-Call Timeout Tasks:** Transition from periodic `StuckCallWatchdog` sweeps to individual scheduled timeout tasks per call (`scheduler.schedule(...)`) once call volume justifies fine-grained task management overhead.
5. **Time-Varying Scenario Simulation:** Implement a dynamic non-stationary Markov-chain simulation model for Scenario D where answer rates and call durations shift dynamically over the campaign lifecycle.
6. **Append-Only Write-Ahead Event Log:** Add an in-memory or memory-mapped append-only event log to support deterministic state machine reconstruction across process crashes.
7. **Universal Scalable Registry Integration:** Audit all `AVAILABLE` transition call sites across coordinators to wire `ScalableAgentRegistry` as the universal default registry across both dialers.
