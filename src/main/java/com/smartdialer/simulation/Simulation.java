package com.smartdialer.simulation;

import com.smartdialer.agent.Agent;
import com.smartdialer.agent.AgentRegistry;
import com.smartdialer.agent.AgentStatus;
import com.smartdialer.dialer.PredictiveDialer;
import com.smartdialer.dialer.ProgressiveDialer;
import com.smartdialer.pacing.SafetyOutcome;
import com.smartdialer.provider.FlakyProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.IntStream;

public class Simulation {

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length > 0 ? args[0] : "predictive";
        String scenario = args.length > 1 ? args[1] : "B";
        runScenario(mode, scenario);
    }

    private static void runScenario(String mode, String scenario) throws InterruptedException {
        ScenarioConfig config = ScenarioConfig.forLetter(scenario);
        System.out.println("Running scenario " + scenario + " (" + mode + " mode): answerRate=" +
            config.answerRate() + " avgTalkTimeSec=" + config.avgTalkTimeSec());

        AgentRegistry agentRegistry = new AgentRegistry();
        for (int i = 0; i < 20; i++) {
            Agent agent = new Agent("agent-" + i);
            agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
            agentRegistry.register(agent);
        }

        ScheduledExecutorService providerScheduler = Executors.newScheduledThreadPool(6);
        FlakyProvider provider = new FlakyProvider(
            providerScheduler,
            1.0 - config.answerRate(),
            0.03, // timeoutRate
            0.15, // duplicateEventRate
            0.15  // outOfOrderRate
        );

        List<String> borrowers = IntStream.range(0, 100)
            .mapToObj(i -> "borrower-" + i)
            .toList();

        if (mode.equalsIgnoreCase("progressive")) {
            ProgressiveDialer dialer = new ProgressiveDialer(
                agentRegistry, provider, 20, 200, 300);
            dialer.submitCampaign(borrowers);
            monitorProgressive(dialer, agentRegistry);
            dialer.shutdown();
        } else {
            PredictiveDialer dialer = new PredictiveDialer(
                agentRegistry, provider, 0.4, 25, 300, 500);
            dialer.submitCampaign(borrowers);
            monitorPredictive(dialer, agentRegistry);
            dialer.shutdown();
        }

        providerScheduler.shutdown();
    }

    private static void monitorProgressive(ProgressiveDialer dialer, AgentRegistry registry) throws InterruptedException {
        List<Integer> busySamples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);
            int available = registry.countByStatus(AgentStatus.AVAILABLE);
            busySamples.add(20 - available);
            System.out.println("[t=" + i + "s] active=" + dialer.activeCallCount() +
                " available=" + available);
            if (dialer.activeCallCount() == 0 && i > 2) {
                break;
            }
        }
        double avgUtil = busySamples.isEmpty() ? 0.0 :
            (busySamples.stream().mapToInt(Integer::intValue).average().orElse(0.0) / 20.0) * 100.0;
        System.out.println("PROGRESSIVE_SUMMARY: Abandoned=0 AvgUtilization=" + String.format("%.1f%%", avgUtil));
    }

    private static void monitorPredictive(PredictiveDialer dialer, AgentRegistry registry) throws InterruptedException {
        List<Integer> busySamples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);
            int available = registry.countByStatus(AgentStatus.AVAILABLE);
            busySamples.add(20 - available);
            var decisionLog = dialer.getDecisionLog();
            String lastDecision = decisionLog.isEmpty() ? "none" :
                decisionLog.get(decisionLog.size() - 1).outcome().toString();

            System.out.println("[t=" + i + "s] active=" + dialer.activeCallCount() +
                " available=" + available +
                " abandoned=" + dialer.getMetrics().getAbandonedCallCount() +
                " lastDecision=" + lastDecision);

            if (dialer.activeCallCount() == 0 && i > 5) {
                break;
            }
        }

        var decisionLog = dialer.getDecisionLog();
        long approved = decisionLog.stream().filter(d -> d.outcome() == SafetyOutcome.APPROVED).count();
        long reduced = decisionLog.stream().filter(d -> d.outcome() == SafetyOutcome.REDUCED).count();
        long rejected = decisionLog.stream().filter(d -> d.outcome() == SafetyOutcome.REJECTED).count();
        long fallback = decisionLog.stream().filter(d -> d.outcome() == SafetyOutcome.FALLBACK_TO_PROGRESSIVE).count();
        double avgUtil = busySamples.isEmpty() ? 0.0 :
            (busySamples.stream().mapToInt(Integer::intValue).average().orElse(0.0) / 20.0) * 100.0;

        System.out.println("Final abandoned call count: " + dialer.getMetrics().getAbandonedCallCount());
        System.out.println("Total safety decisions logged: " + decisionLog.size());
        System.out.println("PREDICTIVE_SUMMARY: Abandoned=" + dialer.getMetrics().getAbandonedCallCount() +
            " AvgUtilization=" + String.format("%.1f%%", avgUtil) +
            " Decisions=[Approved=" + approved + ", Reduced=" + reduced + ", Rejected=" + rejected + ", Fallback=" + fallback + "]");
    }
}
