/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package deepchainbench.generator;

import deepchainbench.core.AbstractBenchmarkEngine.FailureOrigin;
import deepchainbench.core.HTAPSummaryReport;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<JanelaExecucao> janelas = lerJanelas(rfiCsv);

        // ====================================================================
        // PASSO 1: Ler a linha contínua de RAM e CPU
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
        // PASSO 3: Gerar e Exportar Gráfico Exclusivo da RAM
        // ====================================================================
        JFreeChart ramChart = ChartFactory.createXYLineChart(
                "Consumo de RAM (Macro-Visão)", "Tempo (Hora Local)", "RAM Consumida (MB)",
                new XYSeriesCollection(ramSeries)
        );
        configurarEixoTempoEJanelas(ramChart, janelas, Color.RED);
        exportarGrafico(ramChart, outputPathBase + "_RAM.png");

        // ====================================================================
        // PASSO 4: Gerar e Exportar Gráfico Exclusivo do CPU
        // ====================================================================
        JFreeChart cpuChart = ChartFactory.createXYLineChart(
                "Saturação de CPU (Macro-Visão)", "Tempo (Hora Local)", "Uso de CPU (%)",
                new XYSeriesCollection(cpuSeries)
        );
        configurarEixoTempoEJanelas(cpuChart, janelas, Color.BLUE);
        exportarGrafico(cpuChart, outputPathBase + "_CPU.png");
    }

    /**
     * Gráfico multi-painel com métricas adicionais do kernel Linux (/proc):
     * CPU Ativo vs I/O Wait (%), Swap Usada vs Dirty Pages (MB), Context Switches/s & Major Page Faults/s.
     */
    public static void createSystemPressureChart(String observadorCsv, String rfiCsv, String outputPathBase) {
        XYSeries cpuActiveSeries = new XYSeries("CPU Ativo (%)");
        XYSeries ioWaitSeries = new XYSeries("CPU I/O Wait (%)");
        XYSeries swapSeries = new XYSeries("Swap Usada (MB)");
        XYSeries dirtySeries = new XYSeries("Dirty Pages (MB)");
        XYSeries ctxtSeries = new XYSeries("Context Switches (/s)");
        XYSeries majFaultsSeries = new XYSeries("Major Page Faults (/s)");

        boolean hasExtendedColumns = false;
        try (BufferedReader br = new BufferedReader(new FileReader(observadorCsv))) {
            String header = br.readLine();
            if (header == null) {
                return;
            }
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 9) {
                    hasExtendedColumns = true;
                    long ts = Long.parseLong(parts[0].trim());
                    double cpu = Double.parseDouble(parts[1].trim());
                    double ioWait = Double.parseDouble(parts[3].trim());
                    double swap = Double.parseDouble(parts[4].trim());
                    double dirty = Double.parseDouble(parts[5].trim());
                    double ctxt = Double.parseDouble(parts[6].trim());
                    double majFaults = Double.parseDouble(parts[8].trim());

                    cpuActiveSeries.add(ts, cpu);
                    ioWaitSeries.add(ts, ioWait);
                    swapSeries.add(ts, swap);
                    dirtySeries.add(ts, dirty);
                    ctxtSeries.add(ts, ctxt);
                    majFaultsSeries.add(ts, majFaults);
                }
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao ler métricas avançadas do observador: " + e.getMessage());
            return;
        }

        if (!hasExtendedColumns) {
            return;
        }

        List<JanelaExecucao> janelas = lerJanelas(rfiCsv);
        DateAxis domainAxis = new DateAxis("Tempo (Hora Local)");
        domainAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss.SSS"));

        CombinedDomainXYPlot combinedPlot = new CombinedDomainXYPlot(domainAxis);
        combinedPlot.setGap(12.0);

        // Painel 1: CPU vs I/O Wait
        XYSeriesCollection cpuDs = new XYSeriesCollection();
        cpuDs.addSeries(cpuActiveSeries);
        cpuDs.addSeries(ioWaitSeries);
        XYPlot cpuPlot = createSubPlot(cpuDs, "CPU & I/O Wait (%)",
                new Color[]{Color.BLUE, new Color(230, 126, 34)}, janelas);
        combinedPlot.add(cpuPlot, 1);

        // Painel 2: Swap & Dirty Pages
        XYSeriesCollection memDs = new XYSeriesCollection();
        memDs.addSeries(swapSeries);
        memDs.addSeries(dirtySeries);
        XYPlot memPlot = createSubPlot(memDs, "Swap & Dirty (MB)",
                new Color[]{Color.MAGENTA, new Color(192, 57, 43)}, janelas);
        combinedPlot.add(memPlot, 1);

        // Painel 3: Context Switches/s & Major Page Faults/s
        XYSeriesCollection sysDs = new XYSeriesCollection(ctxtSeries);
        XYPlot sysPlot = createSubPlot(sysDs, "Context Switches (/s)",
                new Color[]{new Color(39, 174, 96)}, janelas);
        NumberAxis axis2 = new NumberAxis("Major Faults (/s)");
        sysPlot.setRangeAxis(1, axis2);
        sysPlot.setDataset(1, new XYSeriesCollection(majFaultsSeries));
        sysPlot.mapDatasetToRangeAxis(1, 1);
        XYLineAndShapeRenderer faultRenderer = new XYLineAndShapeRenderer(true, false);
        faultRenderer.setSeriesPaint(0, Color.DARK_GRAY);
        faultRenderer.setSeriesStroke(0, new BasicStroke(1.8f));
        sysPlot.setRenderer(1, faultRenderer);
        combinedPlot.add(sysPlot, 1);

        JFreeChart chart = new JFreeChart(
                "Pressão do Sistema Linux (/proc): I/O Wait, Swap/Dirty, Context Switches e Page Faults",
                JFreeChart.DEFAULT_TITLE_FONT, combinedPlot, true);
        chart.setBackgroundPaint(Color.WHITE);

        exportarGrafico(chart, outputPathBase + "_System_Pressure.png", 1200, 900);
    }

    private static XYPlot createSubPlot(XYSeriesCollection dataset, String yLabel,
                                        Color[] colors, List<JanelaExecucao> janelas) {
        NumberAxis rangeAxis = new NumberAxis(yLabel);
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        for (int i = 0; i < colors.length; i++) {
            renderer.setSeriesPaint(i, colors[i]);
            renderer.setSeriesStroke(i, new BasicStroke(1.8f));
        }
        XYPlot plot = new XYPlot(dataset, null, rangeAxis, renderer);
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        aplicarMarkers(plot, janelas);
        return plot;
    }

    private static List<JanelaExecucao> lerJanelas(String rfiCsv) {
        List<JanelaExecucao> janelas = new ArrayList<>();
        if (rfiCsv == null) {
            return janelas;
        }
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
        return janelas;
    }

    // Método que formata o gráfico e "pinta" as janelas temporais
    private static void configurarEixoTempoEJanelas(JFreeChart chart, List<JanelaExecucao> janelas, Color lineColor) {
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        plot.getRenderer().setSeriesPaint(0, lineColor);

        DateAxis dateAxis = new DateAxis("Tempo (Hora Local)");
        dateAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss.SSS"));
        plot.setDomainAxis(dateAxis);

        aplicarMarkers(plot, janelas);
    }

    private static void aplicarMarkers(XYPlot plot, List<JanelaExecucao> janelas) {
        for (JanelaExecucao j : janelas) {
            IntervalMarker marker = new IntervalMarker(j.start, j.end);
            if (j.runType.contains("Baseline")) {
                marker.setPaint(new Color(135, 206, 250, 70)); // Azul claro (Baseline 2s)
            } else if (j.runType.contains("Cold")) {
                marker.setPaint(new Color(255, 165, 0, 80)); // Laranja (Cold Run / Round 0)
            } else {
                marker.setPaint(new Color(255, 255, 0, 80)); // Amarelo (Warm Runs / HTAP Attack)
            }
            plot.addDomainMarker(marker);
        }
    }

    // ====================================================================
    // GRÁFICOS HTAP (Latências, Sucesso vs Falha e Origem de Timeouts)
    // ====================================================================
    public static void createHTAPCharts(String latencyCsv, String outputPathBase) {
        XYSeries readSeries = new XYSeries("Latência de Leitura (ms)");
        XYSeries writeSeries = new XYSeries("Latência de Escrita (ms)");

        int readSuccess = 0, readFail = 0;
        int writeSuccess = 0, writeFail = 0;

        Map<String, Integer> readFailureOrigins = initOriginMap();
        Map<String, Integer> writeFailureOrigins = initOriginMap();

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
                    String origin = parts.length >= 5 ? parts[4].trim() : (success ? "SUCCESS" : "DB_LOCK_DEADLOCK");

                    if (type.equals("READ")) {
                        if (success) {
                            readSuccess++;
                            readSeries.add(ts, latency);
                        } else {
                            readFail++;
                            readFailureOrigins.merge(origin, 1, Integer::sum);
                        }
                    } else if (type.equals("WRITE")) {
                        if (success) {
                            writeSuccess++;
                            writeSeries.add(ts, latency);
                        } else {
                            writeFail++;
                            writeFailureOrigins.merge(origin, 1, Integer::sum);
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
        configurarEixoTempoSimples(readLineChart, Color.GREEN.darker());
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

        // 6. Gráfico Barras: Origem das Falhas (Timeout Código vs Timeout/Lock/Erro BD)
        DefaultCategoryDataset breakdownDataset = new DefaultCategoryDataset();
        breakdownDataset.addValue(readSuccess, "Leitura (READ)", "Concluídas (Sucesso)");
        breakdownDataset.addValue(writeSuccess, "Escrita (WRITE)", "Concluídas (Sucesso)");

        int readTimeoutCode = readFailureOrigins.getOrDefault(FailureOrigin.TIMEOUT_CODE_QUERY.name(), 0)
                + readFailureOrigins.getOrDefault(FailureOrigin.TIMEOUT_CODE_GLOBAL.name(), 0);
        int writeTimeoutCode = writeFailureOrigins.getOrDefault(FailureOrigin.TIMEOUT_CODE_QUERY.name(), 0)
                + writeFailureOrigins.getOrDefault(FailureOrigin.TIMEOUT_CODE_GLOBAL.name(), 0);
        breakdownDataset.addValue(readTimeoutCode, "Leitura (READ)", "Timeout Código");
        breakdownDataset.addValue(writeTimeoutCode, "Escrita (WRITE)", "Timeout Código");

        breakdownDataset.addValue(readFailureOrigins.getOrDefault(FailureOrigin.TIMEOUT_DB.name(), 0),
                "Leitura (READ)", "Timeout BD");
        breakdownDataset.addValue(writeFailureOrigins.getOrDefault(FailureOrigin.TIMEOUT_DB.name(), 0),
                "Escrita (WRITE)", "Timeout BD");

        breakdownDataset.addValue(readFailureOrigins.getOrDefault(FailureOrigin.DB_LOCK_DEADLOCK.name(), 0),
                "Leitura (READ)", "Lock/Deadlock BD");
        breakdownDataset.addValue(writeFailureOrigins.getOrDefault(FailureOrigin.DB_LOCK_DEADLOCK.name(), 0),
                "Escrita (WRITE)", "Lock/Deadlock BD");

        breakdownDataset.addValue(readFailureOrigins.getOrDefault(FailureOrigin.DB_RESOURCE_OR_ERROR.name(), 0),
                "Leitura (READ)", "Erro/Overload BD");
        breakdownDataset.addValue(writeFailureOrigins.getOrDefault(FailureOrigin.DB_RESOURCE_OR_ERROR.name(), 0),
                "Escrita (WRITE)", "Erro/Overload BD");

        JFreeChart failureBreakdownChart = ChartFactory.createBarChart(
                "Origem das Operações: Sucesso vs Timeout Código vs Falhas BD",
                "Categoria de Resultado", "Número de Operações",
                breakdownDataset, PlotOrientation.VERTICAL, true, true, false);
        failureBreakdownChart.setBackgroundPaint(Color.WHITE);
        exportarGrafico(failureBreakdownChart, outputPathBase + "_Failure_Breakdown.png");
    }

    /**
     * Gera gráfico de barras com a distribuição estatística da latência (Mín, Média, P50, P95, P99, Máx)
     * para Global, Leitura e Escrita.
     */
    public static void createLatencyPercentilesChart(HTAPSummaryReport report, String outputPathBase) {
        if (report == null) {
            return;
        }
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        addSeriesStatsToDataset(dataset, "Global", report.latenciaGlobal);
        addSeriesStatsToDataset(dataset, "Leitura (READ)", report.latenciaLeitura);
        addSeriesStatsToDataset(dataset, "Escrita (WRITE)", report.latenciaEscrita);

        JFreeChart chart = ChartFactory.createBarChart(
                "Distribuição de Latência: Média, Mediana (P50), P95, P99 e Máximo (P100)",
                "Métrica Estatística", "Latência (ms)",
                dataset, PlotOrientation.VERTICAL, true, true, false);
        chart.setBackgroundPaint(Color.WHITE);
        exportarGrafico(chart, outputPathBase + "_Latency_Percentiles.png");
    }

    private static void addSeriesStatsToDataset(DefaultCategoryDataset ds, String seriesName,
                                                HTAPSummaryReport.SeriesStats s) {
        ds.addValue(s.min, seriesName, "Mín");
        ds.addValue(s.mean, seriesName, "Média");
        ds.addValue(s.median, seriesName, "Mediana (P50)");
        ds.addValue(s.p95, seriesName, "P95");
        ds.addValue(s.p99, seriesName, "P99");
        ds.addValue(s.max, seriesName, "Máx (P100)");
    }

    /**
     * Lê a tabela comparativa mestre (htap_comparative_summary.csv) e gera gráficos comparativos
     * entre execuções / bases de dados (Latências, RAM & CPU, Throughput & Confiabilidade).
     */
    public static void createCrossDBComparisonCharts(String comparativeCsvPath, String outputDir) {
        File csvFile = new File(comparativeCsvPath);
        if (!csvFile.exists()) {
            return;
        }
        File outFolder = new File(outputDir);
        if (!outFolder.exists()) {
            outFolder.mkdirs();
        }

        DefaultCategoryDataset latencyDataset = new DefaultCategoryDataset();
        DefaultCategoryDataset ramDataset = new DefaultCategoryDataset();
        DefaultCategoryDataset cpuDataset = new DefaultCategoryDataset();
        DefaultCategoryDataset throughputDataset = new DefaultCategoryDataset();
        DefaultCategoryDataset reliabilityDataset = new DefaultCategoryDataset();

        int rowCount = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                return;
            }
            String[] headers = headerLine.split(",");
            Map<String, Integer> colIdx = new LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++) {
                colIdx.put(headers[i].trim(), i);
            }

            String line;
            int idx = 1;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 30) {
                    continue;
                }
                rowCount++;
                String db = getStr(parts, colIdx, "Base_de_Dados");
                String sf = getStr(parts, colIdx, "Scale_Factor");
                String perfil = getStr(parts, colIdx, "Perfil")
                        .replace("MIX_", "")
                        .replace("_STRESS", "");
                String clientes = getStr(parts, colIdx, "Clientes_Concorrentes");
                String label = "#" + idx + " " + db + " (" + sf + ", " + perfil + ", " + clientes + "c)";
                idx++;

                latencyDataset.addValue(getDouble(parts, colIdx, "Lat_Media_ms"), "Média (ms)", label);
                latencyDataset.addValue(getDouble(parts, colIdx, "Lat_Mediana_P50_ms"), "Mediana P50 (ms)", label);
                latencyDataset.addValue(getDouble(parts, colIdx, "Lat_P95_ms"), "P95 (ms)", label);
                latencyDataset.addValue(getDouble(parts, colIdx, "Lat_P99_ms"), "P99 (ms)", label);
                latencyDataset.addValue(getDouble(parts, colIdx, "Lat_Max_ms"), "Máx P100 (ms)", label);

                ramDataset.addValue(getDouble(parts, colIdx, "RAM_Baseline_2s_Media_MB"), "RAM Baseline 2s (MB)", label);
                ramDataset.addValue(getDouble(parts, colIdx, "RAM_Media_MB"), "RAM Média Carga (MB)", label);
                ramDataset.addValue(getDouble(parts, colIdx, "RAM_Max_MB"), "RAM Máxima (MB)", label);
                ramDataset.addValue(getDouble(parts, colIdx, "RAM_Delta_Max_MB"), "Subida RAM Máx Δ (MB)", label);

                cpuDataset.addValue(getDouble(parts, colIdx, "CPU_Media_Pct"), "CPU Média (%)", label);
                cpuDataset.addValue(getDouble(parts, colIdx, "CPU_Mediana_P50_Pct"), "CPU Mediana P50 (%)", label);
                cpuDataset.addValue(getDouble(parts, colIdx, "CPU_P99_Pct"), "CPU P99 (%)", label);
                cpuDataset.addValue(getDouble(parts, colIdx, "CPU_Max_Pct"), "CPU Máx (%)", label);

                throughputDataset.addValue(getDouble(parts, colIdx, "Throughput_Global_ops_s"), "Throughput Global (ops/s)", label);
                throughputDataset.addValue(getDouble(parts, colIdx, "Goodput_Efetivo_ops_s"), "Goodput Efetivo (ops/s)", label);

                reliabilityDataset.addValue(getDouble(parts, colIdx, "Taxa_Sucesso_Global_Pct"), "Sucesso Global (%)", label);
                reliabilityDataset.addValue(getDouble(parts, colIdx, "Read_Taxa_Sucesso_Pct"), "Sucesso Leituras (%)", label);
                reliabilityDataset.addValue(getDouble(parts, colIdx, "Write_Taxa_Sucesso_Pct"), "Sucesso Escritas (%)", label);
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao gerar gráficos comparativos entre BDs: " + e.getMessage());
            return;
        }

        if (rowCount == 0) {
            return;
        }

        exportCategoryChart(
                "Comparação Entre Bases de Dados: Latências (Média, P50, P95, P99, Máx)",
                "Execução (BD / SF / Perfil / Clientes)", "Latência (ms)",
                latencyDataset, outputDir + "/comparacao_Latencia_Percentis.png");

        exportCategoryChart(
                "Comparação Entre Bases de Dados: Consumo e Subida de RAM (Baseline 2s vs Carga)",
                "Execução (BD / SF / Perfil / Clientes)", "Memória RAM (MB)",
                ramDataset, outputDir + "/comparacao_RAM.png");

        exportCategoryChart(
                "Comparação Entre Bases de Dados: Uso de CPU (Média, Mediana, P99, Máx)",
                "Execução (BD / SF / Perfil / Clientes)", "CPU (%)",
                cpuDataset, outputDir + "/comparacao_CPU.png");

        exportCategoryChart(
                "Comparação Entre Bases de Dados: Throughput Global vs Goodput Efetivo",
                "Execução (BD / SF / Perfil / Clientes)", "Operações / segundo (ops/s)",
                throughputDataset, outputDir + "/comparacao_Throughput.png");

        exportCategoryChart(
                "Comparação Entre Bases de Dados: Taxa de Sucesso (Global, Leitura, Escrita)",
                "Execução (BD / SF / Perfil / Clientes)", "Taxa de Sucesso (%)",
                reliabilityDataset, outputDir + "/comparacao_Confiabilidade.png");
    }

    private static void exportCategoryChart(String title, String xLabel, String yLabel,
                                            DefaultCategoryDataset dataset, String path) {
        JFreeChart chart = ChartFactory.createBarChart(
                title, xLabel, yLabel, dataset, PlotOrientation.VERTICAL, true, true, false);
        chart.setBackgroundPaint(Color.WHITE);
        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        plot.getDomainAxis().setCategoryLabelPositions(CategoryLabelPositions.UP_45);
        exportarGrafico(chart, path, 1400, 750);
    }

    private static String getStr(String[] parts, Map<String, Integer> colIdx, String col) {
        Integer idx = colIdx.get(col);
        if (idx == null || idx >= parts.length) {
            return "";
        }
        return parts[idx].trim();
    }

    private static double getDouble(String[] parts, Map<String, Integer> colIdx, String col) {
        String s = getStr(parts, colIdx, col);
        if (s.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static Map<String, Integer> initOriginMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (FailureOrigin origin : FailureOrigin.values()) {
            m.put(origin.name(), 0);
        }
        return m;
    }

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
        exportarGrafico(chart, path, 1200, 600);
    }

    private static void exportarGrafico(JFreeChart chart, String path, int width, int height) {
        try {
            ChartUtils.saveChartAsPNG(new File(path), chart, width, height);
            System.out.println("   -> Exportado: " + path);
        } catch (Exception e) {
            System.err.println("   [ERRO] Falha ao exportar " + path);
        }
    }
}
