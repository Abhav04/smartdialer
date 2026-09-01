package com.smartdialer.provider;

import com.smartdialer.call.CallStatus;

public interface CallEventListener {
    void onEvent(String callId, CallStatus eventStatus);
}
