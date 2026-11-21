package deepchainbench.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public abstract class AbstractBenchmarkEngine<T> {

    /**
     * Classificação explícita da origem de sucesso/falha de cada operação HTAP,
     * distinguindo timeouts impostos pelo código do benchmark de timeouts/falhas da BD.
     */
    public enum FailureOrigin {
        SUCCESS("SUCESSO"),
        TIMEOUT_CODE_QUERY("TIMEOUT_CODIGO_QUERY"),
        TIMEOUT_CODE_GLOBAL("TIMEOUT_CODIGO_GLOBAL"),
        TIMEOUT_DB("TIMEOUT_BD"),
        DB_LOCK_DEADLOCK("LOCK_DEADLOCK_BD"),
        DB_RESOURCE_OR_ERROR("ERRO_BD"),
        CLIENT_OR_OTHER("ERRO_CLIENTE");

        private final String label;

        FailureOrigin(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * Registo granular de cada operação executada durante o ataque HTAP.
     */
    public static class OperationRecord {
        public final long timestampMs;
        public final boolean isRead;
        public final long latencyMs;
        public final boolean success;
        public final FailureOrigin failureOrigin;

        public OperationRecord(long timestampMs, boolean isRead, long latencyMs, boolean success, FailureOrigin failureOrigin) {
            this.timestampMs = timestampMs;
            this.isRead = isRead;
            this.latencyMs = latencyMs;
            this.success = success;
            this.failureOrigin = failureOrigin;
        }
    }

    // 1. VARIÁVEIS PARTILHADAS
    protected List<String> partKeysPool = new ArrayList<>();
    protected final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> falhasContencao = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> latencyLogs = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> executionLogs = Collections.synchronizedList(new ArrayList<>());
    protected final List<OperationRecord> operationRecords = Collections.synchronizedList(new ArrayList<>());

    protected int sfGlobalAtivo = 1;
    protected long telemetryIntervalMs = TelemetryEngine.DEFAULT_SAMPLING_INTERVAL_MS;
    protected static final String[] TARGETS_ANALITICOS = {"500", "1300", "800", "799", "1599", "0"};

    public void setTelemetryIntervalMs(long intervalMs) {
        this.telemetryIntervalMs = TelemetryEngine.validateSamplingInterval(intervalMs);
    }

    public long getTelemetryIntervalMs() {
        return this.telemetryIntervalMs;
    }

    // 2. MÉTODOS ABSTRATOS (Obriga as classes filhas a implementar)
    public abstract void warmUpAndHarvest(T db);
    public abstract void runWorkload(T db, String perfilNome, int[] probsLeitura, int[] probsEscrita, int readPercentage, int numClientes);
    public abstract void limparDadosTemporarios(T db);
    public abstract void runRFI(T db, String queryId, int warmRuns);

    public void runWorkload(T db, String perfilNome, int[] probsLeitura, int[] probsEscrita, int readPercentage, int numClientes, long samplingIntervalMs) {
        setTelemetryIntervalMs(samplingIntervalMs);
        runWorkload(db, perfilNome, probsLeitura, probsEscrita, readPercentage, numClientes);
    }

    public void runRFI(T db, String queryId, int warmRuns, long samplingIntervalMs) {
        setTelemetryIntervalMs(samplingIntervalMs);
        runRFI(db, queryId, warmRuns);
    }

    // 3. MÉTODOS REUTILIZÁVEIS
    protected int escolherQueryPorPerfil(int[] probs, int randomValue) {
        int soma = 0;
        for (int i = 0; i < probs.length; i++) {
            soma += probs[i];
            if (randomValue < soma) {
                return i;
            }
        }
        return 0;
    }

    protected static boolean hasCause(Throwable t, Class<? extends Throwable> target) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 20) {
            if (target.isInstance(cur)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Calcula estatísticas descritivas exatas de uma série numérica:
     * Média, Mediana (P50), P95, P99, Mínimo, Máximo absoluto (P100 != P99) e Desvio Padrão.
     */
    public static HTAPSummaryReport.SeriesStats computeSeriesStats(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return HTAPSummaryReport.SeriesStats.empty();
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();

        double sum = 0.0;
        for (double v : sorted) {
            sum += v;
        }
        double mean = sum / n;

        double sqDiffSum = 0.0;
        for (double v : sorted) {
            double d = v - mean;
            sqDiffSum += d * d;
        }
        double stdDev = Math.sqrt(sqDiffSum / n);

        double min = sorted.get(0);
        double max = sorted.get(n - 1);
        double median = (n % 2 == 1)
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        double p95 = percentileNearestRank(sorted, 0.95);
        double p99 = percentileNearestRank(sorted, 0.99);

        return new HTAPSummaryReport.SeriesStats(n, mean, median, p95, p99, min, max, stdDev);
    }

    private static double percentileNearestRank(List<Double> sorted, double p) {
        int n = sorted.size();
        int idx = (int) Math.ceil(p * n) - 1;
        if (idx < 0) {
            idx = 0;
        } else if (idx >= n) {
            idx = n - 1;
        }
        return sorted.get(idx);
    }

    /**
     * Overload legado (mantido para compatibilidade com classes auxiliares).
     */
    protected void processarEstatisticas(String sessionTimestamp, String perfil, int clientes, int totalPedidos, long tempoTotalMs) {
        processarEstatisticas(sessionTimestamp, "Desconhecido", "Desconhecido", perfil, clientes, totalPedidos,
                tempoTotalMs, null, null, 0L, 0L);
    }

    /**
     * Consolida todas as métricas comparativas da execução HTAP (Latências, Read/Write,
     * Origem dos Timeouts/Falhas, RAM baseline 2s vs carga, CPU e /proc), imprime a tabela final
     * e exporta para CSV mestre, CSV/TXT da run e folha Excel.
     */
    protected HTAPSummaryReport processarEstatisticas(
            String sessionTimestamp,
            String baseDeDados,
            String esquema,
            String perfil,
            int clientes,
            int totalPedidos,
            long tempoTotalMs,
            String runFolderPath,
            TelemetryEngine observador,
            long baselineEndTimestamp,
            long workloadEndTimestamp) {

        List<OperationRecord> opsSnapshot;
        synchronized (operationRecords) {
            opsSnapshot = new ArrayList<>(operationRecords);
        }
        // Fallback se alguma subclasse legada apenas populou 'latencies'
        if (opsSnapshot.isEmpty() && !latencies.isEmpty()) {
            synchronized (latencies) {
                for (Long lat : latencies) {
                    opsSnapshot.add(new OperationRecord(System.currentTimeMillis(), true, lat, true, FailureOrigin.SUCCESS));
                }
            }
        }

        if (opsSnapshot.isEmpty()) {
            System.out.println("Nenhum pedido foi processado.");
            return null;
        }

        HTAPSummaryReport report = buildSummaryReport(
                sessionTimestamp, baseDeDados, esquema, "SF" + this.sfGlobalAtivo,
                perfil, clientes, totalPedidos, tempoTotalMs, this.telemetryIntervalMs,
                opsSnapshot,
                observador != null ? observador.getSamplesSnapshot() : Collections.emptyList(),
                baselineEndTimestamp, workloadEndTimestamp
        );

        System.out.println(MetricsExporter.formatConsoleTable(report));

        // Mantém compatibilidade com CSV legado + grava tabela comparativa mestre e Excel
        MetricsExporter.saveHTAPSummary(
                sessionTimestamp, perfil, clientes, totalPedidos,
                report.throughputGlobalOpsSec,
                Math.round(report.latenciaGlobal.median),
                Math.round(report.latenciaGlobal.p95),
                Math.round(report.latenciaGlobal.p99),
                report.tempoTotalSegundos,
                report.totalFalhadas
        );
        MetricsExporter.saveHTAPComparativeSummary(report, runFolderPath);

        return report;
    }

    /**
     * Constrói o relatório consolidado a partir dos registos de operações e amostras de telemetria.
     */
    public static HTAPSummaryReport buildSummaryReport(
            String sessionTimestamp,
            String baseDeDados,
            String esquema,
            String scaleFactor,
            String perfil,
            int clientes,
            int totalPedidosSolicitados,
            long tempoTotalMs,
            long intervaloTelemetriaMs,
            List<OperationRecord> opsSnapshot,
            List<TelemetryEngine.TelemetrySample> telemetrySamples,
            long baselineEndTimestamp,
            long workloadEndTimestamp) {

        HTAPSummaryReport r = new HTAPSummaryReport();
        r.sessionTimestamp = sessionTimestamp;
        try {
            long tsEpoch = Long.parseLong(sessionTimestamp);
            r.dataHoraUtc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(tsEpoch));
        } catch (Exception e) {
            r.dataHoraUtc = sessionTimestamp;
        }
        r.baseDeDados = baseDeDados;
        r.esquema = esquema;
        r.scaleFactor = scaleFactor;
        r.perfil = perfil;
        r.clientesConcorrentes = clientes;
        r.totalPedidosSolicitados = totalPedidosSolicitados;
        r.tempoTotalSegundos = Math.max(0.001, tempoTotalMs / 1000.0);
        r.intervaloTelemetriaMs = intervaloTelemetriaMs;

        List<Double> allLat = new ArrayList<>();
        List<Double> readLat = new ArrayList<>();
        List<Double> writeLat = new ArrayList<>();

        for (OperationRecord op : opsSnapshot) {
            r.totalExecutadas++;
            allLat.add((double) op.latencyMs);

            if (op.isRead) {
                r.readTotal++;
                readLat.add((double) op.latencyMs);
                if (op.success) {
                    r.readConcluidas++;
                } else {
                    r.readFalhadas++;
                }
            } else {
                r.writeTotal++;
                writeLat.add((double) op.latencyMs);
                if (op.success) {
                    r.writeConcluidas++;
                } else {
                    r.writeFalhadas++;
                }
            }

            if (op.success) {
                r.totalConcluidas++;
            } else {
                r.totalFalhadas++;
                switch (op.failureOrigin) {
                    case TIMEOUT_CODE_QUERY:
                        r.falhasTimeoutCodigoQuery++;
                        break;
                    case TIMEOUT_CODE_GLOBAL:
                        r.falhasTimeoutCodigoGlobal++;
                        break;
                    case TIMEOUT_DB:
                        r.falhasTimeoutBD++;
                        break;
                    case DB_LOCK_DEADLOCK:
                        r.falhasLockDeadlockBD++;
                        break;
                    case DB_RESOURCE_OR_ERROR:
                        r.falhasErroBD++;
                        break;
                    case CLIENT_OR_OTHER:
                    default:
                        r.falhasOutrasCliente++;
                        break;
                }
            }
        }

        r.taxaSucessoGlobalPct = r.totalExecutadas > 0 ? (r.totalConcluidas * 100.0) / r.totalExecutadas : 0.0;
        r.readTaxaSucessoPct = r.readTotal > 0 ? (r.readConcluidas * 100.0) / r.readTotal : 0.0;
        r.writeTaxaSucessoPct = r.writeTotal > 0 ? (r.writeConcluidas * 100.0) / r.writeTotal : 0.0;

        r.throughputGlobalOpsSec = r.totalExecutadas / r.tempoTotalSegundos;
        r.goodputEfetivoOpsSec = r.totalConcluidas / r.tempoTotalSegundos;

        r.latenciaGlobal = computeSeriesStats(allLat);
        r.latenciaLeitura = computeSeriesStats(readLat);
        r.latenciaEscrita = computeSeriesStats(writeLat);

        // Separar amostras de telemetria em Baseline (primeiros 2s) e Carga Ativa
        if (telemetrySamples != null && !telemetrySamples.isEmpty()) {
            List<TelemetryEngine.TelemetrySample> baseline = new ArrayList<>();
            List<TelemetryEngine.TelemetrySample> workload = new ArrayList<>();

            for (TelemetryEngine.TelemetrySample s : telemetrySamples) {
                if (baselineEndTimestamp > 0 && s.timestampMs <= baselineEndTimestamp) {
                    baseline.add(s);
                } else if (workloadEndTimestamp <= 0 || s.timestampMs <= workloadEndTimestamp) {
                    workload.add(s);
                }
            }

            if (baseline.isEmpty()) {
                baseline.add(telemetrySamples.get(0));
            }
            if (workload.isEmpty()) {
                for (TelemetryEngine.TelemetrySample s : telemetrySamples) {
                    if (s.timestampMs > baselineEndTimestamp) {
                        workload.add(s);
                    }
                }
                if (workload.isEmpty()) {
                    workload.addAll(telemetrySamples);
                }
            }

            List<Double> baseRam = new ArrayList<>();
            List<Double> baseCpu = new ArrayList<>();
            List<Double> baseSwap = new ArrayList<>();
            for (TelemetryEngine.TelemetrySample s : baseline) {
                if (s.ramUsedMB >= 0) baseRam.add(s.ramUsedMB);
                baseCpu.add(s.cpuGlobalPct);
                baseSwap.add(s.swapUsedMB);
            }
            r.ramBaseline2sMediaMB = computeSeriesStats(baseRam).mean;
            r.cpuBaseline2sMediaPct = computeSeriesStats(baseCpu).mean;
            r.swapBaseline2sMediaMB = computeSeriesStats(baseSwap).mean;

            List<Double> workRam = new ArrayList<>();
            List<Double> workCpu = new ArrayList<>();
            List<Double> workIoWait = new ArrayList<>();
            List<Double> workSwap = new ArrayList<>();
            List<Double> workDirty = new ArrayList<>();
            List<Double> workCtxt = new ArrayList<>();
            int maxProcs = 0;
            long sumMajFaults = 0L;

            for (TelemetryEngine.TelemetrySample s : workload) {
                if (s.ramUsedMB >= 0) workRam.add(s.ramUsedMB);
                workCpu.add(s.cpuGlobalPct);
                workIoWait.add(s.cpuIoWaitPct);
                workSwap.add(s.swapUsedMB);
                workDirty.add(s.dirtyMemMB);
                workCtxt.add(s.contextSwitchesPerSec);
                if (s.procsRunning > maxProcs) maxProcs = s.procsRunning;
                sumMajFaults += s.majorPageFaultsDelta;
            }

            r.ramCarga = computeSeriesStats(workRam);
            r.ramDeltaMediaMB = r.ramCarga.mean - r.ramBaseline2sMediaMB;
            r.ramDeltaMaxMB = r.ramCarga.max - r.ramBaseline2sMediaMB;

            r.cpuCarga = computeSeriesStats(workCpu);
            r.cpuIoWaitCarga = computeSeriesStats(workIoWait);
            r.swapCarga = computeSeriesStats(workSwap);
            r.swapDeltaMaxMB = r.swapCarga.max - r.swapBaseline2sMediaMB;
            r.dirtyMemCarga = computeSeriesStats(workDirty);
            r.contextSwitchesCarga = computeSeriesStats(workCtxt);
            r.procsRunningMax = maxProcs;
            r.majorPageFaultsTotal = sumMajFaults;
        }

        return r;
    }

    /**
     * Se o latch global (ex: 10 minutos) expirar, regista as operações que não chegaram a concluir
     * explicitamente como TIMEOUT_CODE_GLOBAL para distinguir de falhas da BD.
     */
    protected void registarOperacoesNaoConcluidasPorTimeoutGlobal(int totalPedidosEsperados, long duracaoMs) {
        int faltam = totalPedidosEsperados - operationRecords.size();
        long now = System.currentTimeMillis();
        for (int i = 0; i < faltam; i++) {
            operationRecords.add(new OperationRecord(now, true, duracaoMs, false, FailureOrigin.TIMEOUT_CODE_GLOBAL));
            latencies.add(duracaoMs);
            falhasContencao.add(FailureOrigin.TIMEOUT_CODE_GLOBAL.name());
            latencyLogs.add(now + ",READ," + duracaoMs + ",false," + FailureOrigin.TIMEOUT_CODE_GLOBAL.name());
            executionLogs.add("[GLOBAL_TIMEOUT] Operação abortada por limite global do benchmark (" + (duracaoMs / 1000.0) + " s) [TIMEOUT_CODIGO_GLOBAL]");
        }
    }

    /**
     * Orquestra de forma uniforme (ArangoDB e PostgreSQL+AGE) a exportação de janelas,
     * timeline de latências, logs textuais, tabela comparativa final e gráficos analíticos.
     */
    protected HTAPSummaryReport finalizarEReportarHTAP(
            String sessionTimestamp,
            String baseDeDados,
            String esquema,
            String perfilNome,
            int numClientes,
            int totalPedidos,
            String folderPath,
            String globalCsv,
            TelemetryEngine observador,
            long baselineStartTs,
            long baselineEndTs,
            long round0StartTs,
            long round0EndTs,
            long startTimeGlobal,
            long endTimeGlobal,
            long durationTotalMs) {

        String janelasCsv = folderPath + "/janela_workload.csv";
        try (java.io.FileWriter fw = new java.io.FileWriter(janelasCsv)) {
            fw.append("Start_Timestamp,End_Timestamp,Engine,Schema,Query_ID,Target_Key,Run_Type,Latency_ms,Delta_RAM_MB,RFI\n");
            fw.append(baselineStartTs + "," + baselineEndTs + "," + baseDeDados + "," + esquema
                    + ",BASELINE,N/A,Baseline_2s," + (baselineEndTs - baselineStartTs) + ",0,0\n");
            fw.append(round0StartTs + "," + round0EndTs + "," + baseDeDados + "," + esquema
                    + ",ROUND0,N/A,Cold_Round0," + (round0EndTs - round0StartTs) + ",0,0\n");
            fw.append(startTimeGlobal + "," + endTimeGlobal + "," + baseDeDados + "," + esquema
                    + ",MIX,N/A,Warm_HTAP," + durationTotalMs + ",0,0\n");
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar janelas: " + e.getMessage());
        }

        String pngPath = folderPath + "/grafico_HTAP";
        System.out.println("\n-> A processar gráficos do Benchmark HTAP...");
        deepchainbench.generator.GraphGenerator.createRFIChart(globalCsv, janelasCsv, pngPath);
        deepchainbench.generator.GraphGenerator.createSystemPressureChart(globalCsv, janelasCsv, pngPath);

        String timelineCsv = folderPath + "/latency_timeline.csv";
        try (java.io.FileWriter fw = new java.io.FileWriter(timelineCsv)) {
            fw.append("Timestamp_ms,Tipo_Operacao,Latencia_ms,Sucesso,Origem_Falha\n");
            synchronized (latencyLogs) {
                for (String log : latencyLogs) {
                    fw.append(log + "\n");
                }
            }
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar timeline de latências: " + e.getMessage());
        }

        deepchainbench.generator.GraphGenerator.createHTAPCharts(timelineCsv, pngPath);

        String logTextPath = folderPath + "/execution_log.txt";
        try (java.io.FileWriter fw = new java.io.FileWriter(logTextPath)) {
            fw.append("========== RELATÓRIO DE EXECUÇÃO HTAP: " + perfilNome + " ==========\n");
            fw.append("Base de Dados: " + baseDeDados + " | Esquema: " + esquema + " | Scale Factor: SF" + this.sfGlobalAtivo + "\n");
            fw.append("Tempo Total de Benchmark: " + (durationTotalMs / 1000.0) + " s\n");
            fw.append("Clientes Simultâneos: " + numClientes + "\n");
            fw.append("Total de Operações Submetidas: " + totalPedidos + "\n");
            fw.append("Intervalo Telemetria: " + this.telemetryIntervalMs + " ms\n");
            fw.append("============================================================\n\n");
            synchronized (executionLogs) {
                for (String logLine : executionLogs) {
                    fw.append(logLine + "\n");
                }
            }
            System.out.println("-> Relatório em texto (Logs) exportado para: " + logTextPath);
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar os logs: " + e.getMessage());
        }

        HTAPSummaryReport report = processarEstatisticas(
                sessionTimestamp, baseDeDados, esquema, perfilNome, numClientes, totalPedidos,
                durationTotalMs, folderPath, observador, baselineEndTs, endTimeGlobal);

        deepchainbench.generator.GraphGenerator.createLatencyPercentilesChart(report, pngPath);
        deepchainbench.generator.GraphGenerator.createCrossDBComparisonCharts(
                MetricsExporter.getComparativeCsvPath(), "./metrics/comparacao");

        return report;
    }
}
