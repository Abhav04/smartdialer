package com.smartdialer.provider;

public interface TelecomProvider {
    void placeCall(String callId, String borrowerId, CallEventListener listener);
}
