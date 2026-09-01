package com.smartdialer.call;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateAndOutOfOrderEventScenarioTest {

    @Test
    void exactDuplicateSequenceFromBriefProducesOnlyOneRealTransition() {
        Call call = new Call("call-x", "borrower-x");
        call.tryTransition(CallStatus.QUEUED, CallStatus.RESERVED);
        call.tryTransition(CallStatus.RESERVED, CallStatus.INITIATED);
        call.tryTransition(CallStatus.INITIATED, CallStatus.RINGING);

        // Exact sequence from the assignment brief: ANSWERED, ANSWERED, ANSWERED, COMPLETED
        boolean e1 = call.applyEvent(CallStatus.ANSWERED);
        boolean e2 = call.applyEvent(CallStatus.ANSWERED);
        boolean e3 = call.applyEvent(CallStatus.ANSWERED);
        // Note: real flow needs CONNECTED between ANSWERED and COMPLETED per our table
        call.applyEvent(CallStatus.CONNECTED);
        boolean e4 = call.applyEvent(CallStatus.COMPLETED);

        assertTrue(e1, "First ANSWERED should apply");
        assertFalse(e2, "Second ANSWERED should be rejected — already ANSWERED");
        assertFalse(e3, "Third ANSWERED should be rejected — already ANSWERED");
        assertTrue(e4, "COMPLETED should apply from CONNECTED");
        assertEquals(CallStatus.COMPLETED, call.getStatus());
    }

    @Test
    void exactOutOfOrderSequenceFromBriefIsRejectedNotCorrupted() {
        Call call = new Call("call-y", "borrower-y");
        call.tryTransition(CallStatus.QUEUED, CallStatus.RESERVED);
        call.tryTransition(CallStatus.RESERVED, CallStatus.INITIATED);

        // Exact sequence from the brief: COMPLETED, ANSWERED, RINGING — while call is only INITIATED
        boolean completedFirst = call.applyEvent(CallStatus.COMPLETED);
        boolean answeredSecond = call.applyEvent(CallStatus.ANSWERED);
        boolean ringingThird = call.applyEvent(CallStatus.RINGING);

        assertFalse(completedFirst, "COMPLETED is illegal from INITIATED — must be rejected");
        assertFalse(answeredSecond, "ANSWERED is illegal from INITIATED — must be rejected");
        assertTrue(ringingThird, "RINGING IS legal from INITIATED — this one should apply");
        assertEquals(CallStatus.RINGING, call.getStatus(),
            "Call should end up RINGING — the only legal transition in this out-of-order burst");
    }
}
