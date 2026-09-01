package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.call.Call;
import java.util.concurrent.atomic.AtomicReference;

public class PredictiveCallContext {
    private final Call call;
    private final String borrowerId;
    private final long dialStartNanos;
    // Deliberately mutable (via AtomicReference/CAS) unlike other correlation records in this codebase,
    // because which agent (if any) binds to this call is unknown at creation time and only resolved later
    // when/if the call is answered.
    private final AtomicReference<Agent> boundAgent = new AtomicReference<>();

    public PredictiveCallContext(Call call, String borrowerId) {
        this.call = call;
        this.borrowerId = borrowerId;
        this.dialStartNanos = System.nanoTime();
    }

    public Call call() { return call; }
    public String borrowerId() { return borrowerId; }
    public double elapsedSeconds() { return (System.nanoTime() - dialStartNanos) / 1_000_000_000.0; }

    public boolean tryBindAgent(Agent agent) {
        return boundAgent.compareAndSet(null, agent);
    }

    public Agent getBoundAgent() {
        return boundAgent.get();
    }
}
