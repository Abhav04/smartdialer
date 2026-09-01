package com.smartdialer.provider;

import com.smartdialer.call.Call;
import com.smartdialer.call.CallStatus;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class CallEventProcessor implements CallEventListener {

    private static final Logger log = Logger.getLogger(CallEventProcessor.class.getName());

    private final ConcurrentHashMap<String, Call> activeCalls;

    public CallEventProcessor(ConcurrentHashMap<String, Call> activeCalls) {
        this.activeCalls = activeCalls;
    }

    @Override
    public void onEvent(String callId, CallStatus eventStatus) {
        Call call = activeCalls.get(callId);
        if (call == null) {
            log.warning("Received event " + eventStatus + " for unknown/expired call " + callId);
            return;
        }

        boolean applied = call.applyEvent(eventStatus);
        if (!applied) {
            log.info("Rejected event " + eventStatus + " for call " + callId +
                " — current status is " + call.getStatus() + " (likely duplicate or out-of-order)");
        }
    }
}