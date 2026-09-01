package com.smartdialer.dialer;

import com.smartdialer.agent.Agent;
import com.smartdialer.call.Call;

public record ActiveCallContext(Call call, Agent agent, String borrowerId) {
}
