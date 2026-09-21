package deepchainbench.core;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;

public class MetricsExporter {

    private static final String EXCEL_PATH = "./benchmark_results.xlsx";

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
            row.createCell(3).setCellValue(startTimestamp); // Nova coluna
            row.createCell(4).setCellValue(endTimestamp);   // Nova coluna
            row.createCell(5).setCellValue(durationMs / 1000.0); // Converte para segundos
            row.createCell(6).setCellValue(datasetSizeBytes / (1024.0 * 1024.0)); // MB
            row.createCell(7).setCellValue(dbSizeBytes / (1024.0 * 1024.0)); // MB

            // Auto-ajustar colunas (agora são 8 colunas no total)
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

//    public static void saveRFIMetrics(String engine, String schema, String queryId, String targetKey, long coldRunTime, java.util.List<Long> warmRunTimes) {
//        try {
//            // Garante que a pasta de output existe
//            java.io.File dir = new java.io.File("./metrics");
//            if (!dir.exists()) {
//                dir.mkdirs();
//            }
//
//            java.io.File file = new java.io.File(dir, "rfi_metrics_" + engine.toLowerCase() + ".csv");
//            boolean isNewFile = !file.exists();
//
//            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
//                // Injeta o cabeçalho se o ficheiro for novo
//                if (isNewFile) {
//                    fw.append("Timestamp,Engine,Schema,Query_ID,Target_Key,Cold_Run_ms,Avg_Warm_Run_ms,All_Warm_Runs\n");
//                }
//
//                // Formatação UTC (GMT-0) rigorosa
//                String timestamp = String.valueOf(System.currentTimeMillis());
//
//                // Cálculo matemático da média de memoization
//                double avgWarm = warmRunTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
//
//                // Substitui as vírgulas do array original por ponto e vírgula para não corromper o CSV
//                String warmRunsStr = warmRunTimes.toString().replace(",", ";");
//
//                // Grava a linha
//                fw.append(String.format(java.util.Locale.US, "%s,%s,%s,Q%s,%s,%d,%.2f,%s\n",
//                        timestamp, engine, schema, queryId, targetKey, coldRunTime, avgWarm, warmRunsStr));
//            }
//            System.out.println("   [MetricsExporter] Métricas RFI gravadas com sucesso em: " + file.getPath());
//
//        } catch (Exception e) {
//            System.err.println("[ERRO] Falha na escrita do CSV de RFI: " + e.getMessage());
//        }
//    }

public static void saveRFIRun(String sessionTimestamp, String engine, String schema, String queryId, String targetKey, String runType, String startTimestamp, String endTimestamp, long latencyMs, int numQuery, double deltaRam, double rfi) {
        try {
            java.io.File dir = new java.io.File("./metrics");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            ++numQuery;
            
            java.io.File file = new java.io.File(dir, "rfi_metrics_QUERY-" + numQuery + "_" + engine.toLowerCase() + "_" + sessionTimestamp + ".csv");
            boolean isNewFile = !file.exists();

            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                // Adicionada a coluna RFI no final
                if (isNewFile) {
                    fw.append("Start_Timestamp,End_Timestamp,Engine,Schema,Query_ID,Target_Key,Run_Type,Latency_ms,Delta_RAM_MB,RFI\n");
                }

                // Grava a linha formatada (%.4f para guardar o RFI com 4 casas decimais)
                fw.append(String.format(java.util.Locale.US, "%s,%s,%s,%s,Q%s,%s,%s,%d,%.6f,%.4f\n",
                        startTimestamp, endTimestamp, engine, schema, queryId, targetKey, runType, latencyMs, deltaRam, rfi));
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha na escrita linha a linha do CSV RFI: " + e.getMessage());
        }
    }
}
