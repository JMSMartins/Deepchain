package deepchainbench.core.Postgres;

import deepchainbench.core.TelemetryEngine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class PostgresTelemetry extends TelemetryEngine {

    private final Connection conn;

    public PostgresTelemetry(String outputFile, Connection conn) {
        super(outputFile);
        this.conn = conn;
    }

    @Override
    public double getSystemRamUsedMB() {
        // Query to check database memory or use LinuxProcTelemetry as fallback
        try {
            // Postgres stat views can give cache hits/blks read but not RAM direct easily.
            // Using pg_stat_activity or generic system call.
            // Let's fallback to LinuxProcTelemetry logic internally.
            long total = 0, available = 0;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = Long.parseLong(line.split("\\s+")[1]);
                } else if (line.startsWith("MemAvailable:")) {
                    available = Long.parseLong(line.split("\\s+")[1]);
                    break;
                }
            }
            br.close();
            return (total - available) / 1024.0;
        } catch (Exception e) {
            return -1.0;
        }
    }
}
