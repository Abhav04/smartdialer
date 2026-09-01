package com.smartdialer.provider;

import com.smartdialer.call.CallStatus;
import java.util.concurrent.*;

public class ReliableProvider implements TelecomProvider {

    private final ScheduledExecutorService scheduler;
    private final double failureRate;

    public ReliableProvider(ScheduledExecutorService scheduler, double failureRate) {
        this.scheduler = scheduler;
        this.failureRate = failureRate;
    }

    @Override
    public void placeCall(String callId, String borrowerId, CallEventListener listener) {
        scheduleEvent(callId, listener, CallStatus.RINGING, 50);

        boolean answered = ThreadLocalRandom.current().nextDouble() > failureRate;
        if (answered) {
            scheduleEvent(callId, listener, CallStatus.ANSWERED, 150);
            scheduleEvent(callId, listener, CallStatus.CONNECTED, 160);
            scheduleEvent(callId, listener, CallStatus.COMPLETED, 300);
        } else {
            scheduleEvent(callId, listener, CallStatus.FAILED, 150);
        }
    }

    private void scheduleEvent(String callId, CallEventListener listener,
                                CallStatus status, long delayMillis) {
        scheduler.schedule(() -> listener.onEvent(callId, status),
            delayMillis, TimeUnit.MILLISECONDS);
    }
}
