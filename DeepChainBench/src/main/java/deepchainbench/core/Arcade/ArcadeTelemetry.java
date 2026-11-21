package deepchainbench.core.Arcade;

import deepchainbench.core.LinuxProcTelemetry;

/**
 * Coletor de telemetria para testes do ArcadeDB usando leitura real de /proc/meminfo e /proc/stat em Linux.
 */
public class ArcadeTelemetry extends LinuxProcTelemetry {

    public ArcadeTelemetry(String outputCsvPath) {
        this(outputCsvPath, DEFAULT_SAMPLING_INTERVAL_MS);
    }

    public ArcadeTelemetry(String outputCsvPath, long samplingIntervalMs) {
        super(outputCsvPath, samplingIntervalMs);
    }
}
