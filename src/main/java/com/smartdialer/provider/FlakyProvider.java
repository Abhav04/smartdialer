package com.smartdialer.provider;

import com.smartdialer.call.CallStatus;
import java.util.concurrent.*;

public class FlakyProvider implements TelecomProvider {

    private final ScheduledExecutorService scheduler;
    private final double failureRate;
    private final double timeoutRate;
    private final double duplicateEventRate;
    private final double outOfOrderRate;

    public FlakyProvider(ScheduledExecutorService scheduler, double failureRate,
                          double timeoutRate, double duplicateEventRate, double outOfOrderRate) {
        this.scheduler = scheduler;
        this.failureRate = failureRate;
        this.timeoutRate = timeoutRate;
        this.duplicateEventRate = duplicateEventRate;
        this.outOfOrderRate = outOfOrderRate;
    }

    @Override
    public void placeCall(String callId, String borrowerId, CallEventListener listener) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (rnd.nextDouble() < timeoutRate) {
            // Simulate a provider timeout: no events ever arrive for this call.
            // The system must detect and handle this via a reservation/call timeout
            // elsewhere (Phase 6/7) — the provider itself just goes silent.
            return;
        }

        boolean answered = rnd.nextDouble() > failureRate;
        long ringDelay = 200 + rnd.nextLong(400);

        emitPossiblyFlaky(callId, listener, CallStatus.RINGING, ringDelay);

        if (answered) {
            long answerDelay = ringDelay + 300 + rnd.nextLong(500);
            long connectDelay = answerDelay + 50;
            long completeDelay = connectDelay + 400 + rnd.nextLong(800);
            emitPossiblyFlaky(callId, listener, CallStatus.ANSWERED, answerDelay);
            emitPossiblyFlaky(callId, listener, CallStatus.CONNECTED, connectDelay);
            emitPossiblyFlaky(callId, listener, CallStatus.COMPLETED, completeDelay);
        } else {
            long failDelay = ringDelay + 300;
            emitPossiblyFlaky(callId, listener, CallStatus.FAILED, failDelay);
        }
    }

    private void emitPossiblyFlaky(String callId, CallEventListener listener,
                                    CallStatus status, long delayMillis) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        long actualDelay = delayMillis;
        if (rnd.nextDouble() < outOfOrderRate) {
            // Jitter the delay significantly, sometimes making a "later" event arrive
            // "earlier" than an event that was scheduled before it.
            actualDelay = Math.max(0, delayMillis - rnd.nextLong(600));
        }

        scheduler.schedule(() -> listener.onEvent(callId, status), actualDelay, TimeUnit.MILLISECONDS);

        if (rnd.nextDouble() < duplicateEventRate) {
            long duplicateDelay = actualDelay + 10 + rnd.nextLong(100);
            scheduler.schedule(() -> listener.onEvent(callId, status), duplicateDelay, TimeUnit.MILLISECONDS);
        }
    }
}