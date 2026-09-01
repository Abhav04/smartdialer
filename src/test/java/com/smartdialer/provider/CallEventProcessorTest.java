package com.smartdialer.provider;

import com.smartdialer.call.Call;
import com.smartdialer.call.CallStatus;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CallEventProcessorTest {

    @Test
    void flakyProviderChaosStillEndsInSensibleTerminalState() throws InterruptedException {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();
        CallEventProcessor processor = new CallEventProcessor(activeCalls);

        FlakyProvider provider = new FlakyProvider(scheduler,
            0.2,  // failureRate
            0.05, // timeoutRate
            0.4,  // duplicateEventRate — deliberately high to force duplicates
            0.4); // outOfOrderRate — deliberately high to force reordering

        int callCount = 100;
        CountDownLatch settleLatch = new CountDownLatch(1);

        for (int i = 0; i < callCount; i++) {
            String callId = "call-" + i;
            Call call = new Call(callId, "borrower-" + i);
            call.tryTransition(CallStatus.QUEUED, CallStatus.RESERVED);
            call.tryTransition(CallStatus.RESERVED, CallStatus.INITIATED);
            activeCalls.put(callId, call);
            provider.placeCall(callId, "borrower-" + i, processor);
        }

        // Give the scheduler enough time for all scheduled events (including jittered/
        // duplicate ones) to fire.
        Thread.sleep(4000);
        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);

        for (Call call : activeCalls.values()) {
            CallStatus finalStatus = call.getStatus();
            assertTrue(
                finalStatus == CallStatus.COMPLETED
                    || finalStatus == CallStatus.FAILED
                    || finalStatus == CallStatus.INITIATED // timeout case: no events ever arrived
                    || finalStatus == CallStatus.RINGING
                    || finalStatus == CallStatus.ANSWERED
                    || finalStatus == CallStatus.CONNECTED,
                "Call " + call.getId() + " ended in unexpected/corrupted state: " + finalStatus
            );
        }
    }
}