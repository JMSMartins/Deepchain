package deepchainbench.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;

public abstract class TelemetryEngine implements Runnable {
    protected volatile boolean running = true;
    protected String outputFile;

    public TelemetryEngine(String outputFile) {
        this.outputFile = outputFile;
    }

    // MÉTODO ABSTRATO: Cada classe implementa a sua forma de ler a RAM
    public abstract double getSystemRamUsedMB();

    // Leitura dos ticks do CPU nativo do Linux
    protected long[] getCpuTicks() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] parts = br.readLine().split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i <= 8; i++) total += Long.parseLong(parts[i]);
            idle = Long.parseLong(parts[4]) + Long.parseLong(parts[5]); // idle + iowait
            return new long[]{total, idle};
        } catch (Exception e) { 
            return new long[]{0, 0}; 
        }
    }

    @Override
    public void run() {
        try (FileWriter fw = new FileWriter(outputFile)) {
            fw.append("Timestamp,CPU_Global_Pct,RAM_Consumida_MB\n");
            long[] ticksA = getCpuTicks();
            
            while (running) {
                // Resolução de 250ms para não asfixiar APIs HTTP (Arango) ou gerar excesso de I/O
                Thread.sleep(250); 
                
                long[] ticksB = getCpuTicks();
                long diffTotal = ticksB[0] - ticksA[0];
                long diffIdle = ticksB[1] - ticksA[1];
                double cpuPct = diffTotal > 0 ? (1.0 - ((double) diffIdle / diffTotal)) * 100.0 : 0.0;
                
                // Delega a chamada para a implementação específica (LinuxProc ou Arango)
                double ramMB = getSystemRamUsedMB();
                
                // Timestamp em milissegundos (Epoch/UTC) conforme exigido
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