package deepchainbench.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;

public class TelemetryEngine implements Runnable {
    private volatile boolean running = true;
    private String outputFile;

    public TelemetryEngine(String outputFile) {
        this.outputFile = outputFile;
    }

    // Leitura estática e imediata da RAM para isolar as queries
    public static long getSystemRamUsedMB() {
        long total = 0, available = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = Long.parseLong(line.split("\\s+")[1]);
                else if (line.startsWith("MemAvailable:")) { available = Long.parseLong(line.split("\\s+")[1]); break; }
            }
        } catch (Exception e) { return 0; }
        return (total - available) / 1024;
    }

    // Leitura dos ticks do CPU nativo do Linux
    private long[] getCpuTicks() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] parts = br.readLine().split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i <= 8; i++) total += Long.parseLong(parts[i]);
            idle = Long.parseLong(parts[4]) + Long.parseLong(parts[5]); // idle + iowait
            return new long[]{total, idle};
        } catch (Exception e) { return new long[]{0, 0}; }
    }

    @Override
    public void run() {
        try (FileWriter fw = new FileWriter(outputFile)) {
            fw.append("Timestamp,CPU_Global_Pct,RAM_Consumida_MB\n");
            long[] ticksA = getCpuTicks();
            
            while (running) {
                Thread.sleep(50); // Resolução de 50ms (muito mais rápido que o Bash)
                
                long[] ticksB = getCpuTicks();
                long diffTotal = ticksB[0] - ticksA[0];
                long diffIdle = ticksB[1] - ticksA[1];
                double cpuPct = diffTotal > 0 ? (1.0 - ((double) diffIdle / diffTotal)) * 100.0 : 0.0;
                
                long ramMB = getSystemRamUsedMB();
                
                fw.append(System.currentTimeMillis() + "," + String.format(Locale.US, "%.2f", cpuPct) + "," + ramMB + "\n");
                fw.flush(); // Garante que a linha vai logo para o disco
                
                ticksA = ticksB;
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha no TelemetryEngine: " + e.getMessage());
        }
    }

    public void stopEngine() {
        this.running = false;
    }
}