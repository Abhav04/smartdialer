# Architecture

SmartDialer operates as an in-memory, event-driven pipeline designed for progressive and predictive telecom dialing: `Campaign -> Pacing Engine (Progressive/Predictive) -> Safety Controller -> Call Allocator -> Telecom Provider`. In this architecture, the `Safety Controller` acts as a non-bypassable enforcement boundary: the `Predictive Pacing Engine` has zero compile-time dependencies on the allocation, provider, worker, or dialer packages, and produces purely advisory capacity recommendations. The `Safety Controller` independently recalculates hard limits from live system snapshots (agent availability, ringing headroom, and sustained provider degradation) before any call is permitted to dispatch.

![System Pipeline Architecture](docs/images/architecture_pipeline.png)

```mermaid
graph LR
    Campaign[Campaign / Borrower List] --> Queue[Job Queue]
    Queue --> Worker1[Dialer Worker 1..N]
    Worker1 --> Claim{Borrower<br/>Claim Guard}
    Claim -->|Progressive| Allocator[Call Allocator]
    Claim -->|Predictive| Pacing[Pacing Engine]
    Pacing --> Safety[Safety Controller]
    Safety --> Allocator
    Allocator --> AgentReg[Agent Registry]
    Allocator --> Provider[Telecom Provider Interface]
    Provider --> ReliableP[Reliable Provider]
    Provider --> FlakyP[Flaky Provider]
    Provider -.events.-> Coordinator[Call Lifecycle Coordinator]
    Coordinator --> AgentReg
    Coordinator --> Metrics[Rolling Metrics]
    Metrics --> Pacing
```

## Agent State Machine

The agent lifecycle is managed via lock-free atomic compare-and-set operations governed by an explicit immutable transition table. In progressive mode, an agent is reserved and transitions to `DIALING` before the telecom call is initiated. In predictive mode, deferred binding is used: an agent transitions directly from `RESERVED` to `CONNECTED` when an in-flight call is answered by the borrower.

![Agent State Machine Diagram](docs/images/agent_state_machine.png)

```mermaid
stateDiagram-v2
    [*] --> OFFLINE
    OFFLINE --> AVAILABLE
    AVAILABLE --> RESERVED
    AVAILABLE --> PAUSED
    AVAILABLE --> OFFLINE
    RESERVED --> DIALING
    RESERVED --> CONNECTED : predictive mode only
    RESERVED --> AVAILABLE
    RESERVED --> OFFLINE
    DIALING --> CONNECTED
    DIALING --> AVAILABLE
    DIALING --> OFFLINE
    CONNECTED --> WRAP_UP
    CONNECTED --> OFFLINE
    WRAP_UP --> AVAILABLE
    WRAP_UP --> OFFLINE
    PAUSED --> AVAILABLE
    PAUSED --> OFFLINE
```

## Call State Machine

Calls progress through explicit operational states driven by asynchronous telecom provider callbacks. Terminal states (`COMPLETED`, `FAILED`, `CANCELLED`) have empty allowed-transition sets, ensuring natural idempotency against duplicate or out-of-order webhook delivery.

```mermaid
stateDiagram-v2
    [*] --> QUEUED
    QUEUED --> RESERVED
    QUEUED --> CANCELLED
    RESERVED --> INITIATED
    RESERVED --> CANCELLED
    INITIATED --> RINGING
    INITIATED --> FAILED
    INITIATED --> CANCELLED
    RINGING --> ANSWERED
    RINGING --> FAILED
    RINGING --> CANCELLED
    ANSWERED --> CONNECTED
    ANSWERED --> FAILED
    ANSWERED --> CANCELLED
    CONNECTED --> COMPLETED
    CONNECTED --> CANCELLED
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```
