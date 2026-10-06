/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package deepchainbench.generator;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.chart.plot.PlotOrientation;

public class GraphGenerator {

    // Classe auxiliar para guardar as janelas de tempo
    private static class JanelaExecucao {
        long start, end;
        String runType;
        JanelaExecucao(long s, long e, String type) { start = s; end = e; runType = type; }
    }

    public static void createRFIChart(String observadorCsv, String rfiCsv, String outputPathBase) {
        XYSeries cpuSeries = new XYSeries("CPU Global (%)");
        XYSeries ramSeries = new XYSeries("RAM Consumida (MB)");
        List<JanelaExecucao> janelas = new ArrayList<>();

        // ====================================================================
        // PASSO 1: Ler a linha contínua de RAM e CPU (De 50 em 50ms)
        // ====================================================================
        try (BufferedReader br = new BufferedReader(new FileReader(observadorCsv))) {
            String line;
            br.readLine(); // Ignorar cabeçalho
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    long ts = Long.parseLong(parts[0].trim());
                    double cpu = Double.parseDouble(parts[1].trim());
                    double ram = Double.parseDouble(parts[2].trim());
                    
                    cpuSeries.add(ts, cpu);
                    ramSeries.add(ts, ram);
                }
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao ler observador: " + e.getMessage());
            return;
        }

