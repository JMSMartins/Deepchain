package deepchainbench.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public abstract class TelemetryEngine implements Runnable {

    /**
     * Intervalo de amostragem default comprovado na literatura (250 ms = 4 Hz).
     * Referências: Garcia et al. (2023, SPBench, Springer Computing, DOI: 10.1007/s00607-021-01025-6)
     * e janela ótima de 200-300 ms em Chen et al. (2025, Reveal, arXiv:2510.26008).
     */
    public static final long DEFAULT_SAMPLING_INTERVAL_MS = 250L;

    /**
     * Intervalo mínimo comprovado na literatura sem introduzir ruído excessivo / Observer Effect (100 ms = 10 Hz).
     * Referências: Li et al. (2026, RAGPerf, arXiv:2603.10765), Chen et al. (2025, Reveal, arXiv:2510.26008),
     * Linux perf-stat(1) ("sub 100ms intervals") e Google Perfetto (proc_stats_poll_ms > 100ms).
     */
    public static final long MIN_SAMPLING_INTERVAL_MS = 100L;

    /**
     * Snapshot de uma amostra de telemetria recolhida pelo observador.
     */
    public static class TelemetrySample {
        public final long timestampMs;
        public final double cpuGlobalPct;
        public final double ramUsedMB;
        public final double cpuIoWaitPct;
        public final double swapUsedMB;
        public final double dirtyMemMB;
        public final double contextSwitchesPerSec;
        public final int procsRunning;
        public final double majorPageFaultsPerSec;
        public final long majorPageFaultsDelta;

        public TelemetrySample(long timestampMs, double cpuGlobalPct, double ramUsedMB,
                               double cpuIoWaitPct, double swapUsedMB, double dirtyMemMB,
                               double contextSwitchesPerSec, int procsRunning,
                               double majorPageFaultsPerSec, long majorPageFaultsDelta) {
            this.timestampMs = timestampMs;
            this.cpuGlobalPct = cpuGlobalPct;
            this.ramUsedMB = ramUsedMB;
            this.cpuIoWaitPct = cpuIoWaitPct;
            this.swapUsedMB = swapUsedMB;
            this.dirtyMemMB = dirtyMemMB;
            this.contextSwitchesPerSec = contextSwitchesPerSec;
            this.procsRunning = procsRunning;
            this.majorPageFaultsPerSec = majorPageFaultsPerSec;
            this.majorPageFaultsDelta = majorPageFaultsDelta;
        }
    }

    public static class MemorySnapshot {
        public final double ramUsedMB;
        public final double swapUsedMB;
        public final double dirtyMemMB;

        public MemorySnapshot(double ramUsedMB, double swapUsedMB, double dirtyMemMB) {
            this.ramUsedMB = ramUsedMB;
            this.swapUsedMB = swapUsedMB;
            this.dirtyMemMB = dirtyMemMB;
        }
    }

    protected static class ProcStatSnapshot {
        final long totalTicks;
        final long idleAndIoWaitTicks;
        final long ioWaitTicks;
        final long contextSwitches;
        final int procsRunning;

        ProcStatSnapshot(long totalTicks, long idleAndIoWaitTicks, long ioWaitTicks,
                         long contextSwitches, int procsRunning) {
            this.totalTicks = totalTicks;
            this.idleAndIoWaitTicks = idleAndIoWaitTicks;
            this.ioWaitTicks = ioWaitTicks;
            this.contextSwitches = contextSwitches;
            this.procsRunning = procsRunning;
        }
    }

    protected volatile boolean running = true;
    protected String outputFile;
    protected final long samplingIntervalMs;
    protected final List<TelemetrySample> samples = Collections.synchronizedList(new ArrayList<>());

    public TelemetryEngine(String outputFile) {
        this(outputFile, DEFAULT_SAMPLING_INTERVAL_MS);
    }

    public TelemetryEngine(String outputFile, long samplingIntervalMs) {
        this.outputFile = outputFile;
        this.samplingIntervalMs = validateSamplingInterval(samplingIntervalMs);
    }

    /**
     * Valida o intervalo de amostragem garantindo que nunca é inferior ao mínimo científico comprovado (100 ms).
     */
    public static long validateSamplingInterval(long intervalMs) {
        if (intervalMs < MIN_SAMPLING_INTERVAL_MS) {
            return MIN_SAMPLING_INTERVAL_MS;
        }
        return intervalMs;
    }

    public long getSamplingIntervalMs() {
        return this.samplingIntervalMs;
    }

    public List<TelemetrySample> getSamplesSnapshot() {
        synchronized (samples) {
            return new ArrayList<>(samples);
        }
    }

    // MÉTODO ABSTRATO: Cada classe implementa a sua forma de ler a RAM
    public abstract double getSystemRamUsedMB();

    /**
     * Permite às subclasses devolverem RAM, Swap e Dirty pages numa única leitura de /proc/meminfo.
     */
    public MemorySnapshot getMemorySnapshot() {
        return new MemorySnapshot(getSystemRamUsedMB(), 0.0, 0.0);
    }

    // Leitura dos ticks do CPU nativo do Linux (mantida para compatibilidade)
    protected long[] getCpuTicks() {
        ProcStatSnapshot snap = getProcStatSnapshot();
        return new long[]{snap.totalTicks, snap.idleAndIoWaitTicks};
    }

    // Leitura enriquecida de /proc/stat: CPU ticks, iowait, context switches e procs_running
    protected ProcStatSnapshot getProcStatSnapshot() {
        long total = 0, idleAndIo = 0, ioWait = 0, ctxt = 0;
        int procsRunning = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String firstLine = br.readLine();
            if (firstLine != null) {
                String[] parts = firstLine.trim().split("\\s+");
                int maxIdx = Math.min(parts.length - 1, 8);
                for (int i = 1; i <= maxIdx; i++) {
                    total += Long.parseLong(parts[i]);
                }
                long idle = parts.length > 4 ? Long.parseLong(parts[4]) : 0;
                ioWait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
                idleAndIo = idle + ioWait;
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("ctxt ")) {
                    ctxt = Long.parseLong(line.split("\\s+")[1]);
                } else if (line.startsWith("procs_running ")) {
                    procsRunning = Integer.parseInt(line.split("\\s+")[1]);
                    break;
                }
            }
        } catch (Exception e) {
            return new ProcStatSnapshot(0, 0, 0, 0, 0);
        }
        return new ProcStatSnapshot(total, idleAndIo, ioWait, ctxt, procsRunning);
    }

    // Leitura cumulativa de major page faults (pgmajfault) em /proc/vmstat
    protected long getMajorPageFaults() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/vmstat"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("pgmajfault ")) {
                    return Long.parseLong(line.split("\\s+")[1]);
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    @Override
    public void run() {
        try (FileWriter fw = new FileWriter(outputFile)) {
            fw.append("Timestamp,CPU_Global_Pct,RAM_Consumida_MB,CPU_IOWait_Pct,Swap_Usada_MB,Dirty_Mem_MB,Context_Switches_Sec,Procs_Running,Major_Page_Faults_Sec\n");
            ProcStatSnapshot statA = getProcStatSnapshot();
            long majFaultsA = getMajorPageFaults();
            long prevTs = System.currentTimeMillis();

            while (running) {
                // Resolução configurável (default 250ms, mínimo comprovado 100ms) para evitar Observer Effect
                Thread.sleep(this.samplingIntervalMs);

                long nowTs = System.currentTimeMillis();
                double dtSec = Math.max(0.001, (nowTs - prevTs) / 1000.0);

                ProcStatSnapshot statB = getProcStatSnapshot();
                long majFaultsB = getMajorPageFaults();

                long diffTotal = statB.totalTicks - statA.totalTicks;
                long diffIdle = statB.idleAndIoWaitTicks - statA.idleAndIoWaitTicks;
                long diffIoWait = statB.ioWaitTicks - statA.ioWaitTicks;

                double cpuPct = diffTotal > 0 ? Math.max(0.0, Math.min(100.0, (1.0 - ((double) diffIdle / diffTotal)) * 100.0)) : 0.0;
                double cpuIoWaitPct = diffTotal > 0 ? Math.max(0.0, Math.min(100.0, ((double) diffIoWait / diffTotal) * 100.0)) : 0.0;

                long diffCtxt = Math.max(0L, statB.contextSwitches - statA.contextSwitches);
                double ctxtPerSec = diffCtxt / dtSec;

                long majFaultsDelta = Math.max(0L, majFaultsB - majFaultsA);
                double majFaultsPerSec = majFaultsDelta / dtSec;

                MemorySnapshot mem = getMemorySnapshot();
                double ramMB = mem.ramUsedMB;

                TelemetrySample sample = new TelemetrySample(
                        nowTs, cpuPct, ramMB, cpuIoWaitPct,
                        mem.swapUsedMB, mem.dirtyMemMB,
                        ctxtPerSec, statB.procsRunning,
                        majFaultsPerSec, majFaultsDelta
                );
                samples.add(sample);

                // Timestamp em milissegundos (Epoch/UTC) conforme exigido
                fw.append(String.format(Locale.US,
                        "%d,%.2f,%.4f,%.2f,%.4f,%.4f,%.2f,%d,%.2f\n",
                        nowTs, cpuPct, ramMB, cpuIoWaitPct,
                        mem.swapUsedMB, mem.dirtyMemMB,
                        ctxtPerSec, statB.procsRunning, majFaultsPerSec));
                fw.flush(); // Garante que a linha vai logo para o disco

                statA = statB;
                majFaultsA = majFaultsB;
                prevTs = nowTs;
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha no TelemetryEngine: " + e.getMessage());
        }
    }

    public void stopEngine() {
        this.running = false;
    }
}
