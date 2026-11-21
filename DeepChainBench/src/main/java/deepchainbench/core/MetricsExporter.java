package deepchainbench.core;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MetricsExporter {

    private static final String EXCEL_PATH = "./benchmark_results.xlsx";
    private static final String HTAP_COMPARATIVE_CSV = "./metrics/htap_comparative_summary.csv";

    public static void saveToExcel(String dbName, String datasetName, String schemaName,
            String startTimestamp, String endTimestamp,
            long durationMs, long dbSizeBytes) {

        long datasetSizeBytes = calculateDatasetSize("./dataset/" + datasetName);

        File file = new File(EXCEL_PATH);
        Workbook workbook;
        Sheet sheet;

        try {
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    workbook = WorkbookFactory.create(fis);
                }
                sheet = workbook.getSheet("Ingestion Metrics");
                if (sheet == null) {
                    sheet = workbook.createSheet("Ingestion Metrics");
                    createHeader(sheet);
                }
            } else {
                workbook = new XSSFWorkbook();
                sheet = workbook.createSheet("Ingestion Metrics");
                createHeader(sheet);
            }

            int nextRowIndex = sheet.getLastRowNum() + 1;
            Row row = sheet.createRow(nextRowIndex);

            row.createCell(0).setCellValue(dbName);
            row.createCell(1).setCellValue(datasetName);
            row.createCell(2).setCellValue(schemaName);
            row.createCell(3).setCellValue(startTimestamp);
            row.createCell(4).setCellValue(endTimestamp);
            row.createCell(5).setCellValue(durationMs / 1000.0);
            row.createCell(6).setCellValue(datasetSizeBytes / (1024.0 * 1024.0));
            row.createCell(7).setCellValue(dbSizeBytes / (1024.0 * 1024.0));

            for (int i = 0; i < 8; i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
            workbook.close();
            System.out.println("-> Métricas exportadas com sucesso para: " + EXCEL_PATH);

        } catch (Exception e) {
            System.err.println("Erro ao gerar o ficheiro Excel: " + e.getMessage());
        }
    }

    private static void createHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        String[] columns = {
            "Base de Dados",
            "Dataset Original",
            "Nome do Esquema",
            "Início (GMT-0)",
            "Fim (GMT-0)",
            "Tempo Ingestão (s)",
            "Tamanho Dataset (MB)",
            "Tamanho em Disco BD (MB)"
        };
        for (int i = 0; i < columns.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns[i]);
        }
    }

    private static long calculateDatasetSize(String folderPath) {
        File folder = new File(folderPath);
        long totalSize = 0;
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        totalSize += f.length();
                    }
                }
            }
        }
        return totalSize;
    }

    public static void saveHTAPSummary(String sessionTimestamp, String perfil, int clientes, int totalOps,
            double throughput, long p50, long p95, long p99, double tempoTotalSegundos, int falhasContencao) {
        try {
            File dir = new File("./metrics");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File file = new File(dir, "htap_summary_results.csv");
            boolean isNewFile = !file.exists();

            try (FileWriter fw = new FileWriter(file, true)) {
                if (isNewFile) {
                    fw.append("Session_Timestamp,Perfil,Clientes,Total_Operacoes,Throughput_ops_sec,P50_ms,P95_ms,P99_ms,Tempo_Total_segundos,Falhas_Contencao\n");
                }

                fw.append(String.format(Locale.US, "%s,%s,%d,%d,%.2f,%d,%d,%d,%.3f,%d\n",
                        sessionTimestamp, perfil, clientes, totalOps, throughput, p50, p95, p99, tempoTotalSegundos, falhasContencao));
            }
            System.out.println("-> Resumo HTAP legado gravado em: " + file.getPath());

        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar resumo HTAP para CSV: " + e.getMessage());
        }
    }

    private static final String[] COMPARATIVE_COLUMNS = {
        "Session_Timestamp", "Data_Hora_UTC", "Base_de_Dados", "Esquema", "Scale_Factor", "Perfil",
        "Clientes_Concorrentes", "Total_Pedidos_Solicitados", "Tempo_Total_s", "Intervalo_Telemetria_ms",
        "Total_Executadas", "Total_Concluidas", "Total_Falhadas", "Taxa_Sucesso_Global_Pct",
        "Read_Total", "Read_Concluidas", "Read_Falhadas", "Read_Taxa_Sucesso_Pct",
        "Write_Total", "Write_Concluidas", "Write_Falhadas", "Write_Taxa_Sucesso_Pct",
        "Throughput_Global_ops_s", "Goodput_Efetivo_ops_s",
        "Falhas_Timeout_Codigo_Query", "Falhas_Timeout_Codigo_Global", "Falhas_Timeout_Codigo_Total",
        "Falhas_Timeout_BD", "Falhas_Lock_Deadlock_BD", "Falhas_Erro_BD", "Falhas_BD_Total", "Falhas_Outras_Cliente",
        "Lat_Media_ms", "Lat_Mediana_P50_ms", "Lat_P95_ms", "Lat_P99_ms", "Lat_Min_ms", "Lat_Max_ms", "Lat_StdDev_ms",
        "Read_Lat_Media_ms", "Read_Lat_Mediana_P50_ms", "Read_Lat_P95_ms", "Read_Lat_P99_ms", "Read_Lat_Max_ms",
        "Write_Lat_Media_ms", "Write_Lat_Mediana_P50_ms", "Write_Lat_P95_ms", "Write_Lat_P99_ms", "Write_Lat_Max_ms",
        "RAM_Baseline_2s_Media_MB", "RAM_Media_MB", "RAM_Mediana_P50_MB", "RAM_P95_MB", "RAM_P99_MB", "RAM_Max_MB",
        "RAM_Delta_Media_MB", "RAM_Delta_Max_MB",
        "CPU_Baseline_2s_Media_Pct", "CPU_Media_Pct", "CPU_Mediana_P50_Pct", "CPU_P95_Pct", "CPU_P99_Pct", "CPU_Max_Pct",
        "CPU_IOWait_Media_Pct", "CPU_IOWait_Max_Pct",
        "Swap_Baseline_2s_Media_MB", "Swap_Max_MB", "Swap_Delta_Max_MB",
        "Dirty_Mem_Max_MB", "Context_Switches_Media_Sec", "Context_Switches_Max_Sec",
        "Procs_Running_Max", "Major_Page_Faults_Total"
    };

    public static String getComparativeCsvPath() {
        return HTAP_COMPARATIVE_CSV;
    }

    /**
     * Exporta a tabela comparativa completa para CSV mestre (comparação entre bases de dados),
     * CSV/TXT individual da execução e folha "HTAP Comparative Summary" no Excel.
     */
    public static void saveHTAPComparativeSummary(HTAPSummaryReport r, String runFolderPath) {
        saveHTAPComparativeSummary(r, runFolderPath, HTAP_COMPARATIVE_CSV, EXCEL_PATH);
    }

    /**
     * Sobrecarga parametrizável (útil para testes ou destinos alternativos sem sujar ./metrics).
     */
    public static void saveHTAPComparativeSummary(HTAPSummaryReport r, String runFolderPath,
                                                  String masterCsvPath, String excelPath) {
        exportMasterComparativeCsv(r, masterCsvPath);
        if (runFolderPath != null && !runFolderPath.isEmpty()) {
            exportRunSummaryFiles(r, runFolderPath);
        }
        exportComparativeToExcel(r, excelPath);
    }

    public static void exportMasterComparativeCsv(HTAPSummaryReport r, String csvPath) {
        try {
            File file = new File(csvPath);
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            boolean isNew = !file.exists();
            try (FileWriter fw = new FileWriter(file, true)) {
                if (isNew) {
                    fw.append(String.join(",", COMPARATIVE_COLUMNS)).append("\n");
                }
                fw.append(formatComparativeCsvRow(r)).append("\n");
            }
            System.out.println("-> Tabela comparativa mestre exportada para: " + file.getPath());
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar tabela comparativa CSV: " + e.getMessage());
        }
    }

    private static String formatComparativeCsvRow(HTAPSummaryReport r) {
        return String.format(Locale.US,
                "%s,%s,%s,%s,%s,%s,"
                + "%d,%d,%.3f,%d,"
                + "%d,%d,%d,%.2f,"
                + "%d,%d,%d,%.2f,"
                + "%d,%d,%d,%.2f,"
                + "%.2f,%.2f,"
                + "%d,%d,%d,"
                + "%d,%d,%d,%d,%d,"
                + "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,"
                + "%.2f,%.2f,%.2f,%.2f,%.2f,"
                + "%.2f,%.2f,%.2f,%.2f,%.2f,"
                + "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,"
                + "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,"
                + "%.2f,%.2f,"
                + "%.2f,%.2f,%.2f,"
                + "%.2f,%.2f,%.2f,"
                + "%d,%d",
                sanitizeCsv(r.sessionTimestamp), sanitizeCsv(r.dataHoraUtc), sanitizeCsv(r.baseDeDados),
                sanitizeCsv(r.esquema), sanitizeCsv(r.scaleFactor), sanitizeCsv(r.perfil),
                r.clientesConcorrentes, r.totalPedidosSolicitados, r.tempoTotalSegundos, r.intervaloTelemetriaMs,
                r.totalExecutadas, r.totalConcluidas, r.totalFalhadas, r.taxaSucessoGlobalPct,
                r.readTotal, r.readConcluidas, r.readFalhadas, r.readTaxaSucessoPct,
                r.writeTotal, r.writeConcluidas, r.writeFalhadas, r.writeTaxaSucessoPct,
                r.throughputGlobalOpsSec, r.goodputEfetivoOpsSec,
                r.falhasTimeoutCodigoQuery, r.falhasTimeoutCodigoGlobal, r.getTotalFalhasTimeoutCodigo(),
                r.falhasTimeoutBD, r.falhasLockDeadlockBD, r.falhasErroBD, r.getTotalFalhasBD(), r.falhasOutrasCliente,
                r.latenciaGlobal.mean, r.latenciaGlobal.median, r.latenciaGlobal.p95, r.latenciaGlobal.p99,
                r.latenciaGlobal.min, r.latenciaGlobal.max, r.latenciaGlobal.stdDev,
                r.latenciaLeitura.mean, r.latenciaLeitura.median, r.latenciaLeitura.p95, r.latenciaLeitura.p99, r.latenciaLeitura.max,
                r.latenciaEscrita.mean, r.latenciaEscrita.median, r.latenciaEscrita.p95, r.latenciaEscrita.p99, r.latenciaEscrita.max,
                r.ramBaseline2sMediaMB, r.ramCarga.mean, r.ramCarga.median, r.ramCarga.p95, r.ramCarga.p99, r.ramCarga.max,
                r.ramDeltaMediaMB, r.ramDeltaMaxMB,
                r.cpuBaseline2sMediaPct, r.cpuCarga.mean, r.cpuCarga.median, r.cpuCarga.p95, r.cpuCarga.p99, r.cpuCarga.max,
                r.cpuIoWaitCarga.mean, r.cpuIoWaitCarga.max,
                r.swapBaseline2sMediaMB, r.swapCarga.max, r.swapDeltaMaxMB,
                r.dirtyMemCarga.max, r.contextSwitchesCarga.mean, r.contextSwitchesCarga.max,
                r.procsRunningMax, r.majorPageFaultsTotal
        );
    }

    private static String sanitizeCsv(String val) {
        if (val == null) {
            return "";
        }
        return val.replace(",", ";").replace("\n", " ").replace("\r", " ");
    }

    private static void exportRunSummaryFiles(HTAPSummaryReport r, String runFolderPath) {
        try {
            File dir = new File(runFolderPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File csvFile = new File(dir, "htap_run_summary.csv");
            try (FileWriter fw = new FileWriter(csvFile)) {
                fw.append(String.join(",", COMPARATIVE_COLUMNS)).append("\n");
                fw.append(formatComparativeCsvRow(r)).append("\n");
            }
            File verticalCsvFile = new File(dir, "resumo_execucao_vertical.csv");
            exportVerticalCsv(r, verticalCsvFile);

            File mdFile = new File(dir, "resumo_execucao.md");
            exportRunMarkdown(r, mdFile);

            File xlsxFile = new File(dir, "resumo_execucao.xlsx");
            exportRunVerticalExcel(r, xlsxFile);

            File txtFile = new File(dir, "summary_table.txt");
            try (FileWriter fw = new FileWriter(txtFile)) {
                fw.append(formatConsoleTable(r));
            }
            System.out.println("-> Resumos individuais (TXT, CSV vertical, MD e XLSX) gravados em: " + dir.getPath());
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao gravar resumo individual da execução: " + e.getMessage());
        }
    }

    private static List<String[]> buildVerticalMetricRows(HTAPSummaryReport r) {
        List<String[]> rows = new ArrayList<>();
        // 1. Metadados
        rows.add(new String[]{"Metadados", "Base_de_Dados", safeStr(r.baseDeDados), "-"});
        rows.add(new String[]{"Metadados", "Esquema", safeStr(r.esquema), "-"});
        rows.add(new String[]{"Metadados", "Scale_Factor", safeStr(r.scaleFactor), "-"});
        rows.add(new String[]{"Metadados", "Perfil", safeStr(r.perfil), "-"});
        rows.add(new String[]{"Metadados", "Data_Hora_UTC", safeStr(r.dataHoraUtc), "UTC"});
        rows.add(new String[]{"Metadados", "Session_Timestamp", safeStr(r.sessionTimestamp), "ms"});
        rows.add(new String[]{"Metadados", "Clientes_Concorrentes", String.valueOf(r.clientesConcorrentes), "users"});
        rows.add(new String[]{"Metadados", "Total_Pedidos_Solicitados", String.valueOf(r.totalPedidosSolicitados), "ops"});
        rows.add(new String[]{"Metadados", "Tempo_Total_Teste", fmt(r.tempoTotalSegundos, 3), "s"});
        rows.add(new String[]{"Metadados", "Intervalo_Telemetria", String.valueOf(r.intervaloTelemetriaMs), "ms"});

        // 2. Operacoes & Throughput
        rows.add(new String[]{"Operacoes", "Total_Executadas", String.valueOf(r.totalExecutadas), "ops"});
        rows.add(new String[]{"Operacoes", "Total_Concluidas", String.valueOf(r.totalConcluidas), "ops"});
        rows.add(new String[]{"Operacoes", "Total_Falhadas", String.valueOf(r.totalFalhadas), "ops"});
        rows.add(new String[]{"Operacoes", "Taxa_Sucesso_Global", fmt(r.taxaSucessoGlobalPct, 2), "%"});
        rows.add(new String[]{"Operacoes", "Read_Total", String.valueOf(r.readTotal), "ops"});
        rows.add(new String[]{"Operacoes", "Read_Concluidas", String.valueOf(r.readConcluidas), "ops"});
        rows.add(new String[]{"Operacoes", "Read_Falhadas", String.valueOf(r.readFalhadas), "ops"});
        rows.add(new String[]{"Operacoes", "Read_Taxa_Sucesso", fmt(r.readTaxaSucessoPct, 2), "%"});
        rows.add(new String[]{"Operacoes", "Write_Total", String.valueOf(r.writeTotal), "ops"});
        rows.add(new String[]{"Operacoes", "Write_Concluidas", String.valueOf(r.writeConcluidas), "ops"});
        rows.add(new String[]{"Operacoes", "Write_Falhadas", String.valueOf(r.writeFalhadas), "ops"});
        rows.add(new String[]{"Operacoes", "Write_Taxa_Sucesso", fmt(r.writeTaxaSucessoPct, 2), "%"});
        rows.add(new String[]{"Operacoes", "Throughput_Global_Raw", fmt(r.throughputGlobalOpsSec, 2), "ops/s"});
        rows.add(new String[]{"Operacoes", "Goodput_Efetivo_Sucessos", fmt(r.goodputEfetivoOpsSec, 2), "ops/s"});

        // 3. Origem das Falhas
        rows.add(new String[]{"Origem_Falhas", "Timeout_Codigo_Query", String.valueOf(r.falhasTimeoutCodigoQuery), "ops"});
        rows.add(new String[]{"Origem_Falhas", "Timeout_Codigo_Global", String.valueOf(r.falhasTimeoutCodigoGlobal), "ops"});
        rows.add(new String[]{"Origem_Falhas", "Timeout_Codigo_Total", String.valueOf(r.getTotalFalhasTimeoutCodigo()), "ops"});
        rows.add(new String[]{"Origem_Falhas", "Timeout_BD", String.valueOf(r.falhasTimeoutBD), "ops"});
        rows.add(new String[]{"Origem_Falhas", "Lock_Deadlock_BD", String.valueOf(r.falhasLockDeadlockBD), "ops"});
        rows.add(new String[]{"Origem_Falhas", "Erro_BD", String.valueOf(r.falhasErroBD), "ops"});
        rows.add(new String[]{"Origem_Falhas", "Falhas_BD_Total", String.valueOf(r.getTotalFalhasBD()), "ops"});
        rows.add(new String[]{"Origem_Falhas", "Outras_Falhas_Cliente", String.valueOf(r.falhasOutrasCliente), "ops"});

        // 4. Latencias (Global, Leitura, Escrita)
        addSeriesRows(rows, "Latencia_Global", r.latenciaGlobal, "ms");
        addSeriesRows(rows, "Latencia_Leitura", r.latenciaLeitura, "ms");
        addSeriesRows(rows, "Latencia_Escrita", r.latenciaEscrita, "ms");

        // 5. RAM (Baseline 2s vs Carga)
        rows.add(new String[]{"RAM", "Baseline_2s_Media", fmt(r.ramBaseline2sMediaMB, 2), "MB"});
        addSeriesRows(rows, "RAM", r.ramCarga, "MB");
        rows.add(new String[]{"RAM", "Delta_Media_vs_2s", fmtSigned(r.ramDeltaMediaMB, 2), "MB"});
        rows.add(new String[]{"RAM", "Delta_Max_vs_2s", fmtSigned(r.ramDeltaMaxMB, 2), "MB"});

        // 6. CPU
        rows.add(new String[]{"CPU", "Baseline_2s_Media", fmt(r.cpuBaseline2sMediaPct, 2), "%"});
        addSeriesRows(rows, "CPU", r.cpuCarga, "%");

        // 7. Sistema Linux (/proc)
        rows.add(new String[]{"Sistema_Linux_Proc", "CPU_IOWait_Media", fmt(r.cpuIoWaitCarga.mean, 2), "%"});
        rows.add(new String[]{"Sistema_Linux_Proc", "CPU_IOWait_Max", fmt(r.cpuIoWaitCarga.max, 2), "%"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Swap_Baseline_2s_Media", fmt(r.swapBaseline2sMediaMB, 2), "MB"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Swap_Max", fmt(r.swapCarga.max, 2), "MB"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Swap_Delta_Max_vs_2s", fmtSigned(r.swapDeltaMaxMB, 2), "MB"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Dirty_Mem_Max", fmt(r.dirtyMemCarga.max, 2), "MB"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Context_Switches_Media", fmt(r.contextSwitchesCarga.mean, 2), "ctxt/s"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Context_Switches_Max", fmt(r.contextSwitchesCarga.max, 2), "ctxt/s"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Procs_Running_Max", String.valueOf(r.procsRunningMax), "procs"});
        rows.add(new String[]{"Sistema_Linux_Proc", "Major_Page_Faults_Total", String.valueOf(r.majorPageFaultsTotal), "faults"});
        return rows;
    }

    private static void addSeriesRows(List<String[]> rows, String sec, HTAPSummaryReport.SeriesStats s, String unit) {
        rows.add(new String[]{sec, "Media", fmt(s.mean, 2), unit});
        rows.add(new String[]{sec, "Mediana_P50", fmt(s.median, 2), unit});
        rows.add(new String[]{sec, "P95", fmt(s.p95, 2), unit});
        rows.add(new String[]{sec, "P99", fmt(s.p99, 2), unit});
        rows.add(new String[]{sec, "Minimo", fmt(s.min, 2), unit});
        rows.add(new String[]{sec, "Maximo_P100", fmt(s.max, 2), unit});
        rows.add(new String[]{sec, "Desvio_Padrao", fmt(s.stdDev, 2), unit});
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    private static String fmtSigned(double v, int decimals) {
        return String.format(Locale.US, "%+." + decimals + "f", v);
    }

    private static void exportVerticalCsv(HTAPSummaryReport r, File file) throws IOException {
        try (FileWriter fw = new FileWriter(file)) {
            fw.append("Secao,Metrica,Valor,Unidade\n");
            for (String[] row : buildVerticalMetricRows(r)) {
                fw.append(sanitizeCsv(row[0])).append(",")
                  .append(sanitizeCsv(row[1])).append(",")
                  .append(sanitizeCsv(row[2])).append(",")
                  .append(sanitizeCsv(row[3])).append("\n");
            }
        }
    }

    private static void exportRunMarkdown(HTAPSummaryReport r, File file) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append(String.format(Locale.US, "# Resumo da Execução HTAP — %s (%s | %s)\n\n",
                r.baseDeDados, r.scaleFactor, r.perfil));

        md.append("## 1. Metadados & Vazão\n");
        md.append("| Métrica | Valor |\n|---|---|\n");
        md.append(String.format(Locale.US, "| Base de Dados | %s |\n", r.baseDeDados));
        md.append(String.format(Locale.US, "| Esquema / Grafo | %s |\n", r.esquema));
        md.append(String.format(Locale.US, "| Scale Factor | %s |\n", r.scaleFactor));
        md.append(String.format(Locale.US, "| Perfil | %s |\n", r.perfil));
        md.append(String.format(Locale.US, "| Sessão UTC | %s (`%s`) |\n", r.dataHoraUtc, r.sessionTimestamp));
        md.append(String.format(Locale.US, "| Clientes Concorrentes | %d |\n", r.clientesConcorrentes));
        md.append(String.format(Locale.US, "| Pedidos Solicitados | %d |\n", r.totalPedidosSolicitados));
        md.append(String.format(Locale.US, "| Tempo Total de Teste | %.3f s |\n", r.tempoTotalSegundos));
        md.append(String.format(Locale.US, "| Intervalo Telemetria | %d ms |\n", r.intervaloTelemetriaMs));
        md.append(String.format(Locale.US, "| Throughput Bruto | %.2f ops/s |\n", r.throughputGlobalOpsSec));
        md.append(String.format(Locale.US, "| Goodput Efetivo (Sucessos) | %.2f ops/s |\n\n", r.goodputEfetivoOpsSec));

        md.append("## 2. Operações Concluídas vs. Não Concluídas\n");
        md.append("| Tipo | Total | Concluídas | Falhadas | Taxa de Sucesso (%) |\n|---|---:|---:|---:|---:|\n");
        md.append(String.format(Locale.US, "| Leitura (READ) | %d | %d | %d | %.2f%% |\n",
                r.readTotal, r.readConcluidas, r.readFalhadas, r.readTaxaSucessoPct));
        md.append(String.format(Locale.US, "| Escrita (WRITE) | %d | %d | %d | %.2f%% |\n",
                r.writeTotal, r.writeConcluidas, r.writeFalhadas, r.writeTaxaSucessoPct));
        md.append(String.format(Locale.US, "| **Global** | **%d** | **%d** | **%d** | **%.2f%%** |\n\n",
                r.totalExecutadas, r.totalConcluidas, r.totalFalhadas, r.taxaSucessoGlobalPct));

        md.append("## 3. Origem das Falhas / Timeouts (Código vs. Base de Dados)\n");
        md.append("| Origem | Quantidade |\n|---|---:|\n");
        md.append(String.format(Locale.US, "| Timeout imposto pelo Código (Query) | %d |\n", r.falhasTimeoutCodigoQuery));
        md.append(String.format(Locale.US, "| Timeout imposto pelo Código (Global 10 min) | %d |\n", r.falhasTimeoutCodigoGlobal));
        md.append(String.format(Locale.US, "| Timeout da Base de Dados (BD) | %d |\n", r.falhasTimeoutBD));
        md.append(String.format(Locale.US, "| Lock Contention / Deadlock (BD) | %d |\n", r.falhasLockDeadlockBD));
        md.append(String.format(Locale.US, "| Erro Interno / Ligação (BD) | %d |\n", r.falhasErroBD));
        md.append(String.format(Locale.US, "| Outras Falhas Cliente | %d |\n\n", r.falhasOutrasCliente));

        md.append("## 4. Latência (ms) — Nota: Máx (P100) != P99\n");
        md.append("| Categoria | Média | Mediana (P50) | P95 | P99 | Mín | Máx (P100) |\n|---|---:|---:|---:|---:|---:|---:|\n");
        md.append(String.format(Locale.US, "| Global | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |\n",
                r.latenciaGlobal.mean, r.latenciaGlobal.median, r.latenciaGlobal.p95,
                r.latenciaGlobal.p99, r.latenciaGlobal.min, r.latenciaGlobal.max));
        md.append(String.format(Locale.US, "| Leitura | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |\n",
                r.latenciaLeitura.mean, r.latenciaLeitura.median, r.latenciaLeitura.p95,
                r.latenciaLeitura.p99, r.latenciaLeitura.min, r.latenciaLeitura.max));
        md.append(String.format(Locale.US, "| Escrita | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |\n\n",
                r.latenciaEscrita.mean, r.latenciaEscrita.median, r.latenciaEscrita.p95,
                r.latenciaEscrita.p99, r.latenciaEscrita.min, r.latenciaEscrita.max));

        md.append("## 5. Hardware: RAM & CPU (Baseline 2s vs. Carga)\n");
        md.append("| Métrica | Baseline (2s) | Média | Mediana (P50) | P95 | P99 | Máx (P100) |\n|---|---:|---:|---:|---:|---:|---:|\n");
        md.append(String.format(Locale.US, "| RAM (MB) | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |\n",
                r.ramBaseline2sMediaMB, r.ramCarga.mean, r.ramCarga.median,
                r.ramCarga.p95, r.ramCarga.p99, r.ramCarga.max));
        md.append(String.format(Locale.US, "| CPU Global (%%) | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |\n\n",
                r.cpuBaseline2sMediaPct, r.cpuCarga.mean, r.cpuCarga.median,
                r.cpuCarga.p95, r.cpuCarga.p99, r.cpuCarga.max));
        md.append(String.format(Locale.US, "- **Subida RAM (Delta face aos 2s):** Média = `%+.2f MB` | Máximo = `%+.2f MB`\n\n",
                r.ramDeltaMediaMB, r.ramDeltaMaxMB));

        md.append("## 6. Medidas Adicionais do Sistema Linux (`/proc`)\n");
        md.append("| Métrica | Valor |\n|---|---|\n");
        md.append(String.format(Locale.US, "| CPU I/O Wait | Média = %.2f%% / Máx = %.2f%% |\n", r.cpuIoWaitCarga.mean, r.cpuIoWaitCarga.max));
        md.append(String.format(Locale.US, "| Swap Usada | Base = %.2f MB / Máx = %.2f MB (Delta = %+.2f MB) |\n",
                r.swapBaseline2sMediaMB, r.swapCarga.max, r.swapDeltaMaxMB));
        md.append(String.format(Locale.US, "| Dirty Pages (Máx) | %.2f MB |\n", r.dirtyMemCarga.max));
        md.append(String.format(Locale.US, "| Context Switches/s | Média = %.2f/s / Máx = %.2f/s |\n",
                r.contextSwitchesCarga.mean, r.contextSwitchesCarga.max));
        md.append(String.format(Locale.US, "| Procs Running (Máx) | %d |\n", r.procsRunningMax));
        md.append(String.format(Locale.US, "| Major Page Faults (Total) | %d |\n", r.majorPageFaultsTotal));

        try (FileWriter fw = new FileWriter(file)) {
            fw.write(md.toString());
        }
    }

    private static void exportRunVerticalExcel(HTAPSummaryReport r, File file) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle sectionStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            sectionStyle.setFont(boldFont);
            sectionStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            sectionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(boldFont);
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Folha 1: Resumo em blocos compactos verticais (máx 7 colunas)
            Sheet s1 = wb.createSheet("Resumo Execucao");
            int rowIdx = 0;

            rowIdx = writeSectionBanner(s1, rowIdx, "1. METADADOS & VAZÃO", sectionStyle);
            rowIdx = writeHeaderRow(s1, rowIdx, new String[]{"Métrica", "Valor", "Unidade"}, headerStyle);
            rowIdx = writeKvRow(s1, rowIdx, "Base de Dados", r.baseDeDados, "-");
            rowIdx = writeKvRow(s1, rowIdx, "Esquema / Grafo", r.esquema, "-");
            rowIdx = writeKvRow(s1, rowIdx, "Scale Factor", r.scaleFactor, "-");
            rowIdx = writeKvRow(s1, rowIdx, "Perfil", r.perfil, "-");
            rowIdx = writeKvRow(s1, rowIdx, "Sessão UTC", r.dataHoraUtc, "UTC");
            rowIdx = writeKvRow(s1, rowIdx, "Clientes Concorrentes", r.clientesConcorrentes, "users");
            rowIdx = writeKvRow(s1, rowIdx, "Pedidos Solicitados", r.totalPedidosSolicitados, "ops");
            rowIdx = writeKvRow(s1, rowIdx, "Tempo Total Teste", r.tempoTotalSegundos, "s");
            rowIdx = writeKvRow(s1, rowIdx, "Intervalo Telemetria", r.intervaloTelemetriaMs, "ms");
            rowIdx = writeKvRow(s1, rowIdx, "Throughput Bruto", r.throughputGlobalOpsSec, "ops/s");
            rowIdx = writeKvRow(s1, rowIdx, "Goodput Efetivo (Sucessos)", r.goodputEfetivoOpsSec, "ops/s");
            rowIdx++;

            rowIdx = writeSectionBanner(s1, rowIdx, "2. OPERAÇÕES CONCLUÍDAS vs NÃO CONCLUÍDAS", sectionStyle);
            rowIdx = writeHeaderRow(s1, rowIdx, new String[]{"Tipo", "Total", "Concluídas", "Falhadas", "Sucesso (%)"}, headerStyle);
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"Leitura (READ)", r.readTotal, r.readConcluidas, r.readFalhadas, r.readTaxaSucessoPct});
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"Escrita (WRITE)", r.writeTotal, r.writeConcluidas, r.writeFalhadas, r.writeTaxaSucessoPct});
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"Global", r.totalExecutadas, r.totalConcluidas, r.totalFalhadas, r.taxaSucessoGlobalPct});
            rowIdx++;

            rowIdx = writeSectionBanner(s1, rowIdx, "3. ORIGEM DAS FALHAS / TIMEOUTS (CÓDIGO vs BD)", sectionStyle);
            rowIdx = writeHeaderRow(s1, rowIdx, new String[]{"Origem", "Operações"}, headerStyle);
            rowIdx = writeKvRow(s1, rowIdx, "Timeout imposto pelo Código (Query)", r.falhasTimeoutCodigoQuery, null);
            rowIdx = writeKvRow(s1, rowIdx, "Timeout imposto pelo Código (Global 10 min)", r.falhasTimeoutCodigoGlobal, null);
            rowIdx = writeKvRow(s1, rowIdx, "Timeout da Base de Dados (BD)", r.falhasTimeoutBD, null);
            rowIdx = writeKvRow(s1, rowIdx, "Lock Contention / Deadlock (BD)", r.falhasLockDeadlockBD, null);
            rowIdx = writeKvRow(s1, rowIdx, "Erro Interno / Ligação (BD)", r.falhasErroBD, null);
            rowIdx = writeKvRow(s1, rowIdx, "Outras Falhas Cliente", r.falhasOutrasCliente, null);
            rowIdx++;

            rowIdx = writeSectionBanner(s1, rowIdx, "4. LATÊNCIA (ms) [Máx = P100 != P99]", sectionStyle);
            rowIdx = writeHeaderRow(s1, rowIdx, new String[]{"Categoria", "Média", "Mediana (P50)", "P95", "P99", "Mín", "Máx (P100)"}, headerStyle);
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"Global", r.latenciaGlobal.mean, r.latenciaGlobal.median, r.latenciaGlobal.p95, r.latenciaGlobal.p99, r.latenciaGlobal.min, r.latenciaGlobal.max});
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"Leitura", r.latenciaLeitura.mean, r.latenciaLeitura.median, r.latenciaLeitura.p95, r.latenciaLeitura.p99, r.latenciaLeitura.min, r.latenciaLeitura.max});
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"Escrita", r.latenciaEscrita.mean, r.latenciaEscrita.median, r.latenciaEscrita.p95, r.latenciaEscrita.p99, r.latenciaEscrita.min, r.latenciaEscrita.max});
            rowIdx++;

            rowIdx = writeSectionBanner(s1, rowIdx, "5. HARDWARE: RAM (MB) & CPU (%) [Baseline 2s vs Carga]", sectionStyle);
            rowIdx = writeHeaderRow(s1, rowIdx, new String[]{"Métrica", "Baseline (2s)", "Média", "Mediana (P50)", "P95", "P99", "Máx (P100)"}, headerStyle);
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"RAM (MB)", r.ramBaseline2sMediaMB, r.ramCarga.mean, r.ramCarga.median, r.ramCarga.p95, r.ramCarga.p99, r.ramCarga.max});
            rowIdx = writeMixedRow(s1, rowIdx, new Object[]{"CPU Global (%)", r.cpuBaseline2sMediaPct, r.cpuCarga.mean, r.cpuCarga.median, r.cpuCarga.p95, r.cpuCarga.p99, r.cpuCarga.max});
            rowIdx = writeKvRow(s1, rowIdx, "Subida RAM Média vs Baseline 2s", r.ramDeltaMediaMB, "MB");
            rowIdx = writeKvRow(s1, rowIdx, "Subida RAM Máxima vs Baseline 2s", r.ramDeltaMaxMB, "MB");
            rowIdx++;

            rowIdx = writeSectionBanner(s1, rowIdx, "6. SISTEMA LINUX (/proc)", sectionStyle);
            rowIdx = writeHeaderRow(s1, rowIdx, new String[]{"Métrica", "Valor", "Unidade"}, headerStyle);
            rowIdx = writeKvRow(s1, rowIdx, "CPU I/O Wait (Média)", r.cpuIoWaitCarga.mean, "%");
            rowIdx = writeKvRow(s1, rowIdx, "CPU I/O Wait (Máx)", r.cpuIoWaitCarga.max, "%");
            rowIdx = writeKvRow(s1, rowIdx, "Swap Baseline (2s)", r.swapBaseline2sMediaMB, "MB");
            rowIdx = writeKvRow(s1, rowIdx, "Swap Máx", r.swapCarga.max, "MB");
            rowIdx = writeKvRow(s1, rowIdx, "Swap Delta Máx vs 2s", r.swapDeltaMaxMB, "MB");
            rowIdx = writeKvRow(s1, rowIdx, "Dirty Pages Máx", r.dirtyMemCarga.max, "MB");
            rowIdx = writeKvRow(s1, rowIdx, "Context Switches Média", r.contextSwitchesCarga.mean, "ctxt/s");
            rowIdx = writeKvRow(s1, rowIdx, "Context Switches Máx", r.contextSwitchesCarga.max, "ctxt/s");
            rowIdx = writeKvRow(s1, rowIdx, "Procs Running Máx", r.procsRunningMax, "procs");
            writeKvRow(s1, rowIdx, "Major Page Faults (Total)", r.majorPageFaultsTotal, "faults");

            for (int c = 0; c < 7; c++) {
                s1.autoSizeColumn(c);
            }

            // Folha 2: Lista estritamente vertical (4 colunas)
            Sheet s2 = wb.createSheet("Vertical (4 Colunas)");
            writeHeaderRow(s2, 0, new String[]{"Secao", "Metrica", "Valor", "Unidade"}, headerStyle);
            int rIdx = 1;
            for (String[] mRow : buildVerticalMetricRows(r)) {
                writeMixedRow(s2, rIdx++, mRow);
            }
            for (int c = 0; c < 4; c++) {
                s2.autoSizeColumn(c);
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                wb.write(fos);
            }
        }
    }

    private static int writeSectionBanner(Sheet sheet, int rowIdx, String title, CellStyle style) {
        Row row = sheet.createRow(rowIdx++);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(style);
        return rowIdx;
    }

    private static int writeHeaderRow(Sheet sheet, int rowIdx, String[] cols, CellStyle style) {
        Row row = sheet.createRow(rowIdx++);
        for (int i = 0; i < cols.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(style);
        }
        return rowIdx;
    }

    private static int writeKvRow(Sheet sheet, int rowIdx, String key, Object val, String unit) {
        if (unit == null) {
            return writeMixedRow(sheet, rowIdx, new Object[]{key, val});
        }
        return writeMixedRow(sheet, rowIdx, new Object[]{key, val, unit});
    }

    private static int writeMixedRow(Sheet sheet, int rowIdx, Object[] vals) {
        Row row = sheet.createRow(rowIdx++);
        for (int i = 0; i < vals.length; i++) {
            Cell c = row.createCell(i);
            Object v = vals[i];
            if (v instanceof Number) {
                c.setCellValue(((Number) v).doubleValue());
            } else {
                c.setCellValue(v == null ? "" : String.valueOf(v));
            }
        }
        return rowIdx;
    }

    public static void exportComparativeToExcel(HTAPSummaryReport r, String excelPath) {
        try {
            File file = new File(excelPath);
            Workbook workbook;
            Sheet sheet;
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    workbook = WorkbookFactory.create(fis);
                }
                sheet = workbook.getSheet("HTAP Comparative Summary");
                if (sheet == null) {
                    sheet = workbook.createSheet("HTAP Comparative Summary");
                    createComparativeExcelHeader(sheet);
                }
            } else {
                workbook = new XSSFWorkbook();
                sheet = workbook.createSheet("HTAP Comparative Summary");
                createComparativeExcelHeader(sheet);
            }

            int nextRow = sheet.getLastRowNum() + 1;
            Row row = sheet.createRow(nextRow);
            Object[] values = {
                r.sessionTimestamp, r.dataHoraUtc, r.baseDeDados, r.esquema, r.scaleFactor, r.perfil,
                r.clientesConcorrentes, r.totalPedidosSolicitados, r.tempoTotalSegundos, r.intervaloTelemetriaMs,
                r.totalExecutadas, r.totalConcluidas, r.totalFalhadas, r.taxaSucessoGlobalPct,
                r.readTotal, r.readConcluidas, r.readFalhadas, r.readTaxaSucessoPct,
                r.writeTotal, r.writeConcluidas, r.writeFalhadas, r.writeTaxaSucessoPct,
                r.throughputGlobalOpsSec, r.goodputEfetivoOpsSec,
                r.falhasTimeoutCodigoQuery, r.falhasTimeoutCodigoGlobal, r.getTotalFalhasTimeoutCodigo(),
                r.falhasTimeoutBD, r.falhasLockDeadlockBD, r.falhasErroBD, r.getTotalFalhasBD(), r.falhasOutrasCliente,
                r.latenciaGlobal.mean, r.latenciaGlobal.median, r.latenciaGlobal.p95, r.latenciaGlobal.p99,
                r.latenciaGlobal.min, r.latenciaGlobal.max, r.latenciaGlobal.stdDev,
                r.latenciaLeitura.mean, r.latenciaLeitura.median, r.latenciaLeitura.p95, r.latenciaLeitura.p99, r.latenciaLeitura.max,
                r.latenciaEscrita.mean, r.latenciaEscrita.median, r.latenciaEscrita.p95, r.latenciaEscrita.p99, r.latenciaEscrita.max,
                r.ramBaseline2sMediaMB, r.ramCarga.mean, r.ramCarga.median, r.ramCarga.p95, r.ramCarga.p99, r.ramCarga.max,
                r.ramDeltaMediaMB, r.ramDeltaMaxMB,
                r.cpuBaseline2sMediaPct, r.cpuCarga.mean, r.cpuCarga.median, r.cpuCarga.p95, r.cpuCarga.p99, r.cpuCarga.max,
                r.cpuIoWaitCarga.mean, r.cpuIoWaitCarga.max,
                r.swapBaseline2sMediaMB, r.swapCarga.max, r.swapDeltaMaxMB,
                r.dirtyMemCarga.max, r.contextSwitchesCarga.mean, r.contextSwitchesCarga.max,
                r.procsRunningMax, r.majorPageFaultsTotal
            };
            for (int i = 0; i < values.length; i++) {
                Cell cell = row.createCell(i);
                Object v = values[i];
                if (v instanceof Number) {
                    cell.setCellValue(((Number) v).doubleValue());
                } else {
                    cell.setCellValue(String.valueOf(v));
                }
            }
            for (int i = 0; i < COMPARATIVE_COLUMNS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
            workbook.close();
            System.out.println("-> Folha comparativa atualizada em: " + excelPath);
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar tabela comparativa para Excel: " + e.getMessage());
        }
    }

    private static void createComparativeExcelHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < COMPARATIVE_COLUMNS.length; i++) {
            header.createCell(i).setCellValue(COMPARATIVE_COLUMNS[i]);
        }
    }

    /**
     * Formata uma tabela completa legível para terminal e ficheiro summary_table.txt.
     */
    public static String formatConsoleTable(HTAPSummaryReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================================================================================\n");
        sb.append(String.format(Locale.US, " TABELA FINAL COMPARATIVA HTAP — %s (%s | %s)\n",
                r.baseDeDados, r.scaleFactor, r.perfil));
        sb.append("================================================================================\n");
        sb.append(String.format(Locale.US, " Sessão UTC       : %s (ID: %s)\n", r.dataHoraUtc, r.sessionTimestamp));
        sb.append(String.format(Locale.US, " Esquema / Grafo  : %s\n", r.esquema));
        sb.append(String.format(Locale.US, " Clientes (Users) : %d concorrentes | Pedidos Solicitados: %d\n",
                r.clientesConcorrentes, r.totalPedidosSolicitados));
        sb.append(String.format(Locale.US, " Tempo Total Teste: %.3f s | Telemetria: %d ms\n",
                r.tempoTotalSegundos, r.intervaloTelemetriaMs));
        sb.append(String.format(Locale.US, " Throughput Raw   : %.2f ops/s | Goodput (Sucessos): %.2f ops/s\n",
                r.throughputGlobalOpsSec, r.goodputEfetivoOpsSec));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [1] OPERAÇÕES CONCLUÍDAS vs NÃO CONCLUÍDAS\n");
        sb.append(String.format(Locale.US, " %-14s | %8s | %10s | %10s | %10s\n",
                "Tipo", "Total", "Concluídas", "Falhadas", "Sucesso %"));
        sb.append(String.format(Locale.US, " %-14s | %8d | %10d | %10d | %9.2f%%\n",
                "Leitura (READ)", r.readTotal, r.readConcluidas, r.readFalhadas, r.readTaxaSucessoPct));
        sb.append(String.format(Locale.US, " %-14s | %8d | %10d | %10d | %9.2f%%\n",
                "Escrita (WRITE)", r.writeTotal, r.writeConcluidas, r.writeFalhadas, r.writeTaxaSucessoPct));
        sb.append(String.format(Locale.US, " %-14s | %8d | %10d | %10d | %9.2f%%\n",
                "Global", r.totalExecutadas, r.totalConcluidas, r.totalFalhadas, r.taxaSucessoGlobalPct));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [2] ORIGEM DAS FALHAS / TIMEOUTS (CÓDIGO vs BASE DE DADOS)\n");
        sb.append(String.format(Locale.US, " - Timeout imposto pelo Código (Query Hard Limit) : %d\n", r.falhasTimeoutCodigoQuery));
        sb.append(String.format(Locale.US, " - Timeout imposto pelo Código (Global 10 min)    : %d\n", r.falhasTimeoutCodigoGlobal));
        sb.append(String.format(Locale.US, " - Timeout da Base de Dados (BD)                  : %d\n", r.falhasTimeoutBD));
        sb.append(String.format(Locale.US, " - Lock Contention / Deadlock / Serialization (BD): %d\n", r.falhasLockDeadlockBD));
        sb.append(String.format(Locale.US, " - Erro Interno / Overload / Ligação (BD)         : %d\n", r.falhasErroBD));
        sb.append(String.format(Locale.US, " - Outras Falhas Cliente                          : %d\n", r.falhasOutrasCliente));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [3] ESTATÍSTICAS DE LATÊNCIA (ms) [Max = P100 absoluto != P99]\n");
        sb.append(String.format(Locale.US, " %-14s | %9s | %9s | %9s | %9s | %9s | %9s\n",
                "Categoria", "Média", "Mediana", "P95", "P99", "Mín", "Máx(P100)"));
        sb.append(String.format(Locale.US, " %-14s | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f\n",
                "Global", r.latenciaGlobal.mean, r.latenciaGlobal.median, r.latenciaGlobal.p95,
                r.latenciaGlobal.p99, r.latenciaGlobal.min, r.latenciaGlobal.max));
        sb.append(String.format(Locale.US, " %-14s | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f\n",
                "Leitura", r.latenciaLeitura.mean, r.latenciaLeitura.median, r.latenciaLeitura.p95,
                r.latenciaLeitura.p99, r.latenciaLeitura.min, r.latenciaLeitura.max));
        sb.append(String.format(Locale.US, " %-14s | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f\n",
                "Escrita", r.latenciaEscrita.mean, r.latenciaEscrita.median, r.latenciaEscrita.p95,
                r.latenciaEscrita.p99, r.latenciaEscrita.min, r.latenciaEscrita.max));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [4] HARDWARE: RAM (MB) E CPU (%) [Baseline 2s vs Carga]\n");
        sb.append(String.format(Locale.US, " %-16s | %9s | %9s | %9s | %9s | %9s | %9s\n",
                "Métrica", "Base(2s)", "Média", "Mediana", "P95", "P99", "Máx(P100)"));
        sb.append(String.format(Locale.US, " %-16s | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f\n",
                "RAM (MB)", r.ramBaseline2sMediaMB, r.ramCarga.mean, r.ramCarga.median,
                r.ramCarga.p95, r.ramCarga.p99, r.ramCarga.max));
        sb.append(String.format(Locale.US, " -> Subida RAM (Delta face aos 2s): Média = %+.2f MB | Máximo = %+.2f MB\n",
                r.ramDeltaMediaMB, r.ramDeltaMaxMB));
        sb.append(String.format(Locale.US, " %-16s | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f | %9.2f\n",
                "CPU Global (%)", r.cpuBaseline2sMediaPct, r.cpuCarga.mean, r.cpuCarga.median,
                r.cpuCarga.p95, r.cpuCarga.p99, r.cpuCarga.max));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [5] MEDIDAS ADICIONAIS DO SISTEMA LINUX (/proc)\n");
        sb.append(String.format(Locale.US, " - CPU I/O Wait (%%)  : Média = %.2f%% | Máximo = %.2f%%\n",
                r.cpuIoWaitCarga.mean, r.cpuIoWaitCarga.max));
        sb.append(String.format(Locale.US, " - Swap Usada (MB)    : Baseline = %.2f MB | Máximo = %.2f MB (Delta = %+.2f MB)\n",
                r.swapBaseline2sMediaMB, r.swapCarga.max, r.swapDeltaMaxMB));
        sb.append(String.format(Locale.US, " - Dirty Pages Máx    : %.2f MB\n", r.dirtyMemCarga.max));
        sb.append(String.format(Locale.US, " - Context Switches/s : Média = %.2f/s | Máximo = %.2f/s\n",
                r.contextSwitchesCarga.mean, r.contextSwitchesCarga.max));
        sb.append(String.format(Locale.US, " - Procs Running Máx  : %d | Major Page Faults (Total): %d\n",
                r.procsRunningMax, r.majorPageFaultsTotal));
        sb.append("================================================================================\n");
        return sb.toString();
    }

    public static void saveRFIRun(String sessionTimestamp, String engine, String schema, String queryId,
            String targetKey, String runType, String startTimestamp, String endTimestamp,
            long latencyMs, int numQuery, double deltaRam, double rfi) {
        try {
            File dir = new File("./metrics");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            ++numQuery;

            File file = new File(dir, "rfi_metrics_QUERY-" + numQuery + "_" + engine.toLowerCase() + "_" + sessionTimestamp + ".csv");
            boolean isNewFile = !file.exists();

            try (FileWriter fw = new FileWriter(file, true)) {
                if (isNewFile) {
                    fw.append("Start_Timestamp,End_Timestamp,Engine,Schema,Query_ID,Target_Key,Run_Type,Latency_ms,Delta_RAM_MB,RFI\n");
                }

                fw.append(String.format(Locale.US, "%s,%s,%s,%s,Q%s,%s,%s,%d,%.6f,%.4f\n",
                        startTimestamp, endTimestamp, engine, schema, queryId, targetKey, runType, latencyMs, deltaRam, rfi));
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha na escrita linha a linha do CSV RFI: " + e.getMessage());
        }
    }
}
