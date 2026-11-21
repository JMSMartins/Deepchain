/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package deepchainbench.core;

import java.io.BufferedReader;
import java.io.FileReader;

public class LinuxProcTelemetry extends TelemetryEngine {

    public LinuxProcTelemetry(String outputFile) {
        super(outputFile);
    }

    public LinuxProcTelemetry(String outputFile, long samplingIntervalMs) {
        super(outputFile, samplingIntervalMs);
    }

    @Override
    public MemorySnapshot getMemorySnapshot() {
        long memTotal = 0, memAvailable = 0;
        long swapTotal = 0, swapFree = 0, dirty = 0;
        int found = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    memTotal = Long.parseLong(line.split("\\s+")[1]);
                    found++;
                } else if (line.startsWith("MemAvailable:")) {
                    memAvailable = Long.parseLong(line.split("\\s+")[1]);
                    found++;
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = Long.parseLong(line.split("\\s+")[1]);
                    found++;
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = Long.parseLong(line.split("\\s+")[1]);
                    found++;
                } else if (line.startsWith("Dirty:")) {
                    dirty = Long.parseLong(line.split("\\s+")[1]);
                    found++;
                }
                if (found >= 5) {
                    break;
                }
            }
        } catch (Exception e) {
            return new MemorySnapshot(-1.0, 0.0, 0.0);
        }
        double ramUsedMB = (memTotal - memAvailable) / 1024.0;
        double swapUsedMB = Math.max(0.0, (swapTotal - swapFree) / 1024.0);
        double dirtyMemMB = dirty / 1024.0;
        return new MemorySnapshot(ramUsedMB, swapUsedMB, dirtyMemMB);
    }

    @Override
    public double getSystemRamUsedMB() {
        return getMemorySnapshot().ramUsedMB;
    }
}
