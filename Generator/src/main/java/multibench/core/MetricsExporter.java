package multibench.core;

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
                    if (f.isFile()) totalSize += f.length();
                }
            }
        }
        return totalSize;
    }
}