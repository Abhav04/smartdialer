package com.smartdialer.load;

import com.smartdialer.agent.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class LoadTestHarness {

    public record LoadTestResult(
        int agentCount, int threadCount, long totalAttempts,
        long successfulReservations, long durationMillis,
        double throughputPerSecond, long p50Nanos, long p99Nanos, long maxNanos
    ) {}

    public static LoadTestResult runAgainstScanBasedRegistry(int agentCount, int threadCount,
                                                               long durationMillis) throws InterruptedException {
        AgentRegistry registry = new AgentRegistry();
        seedAgents(agent -> {
            registry.register(agent);
            agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
        }, agentCount);
        return runLoad(registry::reserveAnyAvailable, threadCount, durationMillis, agentCount);
    }

    public static LoadTestResult runAgainstQueueBasedRegistry(int agentCount, int threadCount,
                                                                long durationMillis) throws InterruptedException {
        ScalableAgentRegistry registry = new ScalableAgentRegistry();
        seedAgents(agent -> {
            registry.register(agent);
            agent.tryTransition(AgentStatus.OFFLINE, AgentStatus.AVAILABLE);
            registry.markAvailable(agent.getId());
        }, agentCount);
        return runLoad(registry::reserveAnyAvailable, threadCount, durationMillis, agentCount);
    }

    private static void seedAgents(Consumer<Agent> registrar, int agentCount) {
        for (int i = 0; i < agentCount; i++) {
            Agent agent = new Agent("agent-" + i);
            registrar.accept(agent);
        }
    }

    private static LoadTestResult runLoad(Supplier<Optional<Agent>> reserveOp,
                                            int threadCount, long durationMillis, int agentCount)
            throws InterruptedException {

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicLong totalAttempts = new AtomicLong();
        AtomicLong successfulReservations = new AtomicLong();
        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        long deadline = System.currentTimeMillis() + durationMillis;

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    while (System.currentTimeMillis() < deadline) {
                        long start = System.nanoTime();
                        Optional<Agent> result = reserveOp.get();
                        long elapsed = System.nanoTime() - start;
                        latenciesNanos.add(elapsed);
                        totalAttempts.incrementAndGet();
                        if (result.isPresent()) successfulReservations.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        long testStart = System.currentTimeMillis();
        startGate.countDown();
        for (Future<?> f : futures) {
            try { f.get(durationMillis + 5000, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        }
        long actualDuration = System.currentTimeMillis() - testStart;
        pool.shutdown();

        List<Long> sorted = new ArrayList<>(latenciesNanos);
        Collections.sort(sorted);
        long p50 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.50));
        long p99 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.99));
        long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

        double throughput = totalAttempts.get() / (actualDuration / 1000.0);

        return new LoadTestResult(agentCount, threadCount, totalAttempts.get(),
            successfulReservations.get(), actualDuration, throughput, p50, p99, max);
    }

    public static void main(String[] args) throws InterruptedException {
        for (int agentCount : new int[]{100, 1_000, 10_000}) {
            var scanResult = LoadTestHarness.runAgainstScanBasedRegistry(agentCount, 16, 3000);
            var queueResult = LoadTestHarness.runAgainstQueueBasedRegistry(agentCount, 16, 3000);
            System.out.println("agents=" + agentCount +
                " | SCAN throughput=" + scanResult.throughputPerSecond() + "/s p99=" + (scanResult.p99Nanos() / 1000) + "us" +
                " | QUEUE throughput=" + queueResult.throughputPerSecond() + "/s p99=" + (queueResult.p99Nanos() / 1000) + "us");
        }
    }
}
