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

   @Override
    public double getSystemRamUsedMB() {
        long total = 0, available = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = Long.parseLong(line.split("\\s+")[1]);
                } else if (line.startsWith("MemAvailable:")) { 
                    available = Long.parseLong(line.split("\\s+")[1]); 
                    break; 
                }
            }
        } catch (Exception e) { 
            return -1.0; 
        }
        return (total - available) / 1024.0;
    }
}
