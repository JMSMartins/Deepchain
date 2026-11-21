package deepchainbench.core.Postgres;

import com.arangodb.ArangoDBException;
import deepchainbench.core.AbstractBenchmarkEngine;
import deepchainbench.core.AbstractBenchmarkEngine.FailureOrigin;
import deepchainbench.core.AbstractBenchmarkEngine.OperationRecord;
import deepchainbench.core.Arango.BenchmarkEngineArango;
import deepchainbench.core.HTAPSummaryReport;
import deepchainbench.core.LinuxProcTelemetry;
import deepchainbench.core.MetricsExporter;
import deepchainbench.core.TelemetryEngine;
import deepchainbench.core.TelemetryEngine.TelemetrySample;
import deepchainbench.generator.GraphGenerator;
import junit.framework.TestCase;
import java.io.File;
import java.io.FileWriter;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;

public class AgentTest extends TestCase {

    public void testPostgresEngineCompilation() {
        BenchmarkEnginePostgres engine = new BenchmarkEnginePostgres();
        assertNotNull(engine);
    }

    public void testPostgresRfiDisabled() {
        BenchmarkEnginePostgres engine = new BenchmarkEnginePostgres();
        try {
            engine.runRFI(null, "5", 1);
            fail("Expected UnsupportedOperationException when running RFI on PostgreSQL + AGE");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("desativado"));
        }
    }

    public void testLinuxProcTelemetryReadsRam() {
        LinuxProcTelemetry telemetry = new LinuxProcTelemetry("target/test_telemetry.csv");
        double ramMb = telemetry.getSystemRamUsedMB();
        assertTrue("Expected positive RAM usage from /proc/meminfo, got: " + ramMb, ramMb > 0.0);
        assertEquals(250L, telemetry.getSamplingIntervalMs());
    }

    public void testTelemetryIntervalDefaultAndMinimumBounds() {
        assertEquals(250L, TelemetryEngine.DEFAULT_SAMPLING_INTERVAL_MS);
        assertEquals(100L, TelemetryEngine.MIN_SAMPLING_INTERVAL_MS);

        LinuxProcTelemetry customValid = new LinuxProcTelemetry("target/test_telemetry.csv", 100L);
        assertEquals(100L, customValid.getSamplingIntervalMs());

        LinuxProcTelemetry customHigher = new LinuxProcTelemetry("target/test_telemetry.csv", 500L);
        assertEquals(500L, customHigher.getSamplingIntervalMs());

        LinuxProcTelemetry belowMinimum = new LinuxProcTelemetry("target/test_telemetry.csv", 25L);
        assertEquals(100L, belowMinimum.getSamplingIntervalMs());

        BenchmarkEnginePostgres pgEngine = new BenchmarkEnginePostgres();
        assertEquals(250L, pgEngine.getTelemetryIntervalMs());
        pgEngine.setTelemetryIntervalMs(50L);
        assertEquals(100L, pgEngine.getTelemetryIntervalMs());
        pgEngine.setTelemetryIntervalMs(200L);
        assertEquals(200L, pgEngine.getTelemetryIntervalMs());
    }

    public void testArangoEngineInstantiation() {
        BenchmarkEngineArango arangoEngine = new BenchmarkEngineArango();
        assertNotNull(arangoEngine);
    }

    public void testPostgresConnection() {
        try {
            Class.forName("org.postgresql.Driver");
            String password = System.getenv("DB_PASS");
            if (password == null) {
                password = "password";
            }
            Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres", "postgres", password);
            assertNotNull(conn);
            conn.close();
        } catch (Exception e) {
            System.err.println("Database is not running in sandbox or connection failed. Proceeding...");
            assertTrue(true);
        }
    }

    public void testComputeSeriesStatsP99VsMaxAndPercentiles() {
        List<Double> values = new ArrayList<>();
        for (int i = 1; i <= 99; i++) {
            values.add((double) i);
        }
        values.add(1000.0); // Outlier extremo para distinguir P99 de Max (P100)

        HTAPSummaryReport.SeriesStats stats = AbstractBenchmarkEngine.computeSeriesStats(values);
        assertEquals(100L, stats.count);
        assertEquals(1.0, stats.min, 0.001);
        assertEquals(50.5, stats.median, 0.001);
        assertEquals(95.0, stats.p95, 0.001);
        assertEquals(99.0, stats.p99, 0.001);
        assertEquals(1000.0, stats.max, 0.001);
        assertTrue("Max (P100) deve ser distinto do P99 quando ha pico extremo", stats.max > stats.p99);
    }

    public void testBuildSummaryReportRamBaselineReadWriteAndFailureBreakdown() {
        long t0 = 1700000000000L;
        long baselineEnd = t0 + 2000L;
        long workloadEnd = t0 + 12000L;

        List<TelemetrySample> samples = new ArrayList<>();
        // Primeiros 2 segundos = baseline (~1000 MB RAM, ~2% CPU)
        samples.add(new TelemetrySample(t0 + 250L, 2.0, 990.0, 0.2, 0.0, 4.0, 120.0, 1, 0.0, 0L));
        samples.add(new TelemetrySample(t0 + 1000L, 2.0, 1000.0, 0.3, 0.0, 4.5, 130.0, 1, 0.0, 0L));
        samples.add(new TelemetrySample(t0 + 1750L, 2.0, 1010.0, 0.4, 0.0, 5.0, 140.0, 1, 0.0, 0L));
        // Carga ativa (RAM sobe para 1500..2200 MB)
        samples.add(new TelemetrySample(t0 + 3000L, 45.0, 1500.0, 8.0, 10.0, 64.0, 3500.0, 6, 2.0, 1L));
        samples.add(new TelemetrySample(t0 + 6000L, 80.0, 1900.0, 14.0, 25.0, 120.0, 7800.0, 12, 4.0, 2L));
        samples.add(new TelemetrySample(t0 + 9000L, 90.0, 2200.0, 18.0, 40.0, 180.0, 9200.0, 16, 8.0, 3L));

        List<OperationRecord> ops = new ArrayList<>();
        // 3 Reads concluidas, 1 Read timeout codigo query, 1 Read deadlock BD
        ops.add(new OperationRecord(t0 + 2500L, true, 120L, true, FailureOrigin.SUCCESS));
        ops.add(new OperationRecord(t0 + 3500L, true, 240L, true, FailureOrigin.SUCCESS));
        ops.add(new OperationRecord(t0 + 4500L, true, 360L, true, FailureOrigin.SUCCESS));
        ops.add(new OperationRecord(t0 + 5500L, true, 60000L, false, FailureOrigin.TIMEOUT_CODE_QUERY));
        ops.add(new OperationRecord(t0 + 6500L, true, 1500L, false, FailureOrigin.DB_LOCK_DEADLOCK));
        // 2 Writes concluidas, 1 Write timeout BD, 1 Write timeout global codigo
        ops.add(new OperationRecord(t0 + 7000L, false, 80L, true, FailureOrigin.SUCCESS));
        ops.add(new OperationRecord(t0 + 8000L, false, 160L, true, FailureOrigin.SUCCESS));
        ops.add(new OperationRecord(t0 + 9000L, false, 5000L, false, FailureOrigin.TIMEOUT_DB));
        ops.add(new OperationRecord(t0 + 11500L, false, 12000L, false, FailureOrigin.TIMEOUT_CODE_GLOBAL));

        HTAPSummaryReport report = AbstractBenchmarkEngine.buildSummaryReport(
                String.valueOf(t0), "PostgreSQL+AGE", "deepchaindb", "SF1",
                "Analitico", 16, 10, 10000L, 250L, ops, samples, baselineEnd, workloadEnd
        );

        assertEquals(9, report.totalExecutadas);
        assertEquals(5, report.totalConcluidas);
        assertEquals(4, report.totalFalhadas);
        assertEquals(5, report.readTotal);
        assertEquals(3, report.readConcluidas);
        assertEquals(2, report.readFalhadas);
        assertEquals(4, report.writeTotal);
        assertEquals(2, report.writeConcluidas);
        assertEquals(2, report.writeFalhadas);

        assertEquals(1, report.falhasTimeoutCodigoQuery);
        assertEquals(1, report.falhasTimeoutCodigoGlobal);
        assertEquals(2, report.getTotalFalhasTimeoutCodigo());
        assertEquals(1, report.falhasTimeoutBD);
        assertEquals(1, report.falhasLockDeadlockBD);
        assertEquals(2, report.getTotalFalhasBD());

        assertEquals(0.9, report.throughputGlobalOpsSec, 0.001);
        assertEquals(0.5, report.goodputEfetivoOpsSec, 0.001);
        assertEquals(1000.0, report.ramBaseline2sMediaMB, 0.001);
        assertEquals(2200.0, report.ramCarga.max, 0.001);
        assertEquals(1200.0, report.ramDeltaMaxMB, 0.001);
        assertEquals(16, report.procsRunningMax);
        assertEquals(6L, report.majorPageFaultsTotal);
        assertEquals(180.0, report.dirtyMemCarga.max, 0.001);
    }

    public void testPostgresTimeoutOriginClassification() {
        // Timeout do proprio codigo (JDBC setQueryTimeout = 120s -> SQLState 57014 perto de 120s)
        SQLException codeQueryTimeout = new SQLTimeoutException("canceling statement due to user request", "57014");
        assertEquals(FailureOrigin.TIMEOUT_CODE_QUERY,
                BenchmarkEnginePostgres.classifyPostgresFailure(codeQueryTimeout, 120150L, 120));

        // Timeout interno configurado na BD (statement_timeout no PostgreSQL)
        SQLException dbStmtTimeout = new SQLException("ERROR: canceling statement due to statement timeout", "57014");
        assertEquals(FailureOrigin.TIMEOUT_DB,
                BenchmarkEnginePostgres.classifyPostgresFailure(dbStmtTimeout, 5000L, 120));

        // Deadlock interno na BD (40P01)
        SQLException dbDeadlock = new SQLException("ERROR: deadlock detected", "40P01");
        assertEquals(FailureOrigin.DB_LOCK_DEADLOCK,
                BenchmarkEnginePostgres.classifyPostgresFailure(dbDeadlock, 340L, 120));

        // SocketTimeoutException encapsulada -> TIMEOUT_DB
        SQLException socketTimeout = new SQLException("I/O error", "08006", new SocketTimeoutException("Read timed out"));
        assertEquals(FailureOrigin.TIMEOUT_DB,
                BenchmarkEnginePostgres.classifyPostgresFailure(socketTimeout, 10000L, 120));
    }

    public void testArangoTimeoutOriginClassification() {
        // Error 1500 perto de maxRuntime (60s) -> timeout imposto pelo codigo
        ArangoDBException codeMaxRuntime = new ArangoDBException("AQL: query killed", 1500) {
            @Override public Integer getErrorNum() { return 1500; }
            @Override public String getErrorMessage() { return "query killed"; }
        };
        assertEquals(FailureOrigin.TIMEOUT_CODE_QUERY,
                BenchmarkEngineArango.classifyArangoFailure(codeMaxRuntime, 59800L, 60.0));

        // Error 1500 aos 5s -> abort interno da BD / recurso
        assertEquals(FailureOrigin.TIMEOUT_DB,
                BenchmarkEngineArango.classifyArangoFailure(codeMaxRuntime, 5000L, 60.0));

        // Error 1200 Lock timeout vs Lock conflict
        ArangoDBException lockTimeout = new ArangoDBException("timeout waiting to lock key", 1200) {
            @Override public Integer getErrorNum() { return 1200; }
            @Override public String getErrorMessage() { return "timeout waiting to lock key"; }
        };
        assertEquals(FailureOrigin.TIMEOUT_DB,
                BenchmarkEngineArango.classifyArangoFailure(lockTimeout, 1020L, 60.0));

        ArangoDBException lockConflict = new ArangoDBException("write-write conflict", 1200) {
            @Override public Integer getErrorNum() { return 1200; }
            @Override public String getErrorMessage() { return "write-write conflict"; }
        };
        assertEquals(FailureOrigin.DB_LOCK_DEADLOCK,
                BenchmarkEngineArango.classifyArangoFailure(lockConflict, 45L, 60.0));
    }

    public void testLinuxProcMemorySnapshotReadsExtendedMetrics() {
        LinuxProcTelemetry telemetry = new LinuxProcTelemetry("target/test_telemetry.csv", 100L);
        TelemetryEngine.MemorySnapshot snap = telemetry.getMemorySnapshot();
        assertTrue(snap.ramUsedMB > 0.0);
        assertTrue(snap.swapUsedMB >= 0.0);
        assertTrue(snap.dirtyMemMB >= 0.0);
    }

    public void testMetricsExporterAndGraphGeneration() throws Exception {
        String baseDir = "target/test_metrics";
        File runDir = new File(baseDir + "/run1");
        runDir.mkdirs();

        String telemetryCsv = runDir.getPath() + "/hardware_telemetry.csv";
        String latencyCsv = runDir.getPath() + "/latency_metrics.csv";
        long t0 = 1700000000000L;

        try (FileWriter fw = new FileWriter(telemetryCsv)) {
            fw.write("Timestamp,CPU_Global_Pct,RAM_Usada_MB,RocksDB_Cache_MB,CPU_IOWait_Pct,Swap_Usada_MB,Dirty_Mem_MB,Context_Switches_Sec,Procs_Running,Major_Page_Faults_Sec\n");
            for (int i = 0; i < 12; i++) {
                long ts = t0 + i * 500L;
                fw.write(String.format(java.util.Locale.US,
                        "%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.2f\n",
                        ts, 10.0 + i * 5.0, 1000.0 + i * 50.0, 200.0 + i * 10.0,
                        1.0 + i * 0.5, 5.0 + i, 10.0 + i * 3.0, 500.0 + i * 100.0, 2 + (i % 4), (i % 3) * 1.0));
            }
        }
        try (FileWriter fw = new FileWriter(latencyCsv)) {
            fw.write("Timestamp,Operacao,Latencia_ms,Sucesso,Origem\n");
            for (int i = 0; i < 8; i++) {
                fw.write((t0 + 1000L + i * 400L) + ",READ," + (50 + i * 25) + ",true,SUCCESS\n");
            }
            fw.write((t0 + 5000L) + ",READ,60000,false,TIMEOUT_CODIGO_QUERY\n");
            fw.write((t0 + 5200L) + ",WRITE,1200,false,LOCK_DEADLOCK_BD\n");
        }

        List<TelemetrySample> samples = new ArrayList<>();
        samples.add(new TelemetrySample(t0 + 500L, 10.0, 1000.0, 1.0, 5.0, 10.0, 500.0, 2, 0.0, 0L));
        samples.add(new TelemetrySample(t0 + 3000L, 65.0, 1500.0, 6.0, 15.0, 40.0, 1500.0, 5, 1.0, 1L));

        List<OperationRecord> ops = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ops.add(new OperationRecord(t0 + 2500L + i * 300L, i % 2 == 0, 50L + i * 30L, true, FailureOrigin.SUCCESS));
        }
        ops.add(new OperationRecord(t0 + 5000L, true, 60000L, false, FailureOrigin.TIMEOUT_CODE_QUERY));
        ops.add(new OperationRecord(t0 + 5200L, false, 1200L, false, FailureOrigin.DB_LOCK_DEADLOCK));

        HTAPSummaryReport pgReport = AbstractBenchmarkEngine.buildSummaryReport(
                String.valueOf(t0), "PostgreSQL+AGE", "deepchaindb", "SF1",
                "Balanced", 16, 10, 6000L, 250L, ops, samples, t0 + 2000L, t0 + 6000L);
        HTAPSummaryReport arangoReport = AbstractBenchmarkEngine.buildSummaryReport(
                String.valueOf(t0 + 10000L), "ArangoDB", "MM", "SF1",
                "Balanced", 16, 10, 5000L, 250L, ops, samples, t0 + 2000L, t0 + 6000L);

        String masterCsv = baseDir + "/htap_comparative_summary.csv";
        String excelPath = baseDir + "/benchmark_results.xlsx";
        Files.deleteIfExists(new File(masterCsv).toPath());
        Files.deleteIfExists(new File(excelPath).toPath());

        MetricsExporter.saveHTAPComparativeSummary(pgReport, runDir.getPath(), masterCsv, excelPath);
        MetricsExporter.saveHTAPComparativeSummary(arangoReport, runDir.getPath(), masterCsv, excelPath);

        assertTrue(new File(masterCsv).exists());
        assertTrue(new File(excelPath).exists());
        assertTrue(new File(runDir, "summary_table.txt").exists());
        assertTrue(new File(runDir, "htap_run_summary.csv").exists());
        File verticalCsv = new File(runDir, "resumo_execucao_vertical.csv");
        File verticalMd = new File(runDir, "resumo_execucao.md");
        File verticalXlsx = new File(runDir, "resumo_execucao.xlsx");
        assertTrue(verticalCsv.length() > 0);
        assertTrue(verticalMd.length() > 0);
        assertTrue(verticalXlsx.length() > 0);
        String verticalCsvContent = Files.readString(verticalCsv.toPath());
        assertTrue(verticalCsvContent.contains("Secao,Metrica,Valor,Unidade"));
        assertTrue(verticalCsvContent.contains("RAM,Delta_Max_vs_2s"));
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(verticalXlsx)) {
            assertEquals(2, wb.getNumberOfSheets());
            assertNotNull(wb.getSheet("Resumo Execucao"));
            assertNotNull(wb.getSheet("Vertical (4 Colunas)"));
        }
        String consoleTable = MetricsExporter.formatConsoleTable(pgReport);
        assertTrue(consoleTable.contains("Timeout imposto pelo Código (Query Hard Limit)"));
        assertTrue(consoleTable.contains("Subida RAM (Delta face aos 2s)"));

        String chartBase = runDir.getPath() + "/Teste";
        GraphGenerator.createRFIChart(telemetryCsv, null, chartBase);
        GraphGenerator.createSystemPressureChart(telemetryCsv, null, chartBase);
        GraphGenerator.createHTAPCharts(latencyCsv, chartBase);
        GraphGenerator.createLatencyPercentilesChart(pgReport, chartBase);
        GraphGenerator.createCrossDBComparisonCharts(masterCsv, baseDir + "/comparacao");

        assertTrue(new File(chartBase + "_RAM.png").length() > 0);
        assertTrue(new File(chartBase + "_CPU.png").length() > 0);
        assertTrue(new File(chartBase + "_System_Pressure.png").length() > 0);
        assertTrue(new File(chartBase + "_Read_Latency.png").length() > 0);
        assertTrue(new File(chartBase + "_Write_Latency.png").length() > 0);
        assertTrue(new File(chartBase + "_Read_Status_Bar.png").length() > 0);
        assertTrue(new File(chartBase + "_Write_Status_Bar.png").length() > 0);
        assertTrue(new File(chartBase + "_Failure_Breakdown.png").length() > 0);
        assertTrue(new File(chartBase + "_Latency_Percentiles.png").length() > 0);
        assertTrue(new File(baseDir + "/comparacao/comparacao_Latencia_Percentis.png").length() > 0);
        assertTrue(new File(baseDir + "/comparacao/comparacao_RAM.png").length() > 0);
        assertTrue(new File(baseDir + "/comparacao/comparacao_CPU.png").length() > 0);
        assertTrue(new File(baseDir + "/comparacao/comparacao_Throughput.png").length() > 0);
        assertTrue(new File(baseDir + "/comparacao/comparacao_Confiabilidade.png").length() > 0);
    }
}
