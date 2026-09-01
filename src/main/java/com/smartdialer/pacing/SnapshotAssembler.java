package com.smartdialer.pacing;

import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.metrics.RollingMetrics;

public class SnapshotAssembler {

    private final AgentRegistry agentRegistry;
    private final RollingMetrics metrics;

    public SnapshotAssembler(AgentRegistry agentRegistry, RollingMetrics metrics) {
        this.agentRegistry = agentRegistry;
        this.metrics = metrics;
    }

    public SystemSnapshot assemble(int inFlightCalls) {
        int available = agentRegistry.countByStatus(AgentStatus.AVAILABLE);
        int connected = agentRegistry.countByStatus(AgentStatus.CONNECTED);
        int dialing = agentRegistry.countByStatus(AgentStatus.DIALING);

        return new SystemSnapshot(
            available,
            connected,
            dialing,
            inFlightCalls,
            metrics.getRecentAnswerRate(),
            metrics.getAvgCallDurationSeconds(),
            5.0, // avgSetupTimeSeconds — fixed assumption, matches ReliableProvider/FlakyProvider ring delay ballpark
            metrics.getProviderFailureRate()
        );
    }
}