        // ====================================================================
        // PASSO 2: Ler os Tempos Exatos das Queries (As Janelas Temporais)
        // ====================================================================
        try (BufferedReader br = new BufferedReader(new FileReader(rfiCsv))) {
            String line;
            br.readLine(); // Ignorar cabeçalho
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    long start = Long.parseLong(parts[0].trim());
                    long end = Long.parseLong(parts[1].trim());
                    String runType = parts[6].trim();
                    janelas.add(new JanelaExecucao(start, end, runType));
                }
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao ler janelas RFI: " + e.getMessage());
        }

        // ====================================================================
        // PASSO 3: Gerar e Exportar Gráfico Exclusivo da RAM
        // ====================================================================
        JFreeChart ramChart = ChartFactory.createXYLineChart(
                "Consumo de RAM (Macro-Visão)", "Tempo (Hora Local)", "RAM Consumida (MB)", 
                new XYSeriesCollection(ramSeries)
        );
        configurarEixoTempoEJanelas(ramChart, janelas, Color.RED);
        
        try {
            File ramFile = new File(outputPathBase + "_RAM.png");
            ChartUtils.saveChartAsPNG(ramFile, ramChart, 1200, 600);
            System.out.println("-> Gráfico RAM exportado: " + ramFile.getAbsolutePath());
        } catch (Exception e) {}

        // ====================================================================
        // PASSO 4: Gerar e Exportar Gráfico Exclusivo do CPU
        // ====================================================================
        JFreeChart cpuChart = ChartFactory.createXYLineChart(
                "Saturação de CPU (Macro-Visão)", "Tempo (Hora Local)", "Uso de CPU (%)", 
                new XYSeriesCollection(cpuSeries)
        );
        configurarEixoTempoEJanelas(cpuChart, janelas, Color.BLUE);

        try {
            File cpuFile = new File(outputPathBase + "_CPU.png");
            ChartUtils.saveChartAsPNG(cpuFile, cpuChart, 1200, 600);
            System.out.println("-> Gráfico CPU exportado: " + cpuFile.getAbsolutePath());
        } catch (Exception e) {}
    }

    // Método que formata o gráfico e "pinta" as janelas temporais
    private static void configurarEixoTempoEJanelas(JFreeChart chart, List<JanelaExecucao> janelas, Color lineColor) {
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        plot.getRenderer().setSeriesPaint(0, lineColor);

        // Formata o eixo X para Hora:Minuto:Segundo
        DateAxis dateAxis = new DateAxis("Tempo (Hora Local)");
        dateAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss.SSS"));
        plot.setDomainAxis(dateAxis);

        // INSERÇÃO DAS JANELAS TEMPORAIS
        for (JanelaExecucao j : janelas) {
            IntervalMarker marker = new IntervalMarker(j.start, j.end);
            if (j.runType.contains("Cold")) {
                marker.setPaint(new Color(255, 165, 0, 80)); // Laranja (Cold Run)
            } else {
                marker.setPaint(new Color(255, 255, 0, 80)); // Amarelo (Warm Runs)
            }
            plot.addDomainMarker(marker); // Aplica a janela ao gráfico
        }
    }


    // ====================================================================
    // NOVOS GRÁFICOS HTAP (Latências e Sucesso vs Falha)
    // ====================================================================
    public static void createHTAPCharts(String latencyCsv, String outputPathBase) {
        XYSeries readSeries = new XYSeries("Latência de Leitura (ms)");
        XYSeries writeSeries = new XYSeries("Latência de Escrita (ms)");

        int readSuccess = 0, readFail = 0;
        int writeSuccess = 0, writeFail = 0;

        // 1. Processar os dados do CSV
        try (BufferedReader br = new BufferedReader(new FileReader(latencyCsv))) {
            String line;
            br.readLine(); // Ignorar o cabeçalho
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    long ts = Long.parseLong(parts[0].trim());
                    String type = parts[1].trim();
                    long latency = Long.parseLong(parts[2].trim());
                    boolean success = Boolean.parseBoolean(parts[3].trim());

                    if (type.equals("READ")) {
                        if (success) {
                            readSuccess++;
                            readSeries.add(ts, latency); // Apenas latências com sucesso vão para a linha
                        } else {
                            readFail++;
                        }
                    } else if (type.equals("WRITE")) {
                        if (success) {
                            writeSuccess++;
                            writeSeries.add(ts, latency);
                        } else {
                            writeFail++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao ler CSV de latências: " + e.getMessage());
            return;
        }

        System.out.println("-> A processar gráficos analíticos HTAP...");

        // 2. Gráfico Linha: Leituras (Sucessos)
        JFreeChart readLineChart = ChartFactory.createXYLineChart(
                "Degradação de Performance: Leituras", "Tempo (Hora Local)", "Latência (ms)", 
                new XYSeriesCollection(readSeries));
        configurarEixoTempoSimples(readLineChart, Color.GREEN);
        exportarGrafico(readLineChart, outputPathBase + "_Read_Latency.png");

        // 3. Gráfico Linha: Escritas (Sucessos)
        JFreeChart writeLineChart = ChartFactory.createXYLineChart(
                "Degradação de Performance: Escritas (Locks)", "Tempo (Hora Local)", "Latência (ms)", 
                new XYSeriesCollection(writeSeries));
        configurarEixoTempoSimples(writeLineChart, Color.RED);
        exportarGrafico(writeLineChart, outputPathBase + "_Write_Latency.png");

        // 4. Gráfico Barras: Sucesso vs Falha (Leituras)
        DefaultCategoryDataset readDataset = new DefaultCategoryDataset();
        readDataset.addValue(readSuccess, "Leituras", "Sucesso");
        readDataset.addValue(readFail, "Leituras", "Timeout / Falha");
        JFreeChart readBarChart = ChartFactory.createBarChart(
                "Confiabilidade das Leituras", "Estado", "Número de Queries", 
                readDataset, PlotOrientation.VERTICAL, true, true, false);
        exportarGrafico(readBarChart, outputPathBase + "_Read_Status_Bar.png");

        // 5. Gráfico Barras: Sucesso vs Falha (Escritas)
        DefaultCategoryDataset writeDataset = new DefaultCategoryDataset();
        writeDataset.addValue(writeSuccess, "Escritas", "Sucesso");
        writeDataset.addValue(writeFail, "Escritas", "Timeout / Lock Contention");
        JFreeChart writeBarChart = ChartFactory.createBarChart(
                "Confiabilidade das Escritas (Contenção)", "Estado", "Número de Queries", 
                writeDataset, PlotOrientation.VERTICAL, true, true, false);
        exportarGrafico(writeBarChart, outputPathBase + "_Write_Status_Bar.png");
    }

    // Métodos Auxiliares para evitar repetição
    private static void configurarEixoTempoSimples(JFreeChart chart, Color lineColor) {
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        plot.getRenderer().setSeriesPaint(0, lineColor);

        DateAxis dateAxis = new DateAxis("Tempo (Hora Local)");
        dateAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss.SSS"));
        plot.setDomainAxis(dateAxis);
    }

    private static void exportarGrafico(JFreeChart chart, String path) {
        try {
            ChartUtils.saveChartAsPNG(new File(path), chart, 1200, 600);
            System.out.println("   -> Exportado: " + path);
        } catch (Exception e) {
            System.err.println("   [ERRO] Falha ao exportar " + path);
        }
    }
}