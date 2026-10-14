package deepchainbench.core.Postgres;

import deepchainbench.core.AbstractBenchmarkEngine;
import deepchainbench.core.MetricsExporter;
import deepchainbench.core.TelemetryEngine;
import deepchainbench.generator.GraphGenerator;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motor de benchmark para PostgreSQL + Apache AGE.
 *
 * <h2>Nota arquitetural</h2>
 * Este motor testa <b>PostgreSQL + AGE</b> como um MMDBMS, não "PostgreSQL puro".
 * As queries analíticas são expressas em Cypher (via AGE), porque é assim que o
 * motor suporta o modelo de grafo exigido pelo DeepChainBench.
 *
 * <h2>Métrica RFI</h2>
 * O RFI (Resource Footprint Index) é calculado como:
 * <pre>
 *     RFI = ΔRAM / Δd
 * </pre>
 * onde {@code ΔRAM} é o delta de memória <b>interna</b> do backend Postgres
 * durante a execução da query — obtido de {@code pg_backend_memory_contexts}
 * (PostgreSQL 18+) — e {@code Δd} é a profundidade topológica da query.
 *
 * Esta é a métrica equivalente ao {@code cursor.getStats().getPeakMemoryUsage()}
 * do ArangoDB, tornando os dois motores comparáveis no gráfico RFI do paper.
 *
 * <h2>Stress Test (HTAP)</h2>
 * O stress test usa uma métrica <b>diferente e independente</b>: RAM total do
 * sistema operativo, via {@link PostgresTelemetry}. Essa métrica serve para
 * observar o comportamento do sistema sob concorrência massiva, e <b>não</b>
 * deve ser confundida com o RFI.
 *
 * @see deepchainbench.core.Postgres.PostgresTelemetry
 */
public class BenchmarkEnginePostgres extends AbstractBenchmarkEngine<Connection> {

    private String graphName = "deepchaindb";
    private final String qualityTable;
    private final String telemetryTable;

    public BenchmarkEnginePostgres() {
        // ← ALTERADO: prefixo "public." para alinhar com o PostgresAgeDriver (ingestão)
        this.qualityTable = "public." + this.graphName + "_quality_kv";
        this.telemetryTable = "public." + this.graphName + "_telemetry";
        initQueries();
    }

    public BenchmarkEnginePostgres(String graphName) {
        this.graphName = graphName.toLowerCase();
        // ← ALTERADO: prefixo "public." para alinhar com o PostgresAgeDriver (ingestão)
        this.qualityTable = "public." + this.graphName + "_quality_kv";
        this.telemetryTable = "public." + this.graphName + "_telemetry";
        initQueries();
    }

    // =========================================================================
    // WARM-UP
    // =========================================================================

    @Override
    public void warmUpAndHarvest(Connection conn) {
        System.out.println("   [Benchmark] A iniciar Warm-up e colheita de parâmetros no PostgreSQL (AGE)...");
        try {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("LOAD 'age';");
                // ← ALTERADO: public primeiro, alinhado com o PostgresAgeDriver
                stmt.execute("SET search_path = public, ag_catalog, \"$user\";");
            }
            long totalParts = 0;
            String countSql = "SELECT count(*) FROM cypher('" + graphName + "', $$ MATCH (p:Part) RETURN p $$) AS (p agtype);";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(countSql)) {
                if (rs.next()) totalParts = rs.getLong(1);
            }
            this.sfGlobalAtivo = (int) (totalParts / 2000);
            if (this.sfGlobalAtivo < 1) this.sfGlobalAtivo = 1;
            System.out.println("   [Benchmark] Scale Factor detetado no motor: SF" + this.sfGlobalAtivo);

            partKeysPool.clear();
            String harvestSql = "SELECT ag_catalog.agtype_to_text(id) FROM cypher('" + graphName + "', $$ MATCH (p:Part) RETURN p.id $$) AS (id agtype) LIMIT 1000;";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(harvestSql)) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    if (id != null) partKeysPool.add(id.replaceAll("^\"|\"$", ""));
                }
            }
            if (partKeysPool.isEmpty())
                throw new IllegalStateException("A coleção 'Part' está vazia no Grafo " + graphName + ". Execute a ingestão primeiro.");
            System.out.println("   [Benchmark] Pool carregada com " + partKeysPool.size() + " IDs.");
        } catch (Exception e) {
            System.err.println("   [ERRO] Falha no warm-up: " + e.getMessage());
        }
    }

    // =========================================================================
    // WORKLOAD HTAP
    // =========================================================================

    @Override
    public void runWorkload(Connection db, String perfilNome, int[] probsLeitura, int[] probsEscrita,
                            int readPercentage, int numClientes) {
        if (partKeysPool.isEmpty()) {
            System.out.println("[ERRO] Pool de parâmetros vazia. Execute o warm-up primeiro.");
            return;
        }

        latencies.clear();
        falhasContencao.clear();
        latencyLogs.clear();
        executionLogs.clear();

        int pedidosPorCliente = 10;
        int totalPedidos = numClientes * pedidosPorCliente;

        // 1. ISOLAMENTO DE PASTA
        String sessionTimestamp = String.valueOf(System.currentTimeMillis());
        String folderPath = "./metrics/Task_" + perfilNome + "_" + sessionTimestamp;
        java.io.File directory = new java.io.File(folderPath);
        if (!directory.exists()) directory.mkdirs();

        java.text.SimpleDateFormat sdfUtc = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
        sdfUtc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        System.out.println("\n--- EXECUÇÃO DO BENCHMARK: " + perfilNome + " ---");
        System.out.println("-> Clientes Simultâneos: " + numClientes);
        System.out.println("-> Carga: " + pedidosPorCliente + " queries/cliente (Total: " + totalPedidos + " operações)");
        System.out.println("-> Rácio Leitura/Escrita: " + readPercentage + "% / " + (100 - readPercentage) + "%");
        System.out.println("-> Diretório de Métricas: " + folderPath);

        // 2. INICIAR TELEMETRIA (RAM total do sistema — métrica de STRESS TEST)
        String globalCsv = folderPath + "/hardware_telemetry.csv";
        Connection telemetryDb = createTelemetryConnection(db);
        TelemetryEngine observador = new PostgresTelemetry(globalCsv, telemetryDb);
        Thread threadObservador = new Thread(observador);
        threadObservador.start();

        System.out.println("-> A gravar baseline de hardware (Aguardando 2 segundos)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}

        // =====================================================================
        // ROUND 0 (COLD START)
        // =====================================================================
        System.out.println("-> A EXECUTAR ROUND 0 (COLD START) - Aquecer Caches do Motor...");
        long tInicioRound0 = System.currentTimeMillis();
        Random coldRandom = new Random(99999);

        for (int q = 0; q < QUERIES_STRESS_TEST_READ.length; q++) {
            String keyBase = (q < 2)
                    ? String.valueOf(coldRandom.nextInt(2000))
                    : TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)];
            long startQuery = System.nanoTime();
            boolean sucesso = true;
            try (PreparedStatement pstmt = db.prepareStatement(QUERIES_STRESS_TEST_READ[q])) {
                pstmt.setQueryTimeout(120);
                if (q == 1) {
                    pstmt.setString(1, keyBase + "_sf" + this.sfGlobalAtivo);
                } else {
                    PGobject param = new PGobject();
                    param.setType("agtype");
                    param.setValue("{\"key_base\": \"" + keyBase + "_sf" + this.sfGlobalAtivo + "\"}");
                    pstmt.setObject(1, param);
                }
                try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) {} }
            } catch (Exception e) { sucesso = false; }

            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            latencyLogs.add(System.currentTimeMillis() + ",COLD_READ," + latencyMs + "," + sucesso);
            executionLogs.add("[" + sdfUtc.format(new java.util.Date()) + "] [Round 0] CONCLUIU COLD_READ (Q" + (q + 1) + ") em " + latencyMs + " ms.");
        }

        for (int w = 0; w < QUERIES_WRITE.length; w++) {
            String keyBase = (w < 4)
                    ? String.valueOf(coldRandom.nextInt(2000))
                    : TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)];
            if (w == 8) keyBase = coldRandom.nextBoolean() ? "799" : "1599";
            String targetKey = keyBase + "_sf" + this.sfGlobalAtivo;

            long startQuery = System.nanoTime();
            boolean sucesso = true;
            try (PreparedStatement pstmt = db.prepareStatement(QUERIES_WRITE[w])) {
                pstmt.setQueryTimeout(120);
                bindWriteParams(pstmt, w, targetKey, coldRandom);
                if (QUERIES_WRITE[w].startsWith("SELECT")) pstmt.executeQuery().close();
                else pstmt.executeUpdate();
            } catch (Exception e) { sucesso = false; }

            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            latencyLogs.add(System.currentTimeMillis() + ",COLD_WRITE," + latencyMs + "," + sucesso);
            executionLogs.add("[" + sdfUtc.format(new java.util.Date()) + "] [Round 0] CONCLUIU COLD_WRITE (W" + (w + 1) + ") em " + latencyMs + " ms.");
        }
        System.out.println("-> ROUND 0 CONCLUÍDO em " + (System.currentTimeMillis() - tInicioRound0) + "ms. Planos em cache.");

        // =====================================================================
        // ATAQUE HTAP
        // =====================================================================
        ExecutorService executor = Executors.newFixedThreadPool(numClientes);
        CountDownLatch latch = new CountDownLatch(numClientes);
        long startTimeGlobal = System.currentTimeMillis();
        System.out.println("-> A INICIAR ATAQUE DE CONCORRÊNCIA HTAP...");

        for (int i = 0; i < numClientes; i++) {
            final int streamId = i;
            executor.submit(() -> {
                try {
                    Random deterministico = new Random(42000 + streamId);
                    for (int p = 0; p < pedidosPorCliente; p++) {
                        boolean isRead = deterministico.nextInt(100) < readPercentage;

                        String rawKey = isRead
                                ? (deterministico.nextBoolean()
                                    ? String.valueOf(deterministico.nextInt(2000))
                                    : TARGETS_ANALITICOS[deterministico.nextInt(TARGETS_ANALITICOS.length)])
                                : String.valueOf(deterministico.nextInt(2000));
                        String targetKey = rawKey + "_sf" + this.sfGlobalAtivo;

                        String labelQuery;
                        String queryExecutar;
                        int queryIndex;

                        if (isRead) {
                            queryIndex = escolherQueryPorPerfil(probsLeitura, deterministico.nextInt(100));
                            queryExecutar = QUERIES_STRESS_TEST_READ[queryIndex];
                            labelQuery = "READ (Q" + (queryIndex + 1) + ")";
                        } else {
                            queryIndex = escolherQueryPorPerfil(probsEscrita, deterministico.nextInt(100));
                            queryExecutar = QUERIES_WRITE[queryIndex];
                            labelQuery = "WRITE (W" + (queryIndex + 1) + ")";
                        }

                        String logStart = "[" + sdfUtc.format(new java.util.Date()) + "] [Stream "
                                + String.format("%03d", streamId) + "] INICIOU " + labelQuery
                                + " no target: " + targetKey;
                        System.out.println("   " + logStart);
                        executionLogs.add(logStart);

                        long startQuery = System.nanoTime();
                        boolean sucesso = true;
                        String erroDetalhe = "";

                        try (PreparedStatement pstmt = db.prepareStatement(queryExecutar)) {
                            pstmt.setQueryTimeout(120);

                            if (isRead) {
                                if (queryIndex == 1) {
                                    pstmt.setString(1, targetKey);
                                } else {
                                    PGobject param = new PGobject();
                                    param.setType("agtype");
                                    param.setValue("{\"key_base\": \"" + targetKey + "\"}");
                                    pstmt.setObject(1, param);
                                }
                                try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) {} }
                            } else {
                                bindWriteParams(pstmt, queryIndex, targetKey, deterministico);
                                if (queryExecutar.startsWith("SELECT")) pstmt.executeQuery().close();
                                else pstmt.executeUpdate();
                            }
                        } catch (SQLException e) {
                            sucesso = false;
                            String sqlState = e.getSQLState();
                            if (sqlState != null && (sqlState.equals("57014") || sqlState.equals("40P01") || sqlState.equals("40001"))) {
                                erroDetalhe = "Timeout/Deadlock (" + sqlState + ")";
                                falhasContencao.add("Timeout/Lock");
                            } else {
                                erroDetalhe = "Erro SQLSTATE: " + sqlState;
                                falhasContencao.add("SQL Error: " + sqlState);
                            }
                        } catch (Exception e) {
                            sucesso = false;
                            erroDetalhe = "Erro Genérico Java";
                            falhasContencao.add("Generic Error");
                        }

                        long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
                        String logEnd;
                        if (sucesso) {
                            logEnd = "[" + sdfUtc.format(new java.util.Date()) + "] [Stream "
                                    + String.format("%03d", streamId) + "] CONCLUIU " + labelQuery
                                    + " em " + latencyMs + " ms.";
                        } else {
                            logEnd = "[" + sdfUtc.format(new java.util.Date()) + "] [Stream "
                                    + String.format("%03d", streamId) + "] FALHOU   " + labelQuery
                                    + " (" + erroDetalhe + ") após " + latencyMs + " ms.";
                        }

                        System.out.println("   " + logEnd);
                        executionLogs.add(logEnd);

                        latencies.add(latencyMs);
                        latencyLogs.add(System.currentTimeMillis() + "," + (isRead ? "READ" : "WRITE")
                                + "," + latencyMs + "," + sucesso);
                    }
                } catch (Exception e) {
                    System.err.println("Erro crítico no Stream " + streamId + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean terminouNormalmente = latch.await(10, java.util.concurrent.TimeUnit.MINUTES);
            if (!terminouNormalmente) {
                System.err.println("\n[ERRO FATAL] O Benchmark excedeu o limite global de 10 minutos. A forçar paragem.");
            }
        } catch (InterruptedException e) {}

        long endTimeGlobal = System.currentTimeMillis();
        long durationTotalMs = endTimeGlobal - startTimeGlobal;
        executor.shutdown();

        // 4. PARAR TELEMETRIA
        System.out.println("-> A gravar cooldown de hardware (Aguardando 1 segundo)...");
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        observador.stopEngine();
        closeTelemetryConnection(telemetryDb, db);
        try { Thread.sleep(200); } catch (InterruptedException e) {}

        // 5. EXPORTAR FICHEIROS E GRÁFICOS
        String janelasCsv = folderPath + "/janela_workload.csv";
        try (java.io.FileWriter fw = new java.io.FileWriter(janelasCsv)) {
            fw.append("Start_Timestamp,End_Timestamp,Engine,Schema,Query_ID,Target_Key,Run_Type,Latency_ms,Delta_RAM_MB,RFI\n");
            fw.append(startTimeGlobal + "," + endTimeGlobal + ",PostgreSQL,Public,MIX,N/A,Warm_HTAP,"
                    + durationTotalMs + ",0,0\n");
        } catch (Exception e) {}

        String pngPath = folderPath + "/grafico_HTAP";
        System.out.println("\n-> A processar gráficos do Benchmark HTAP...");
        GraphGenerator.createRFIChart(globalCsv, janelasCsv, pngPath);

        String timelineCsv = folderPath + "/latency_timeline.csv";
        try (java.io.FileWriter fw = new java.io.FileWriter(timelineCsv)) {
            fw.append("Timestamp_ms,Tipo_Operacao,Latencia_ms,Sucesso\n");
            for (String log : latencyLogs) fw.append(log + "\n");
        } catch (Exception e) {}

        GraphGenerator.createHTAPCharts(timelineCsv, pngPath);

        String logTextPath = folderPath + "/execution_log.txt";
        try (java.io.FileWriter fw = new java.io.FileWriter(logTextPath)) {
            fw.append("========== RELATÓRIO DE EXECUÇÃO HTAP: " + perfilNome + " ==========\n");
            fw.append("Tempo Total de Benchmark: " + (durationTotalMs / 1000.0) + " s\n");
            fw.append("Clientes Simultâneos: " + numClientes + "\n");
            fw.append("Total de Operações Submetidas: " + totalPedidos + "\n");
            fw.append("============================================================\n\n");
            for (String logLine : executionLogs) fw.append(logLine + "\n");
            System.out.println("-> Relatório em texto (Logs) exportado para: " + logTextPath);
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar os logs: " + e.getMessage());
        }

        processarEstatisticas(sessionTimestamp, perfilNome, numClientes, totalPedidos, durationTotalMs);
    }

    // =========================================================================
    // RFI (Resource Footprint Index)
    // =========================================================================

    /**
     * Executa o Modo RFI para PostgreSQL + AGE.
     *
     * <h3>Métrica</h3>
     * O ΔRAM é lido de {@code pg_backend_memory_contexts} — a soma de
     * {@code total_bytes} de todos os memory contexts do backend que executa a
     * query. A leitura é feita antes e depois da execução, na <b>mesma conexão</b>
     * (a view é local ao backend).
     *
     * <p>Este valor representa a RAM <b>interna do motor</b> alocada pela query,
     * sendo conceptualmente equivalente ao {@code cursor.getStats().getPeakMemoryUsage()}
     * do ArangoDB.
     *
     * <h3>Alvo canónico</h3>
     * O RFI testa sempre o universo {@code _sf1} para isolar a variável
     * <i>profundidade topológica</i> da variável <i>volume</i>. Como os universos
     * {@code _sfK} são topologicamente isomórficos (mesma seed, mesmo algoritmo
     * de parentesco, mesma injeção modular), testar {@code _sfK} com K > 1
     * introduziria variabilidade de volume sem alterar a profundidade, invalidando
     * a interpretação do RFI como medida de esforço algorítmico.
     *
     * <h3>Pré-requisito</h3>
     * Requer <b>PostgreSQL 18+</b> (view {@code pg_backend_memory_contexts}).
     */
    @Override
    public void runRFI(Connection db, String queryId, int warmRuns) {
        if (partKeysPool.isEmpty()) {
            System.out.println("[ERRO] Pool de parâmetros vazia. O motor precisa do Warm-up primeiro.");
            return;
        }

        int index;
        try { index = Integer.parseInt(queryId) - 1; }
        catch (NumberFormatException e) { System.out.println("[ERRO] ID de Query inválido."); return; }
        if (index < 0 || index >= QUERIES_RFI.length) {
            System.out.println("[ERRO] A Query selecionada não existe no array (1 a 10).");
            return;
        }

        String sqlQuery = QUERIES_RFI[index];

        // Alvo canónico fixo em _sf1 — ver javadoc acima para justificação científica.
        final String[] TARGET_KEYS = {
                "15_sf1",  // Q1
                "12_sf1",  // Q2
                "800_sf1", // Q3
                "800_sf1", // Q4
                "500_sf1", // Q5
                "500_sf1", // Q6
                "500_sf1", // Q7
                "500_sf1", // Q8
                "799_sf1", // Q9
                "800_sf1"  // Q10
        };
        String targetKey = TARGET_KEYS[index];

        final int[] DEPTHS = { 1, 1, 1, 1, 299, 299, 299, 299, 299, 10 };
        int depth = DEPTHS[index];

        System.out.println("\n-> A preparar Teste RFI para a Query " + queryId);
        System.out.println("-> Target Node (Key injetada): " + targetKey);
        System.out.println("-> Postgres AGE Query: " + sqlQuery);
        System.out.println("-> Métrica: pg_backend_memory_contexts (RAM interna do motor)");

        String sessionTimestamp = String.valueOf(System.currentTimeMillis());
        String globalCsv = "./metrics/resultados_observador_" + sessionTimestamp + ".csv";

        // Telemetria de SISTEMA (RAM do SO) — usada apenas para o gráfico de contexto
        Connection telemetryDb = createTelemetryConnection(db);
        TelemetryEngine observador = new PostgresTelemetry(globalCsv, telemetryDb);
        Thread threadObservador = new Thread(observador);
        threadObservador.start();

        System.out.println("-> A gravar baseline global (Aguardando 2 segundos)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}

        // Verificar disponibilidade da view (PG18+)
        if (!isBackendMemoryContextsAvailable(db)) {
            System.err.println("[ERRO] pg_backend_memory_contexts indisponível neste PostgreSQL. "
                    + "O RFI requer PostgreSQL 18+. A abortar medição.");
            observador.stopEngine();
            closeTelemetryConnection(telemetryDb, db);
            return;
        }

        try (Statement timeoutStmt = db.createStatement()) {
            timeoutStmt.execute("SET statement_timeout = 120000;");
        } catch (Exception e) {
            System.err.println("[Aviso] Não foi possível definir o timeout: " + e.getMessage());
        }

        try {
            System.out.println("\n=========================================");
            System.out.println(" INICIANDO COLD RUN (RUN 1)");
            System.out.println("=========================================");
            RFIMeasurement cold = measureRFI(db, sqlQuery, index, targetKey);
            System.out.println("Cold Run Tempo: " + cold.latencyMs + " ms | Delta RAM: "
                    + String.format("%.3f", cold.deltaRamMB) + " MB | RFI: "
                    + String.format("%.6f", cold.deltaRamMB / depth));

            MetricsExporter.saveRFIRun(
                    sessionTimestamp, "PostgreSQL", graphName, queryId, targetKey, "Cold",
                    String.valueOf(cold.startTs), String.valueOf(cold.endTs),
                    cold.latencyMs, index, cold.deltaRamMB, cold.deltaRamMB / depth);

            System.out.println("\n=========================================");
            System.out.println(" INICIANDO WARM RUNS (RUNS 2 a " + (warmRuns + 1) + ")");
            System.out.println("=========================================");
            for (int i = 1; i <= warmRuns; i++) {
                RFIMeasurement warm = measureRFI(db, sqlQuery, index, targetKey);
                System.out.println("Warm Run " + i + " Tempo: " + warm.latencyMs + " ms | Delta RAM: "
                        + String.format("%.3f", warm.deltaRamMB) + " MB | RFI: "
                        + String.format("%.6f", warm.deltaRamMB / depth));

                MetricsExporter.saveRFIRun(
                        sessionTimestamp, "PostgreSQL", graphName, queryId, targetKey, "Warm_" + i,
                        String.valueOf(warm.startTs), String.valueOf(warm.endTs),
                        warm.latencyMs, index, warm.deltaRamMB, warm.deltaRamMB / depth);
            }
        } finally {
            try (Statement timeoutStmt = db.createStatement()) {
                timeoutStmt.execute("SET statement_timeout = 0;");
            } catch (Exception ignored) {}
        }

        System.out.println("-> A gravar cooldown global (Aguardando 1 segundo)...");
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        observador.stopEngine();
        closeTelemetryConnection(telemetryDb, db);
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        int queryNum = index + 1;
        String rfiCsv = "./metrics/rfi_metrics_QUERY-" + queryNum + "_postgresql_" + sessionTimestamp + ".csv";
        String pngPath = "./metrics/grafico_RFI_Q" + queryNum + "_" + sessionTimestamp;
        System.out.println("-> A gerar gráfico de telemetria...");
        GraphGenerator.createRFIChart(globalCsv, rfiCsv, pngPath);
        System.out.println("-> Teste RFI concluído. Métricas gravadas.");
    }

    // =========================================================================
    // MEDIÇÃO RFI — CORE
    // =========================================================================

    /**
     * Executa UMA query e mede o delta de RAM interna do backend Postgres.
     *
     * <p>A medição usa {@code pg_backend_memory_contexts}, que é uma view local
     * ao backend — só pode ser consultada na mesma conexão que executa a query.
     * Isto garante que o valor lido reflete <b>apenas</b> a atividade desta
     * conexão, sem ruído de outras conexões ou do sistema operativo.
     *
     * <p><b>Nota metodológica:</b> a métrica é um <i>delta</i> (antes/depois), não
     * um <i>pico</i>. O PostgreSQL não expõe o pico de memória interna da query
     * sem recurso a extensões. Este delta é o resíduo que a query deixa alocado
     * nos memory contexts do backend após a execução.
     */
    private RFIMeasurement measureRFI(Connection db, String sqlQuery,
                                      int queryIndex, String targetKey) {
        RFIMeasurement m = new RFIMeasurement();

        // 1. Baseline
        long memBefore;
        try {
            memBefore = readBackendMemoryContexts(db);
        } catch (SQLException e) {
            System.err.println("[ERRO] Falha ao ler pg_backend_memory_contexts (baseline): " + e.getMessage());
            m.deltaRamMB = 0;
            m.latencyMs = 0;
            m.startTs = System.currentTimeMillis();
            m.endTs = m.startTs;
            return m;
        }

        // 2. Executar a query
        long startNs = System.nanoTime();
        m.startTs = System.currentTimeMillis();
        try (PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
            pstmt.setQueryTimeout(120);
            bindRfiParam(pstmt, queryIndex, targetKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) { /* drain */ }
            }
        } catch (Exception e) {
            System.err.println("Erro no RFI run: " + e.getMessage());
        }
        m.endTs = System.currentTimeMillis();
        m.latencyMs = (System.nanoTime() - startNs) / 1_000_000;

        // 3. Memória após a query
        long memAfter;
        try {
            memAfter = readBackendMemoryContexts(db);
        } catch (SQLException e) {
            System.err.println("[ERRO] Falha ao ler pg_backend_memory_contexts (após query): " + e.getMessage());
            memAfter = memBefore;
        }

        long deltaBytes = Math.max(0, memAfter - memBefore);
        m.deltaRamMB = deltaBytes / (1024.0 * 1024.0);
        m.baselineBytes = memBefore;
        m.afterBytes = memAfter;

        return m;
    }

    /**
     * Soma {@code total_bytes} de todos os memory contexts do backend atual.
     *
     * <p>Requer PostgreSQL 18+. A view é local ao backend, portanto esta leitura
     * tem de ser feita na mesma conexão que executa a query.
     */
    private long readBackendMemoryContexts(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COALESCE(SUM(total_bytes), 0) FROM pg_backend_memory_contexts")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Verifica se a view {@code pg_backend_memory_contexts} existe (PG18+).
     */
    private boolean isBackendMemoryContextsAvailable(Connection conn) {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT 1 FROM pg_catalog.pg_views WHERE viewname = 'pg_backend_memory_contexts' "
                     + "UNION ALL "
                     + "SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'pg_backend_memory_contexts' "
                     + "LIMIT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Estrutura de resultado de uma medição RFI.
     */
    private static class RFIMeasurement {
        long startTs;
        long endTs;
        long latencyMs;
        double deltaRamMB;
        long baselineBytes;
        long afterBytes;
    }

    // =========================================================================
    // LIMPEZA
    // =========================================================================

    @Override
    public void limparDadosTemporarios(Connection db) {
        System.out.println("\n-> A INICIAR LIMPEZA DE DADOS TEMPORÁRIOS (HTAP) no Postgres...");
        String[] cleanupQueries = {
                "SELECT * FROM cypher('" + graphName + "', $$ MATCH ()-[e:BoM]->() WHERE e.is_temp = true DELETE e $$) AS (a agtype)",
                "UPDATE " + qualityTable + " SET value = value - 'is_temp' - 'diamond_hits' WHERE value->>'is_temp' = 'true'",
                "SELECT * FROM cypher('" + graphName + "', $$ MATCH (p:Part) WHERE p.is_temp = true REMOVE p.last_inspected, p.is_temp $$) AS (a agtype)",
                "UPDATE " + telemetryTable + " SET data = jsonb_set(data - 'is_temp', '{anomaly}', 'false'::jsonb) WHERE data->>'is_temp' = 'true'"
        };
        for (int i = 0; i < cleanupQueries.length; i++) {
            try (Statement stmt = db.createStatement()) {
                stmt.execute(cleanupQueries[i]);
            } catch (Exception e) {
                System.err.println("Erro na rotina de limpeza " + (i + 1) + ": " + e.getMessage());
            }
        }
        System.out.println("-> Limpeza concluída! Base de dados restaurada para o estado de avaliação.");
    }

    // =========================================================================
    // HELPERS DE LIGAÇÃO
    // =========================================================================

    private Connection createTelemetryConnection(Connection main) {
        try {
            Connection telemetryDb = DriverManager.getConnection(
                    main.getMetaData().getURL(), "postgres", "password");
            try (Statement stmt = telemetryDb.createStatement()) {
                stmt.execute("LOAD 'age';");
                // ← ALTERADO: public primeiro, alinhado com o PostgresAgeDriver
                stmt.execute("SET search_path = public, ag_catalog, \"$user\";");
            }
            return telemetryDb;
        } catch (Exception e) {
            System.err.println("[Aviso] Falha ao criar ligação autónoma de telemetria. A usar a principal (pode bloquear).");
            return main;
        }
    }

    private void closeTelemetryConnection(Connection telemetryDb, Connection main) {
        if (telemetryDb != null && telemetryDb != main) {
            try { telemetryDb.close(); } catch (Exception e) {}
        }
    }

    // =========================================================================
    // BINDING DE PARÂMETROS
    // =========================================================================

    /**
     * Binding centralizado dos parâmetros de escrita. Elimina o {@code if (w==0)}
     * espalhado por múltiplos pontos do código.
     */
    private void bindWriteParams(PreparedStatement pstmt, int wIndex, String targetKey, Random rnd)
            throws SQLException {
        switch (wIndex) {
            case 0:
                pstmt.setString(1, "\"Tipo A\"");
                pstmt.setString(2, "99");
                pstmt.setString(3, targetKey);
                break;
            case 1:
                pstmt.setString(1, "{\"ts\": " + System.currentTimeMillis()
                        + ", \"v\": 85.5, \"is_temp\": true}");
                pstmt.setString(2, targetKey);
                break;
            case 2:
                PGobject p2 = new PGobject(); p2.setType("agtype");
                p2.setValue("{\"key_base\": \"" + targetKey + "\", \"novo_material\": \"Titânio\"}");
                pstmt.setObject(1, p2);
                break;
            case 3:
                pstmt.setString(1, targetKey);
                pstmt.setString(2, targetKey);
                break;
            case 4:
                PGobject p4 = new PGobject(); p4.setType("agtype");
                p4.setValue("{\"from_id\": \"" + targetKey
                        + "\", \"to_id\": \"" + String.valueOf(rnd.nextInt(2000)) + "_sf" + this.sfGlobalAtivo + "\"}");
                pstmt.setObject(1, p4);
                break;
            case 5:
            case 6:
            case 8:
            case 9:
                PGobject p5 = new PGobject(); p5.setType("agtype");
                p5.setValue("{\"key_base\": \"" + targetKey + "\", \"ts\": " + System.currentTimeMillis() + "}");
                pstmt.setObject(1, p5);
                break;
            case 7:
                pstmt.setString(1, "{\"ts\": " + System.currentTimeMillis()
                        + ", \"v\": 99.9, \"is_temp\": true}");
                PGobject p7 = new PGobject(); p7.setType("agtype");
                p7.setValue("{\"key_base\": \"" + targetKey + "\"}");
                pstmt.setObject(2, p7);
                break;
            default:
                throw new SQLException("Índice de query de escrita inválido: W" + (wIndex + 1));
        }
    }

    /**
     * Binding do parâmetro da query RFI. Todas as queries RFI de leitura usam o
     * mesmo padrão: ou um {@code agtype} com {@code key_base}, ou (Q2) uma string direta.
     */
    private void bindRfiParam(PreparedStatement pstmt, int queryIndex, String targetKey) throws SQLException {
        if (queryIndex == 1) {
            // Q2: filtro direto por part_id
            pstmt.setString(1, targetKey);
        } else {
            PGobject param = new PGobject();
            param.setType("agtype");
            param.setValue("{\"key_base\": \"" + targetKey + "\"}");
            pstmt.setObject(1, param);
        }
    }

    // =========================================================================
    // QUERIES — INICIALIZAÇÃO
    // =========================================================================

    private String[] QUERIES_STRESS_TEST_READ;
    private String[] QUERIES_WRITE;
    private String[] QUERIES_RFI;

    private void initQueries() {
        QUERIES_STRESS_TEST_READ = new String[] {
                // Q1 — Acesso Atómico (KV)
                "SELECT ag_catalog.agtype_to_text(id) as id, ag_catalog.agtype_to_text(material) as material, q.value->>'cert' AS cert "
                        + "FROM cypher('" + graphName + "', $$ MATCH (p:Part {id: $key_base}) RETURN p.id, p.material $$, ?) AS (id agtype, material agtype) "
                        + "JOIN " + qualityTable + " q ON ag_catalog.agtype_to_text(id) = '\"' || q.key || '\"'",

                // Q2 — Varredura de Telemetria (Documento)
                "SELECT t.id, t.data->>'part_name' as nome, log->>'ts' as tempo, log->>'v' as temp, t.data->>'anomaly' as anomalia "
                        + "FROM " + telemetryTable + " t, jsonb_array_elements(t.data->'sensor_logs') as log "
                        + "WHERE t.data->>'part_id' = ? ORDER BY (log->>'ts')::numeric ASC",

                // Q3 — Fan-out Stress (1-hop)
                "SELECT ag_catalog.agtype_to_text(id) as id "
                        + "FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM]-(v:Part) RETURN v.id $$, ?) AS (id agtype)",

                // Q4 — Filtro de Qualidade (Grafo + KV)
                "SELECT ag_catalog.agtype_to_text(id) as id, ag_catalog.agtype_to_text(material) as material, q.value->>'cert' AS cert "
                        + "FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM]-(v:Part) RETURN v.id, v.material $$, ?) AS (id agtype, material agtype) "
                        + "JOIN " + qualityTable + " q ON ag_catalog.agtype_to_text(id) = '\"' || q.key || '\"' "
                        + "WHERE q.value->>'cert' = 'Tipo B'",

                // Q5 — Travessia Linear Extrema (299 níveis)
                "SELECT ag_catalog.agtype_to_text(id) as id "
                        + "FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) RETURN v.id $$, ?) AS (id agtype)",

                // Q6 — Early Pruning (Grafo + Atributo)
                "SELECT ag_catalog.agtype_to_text(id) as id, ag_catalog.agtype_to_text(material) as material "
                        + "FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) WHERE v.material = 'Titânio' RETURN v.id, v.material $$, ?) AS (id agtype, material agtype)",

                // Q7 — Agregação Recursiva (Grafo + Documento)
                "SELECT AVG((log->>'v')::numeric) as media "
                        + "FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) RETURN v.id $$, ?) AS (id agtype) "
                        + "JOIN " + telemetryTable + " t ON ag_catalog.agtype_to_text(id) = '\"' || (t.data->>'part_id') || '\"' "
                        + "CROSS JOIN jsonb_array_elements(t.data->'sensor_logs') as log",

                // Q8 — Colisão Semântica (Grafo + KV + Documento)
                "SELECT ag_catalog.agtype_to_text(id) as id, q.value->>'cert' AS cert, (log->>'v')::numeric as temperatura "
                        + "FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(p:Part) WHERE p.material = 'Titânio' RETURN p.id $$, ?) AS (id agtype) "
                        + "JOIN " + qualityTable + " q ON ag_catalog.agtype_to_text(id) = '\"' || q.key || '\"' "
                        + "JOIN " + telemetryTable + " t ON ag_catalog.agtype_to_text(id) = '\"' || (t.data->>'part_id') || '\"' "
                        + "CROSS JOIN jsonb_array_elements(t.data->'sensor_logs') as log "
                        + "WHERE q.value->>'cert' = 'Tipo B' AND t.data->>'anomaly' = 'true' AND (log->>'v')::numeric > 92.0",

                // Q9 — Reverse Graph (Bottom-up)
                "SELECT ag_catalog.agtype_to_text(id) as id "
                        + "FROM cypher('" + graphName + "', $$ MATCH (leaf:Part {id: $key_base})-[:BoM*1..299]->(v:Part) RETURN v.id $$, ?) AS (id agtype)",

                // Q10 — Explosão Combinatória / Diamantes de Memória
                "SELECT ag_catalog.agtype_to_text(path) as path, t.data->>'anomaly' as anomalia "
                        + "FROM cypher('" + graphName + "', $$ MATCH p=(root:Part {id: $key_base})<-[:BoM*1..10]-(v:Part) RETURN [n IN nodes(p) | n.id], v.id $$, ?) AS (path agtype, id agtype) "
                        + "JOIN " + telemetryTable + " t ON ag_catalog.agtype_to_text(id) = '\"' || (t.data->>'part_id') || '\"' "
                        + "WHERE t.data->>'anomaly' = 'true'"
        };

        QUERIES_WRITE = new String[] {
                // W1 — Atualização Atómica de KV
                "UPDATE " + qualityTable + " SET value = jsonb_set(jsonb_set(jsonb_set(value, '{cert}', ?::jsonb), '{score}', ?::jsonb), '{is_temp}', 'true'::jsonb) WHERE key = ?",

                // W2 — Injeção de Telemetria IoT (Array Append)
                "UPDATE " + telemetryTable + " SET data = jsonb_set(data, '{sensor_logs}', (data->'sensor_logs') || ?::jsonb) WHERE data->>'part_id' = ?",

                // W3 — Mutação Semântica (Grafo Atributos)
                "SELECT * FROM cypher('" + graphName + "', $$ MATCH (p:Part {id: $key_base}) SET p.material = $novo_material, p.is_temp = true $$, ?) AS (a agtype)",

                // W4 — Colisão de Escrita Multi-Modelo Síncrona
                "WITH t AS (UPDATE " + telemetryTable + " SET data = jsonb_set(jsonb_set(data, '{anomaly}', 'true'::jsonb), '{is_temp}', 'true'::jsonb) WHERE data->>'part_id' = ? RETURNING 1) "
                        + "UPDATE " + qualityTable + " SET value = jsonb_set(jsonb_set(jsonb_set(value, '{cert}', '\"Revogado\"'::jsonb), '{score}', '0'::jsonb), '{is_temp}', 'true'::jsonb) WHERE key = ?",

                // W5 — Mutação Topológica (Insert Edge)
                "SELECT * FROM cypher('" + graphName + "', $$ MATCH (from_node:Part {id: $from_id}), (to_node:Part {id: $to_id}) CREATE (from_node)-[:BoM {qty: 1, type: 'redundant', is_temp: true}]->(to_node) $$, ?) AS (a agtype)",

                // W6 — Atualização de Arestas em Lote
                "SELECT * FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})-[e:BoM]->() SET e.qty = e.qty + 1, e.is_temp = true $$, ?) AS (a agtype)",

                // W7 — Travessia Curta + Atualização (1-hop Cascade)
                "UPDATE " + qualityTable + " SET value = jsonb_set(jsonb_set(value, '{cert}', '\"Em Revisão\"'::jsonb), '{is_temp}', 'true'::jsonb) "
                        + "WHERE '\"' || key || '\"' IN (SELECT ag_catalog.agtype_to_text(id) FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM]-(v:Part) RETURN v.id $$, ?) AS (id agtype))",

                // W8 — Write Carrasco (Deep Traverse Conditional Update)
                "UPDATE " + telemetryTable + " SET data = jsonb_set(jsonb_set(jsonb_set(data, '{anomaly}', 'true'::jsonb), '{is_temp}', 'true'::jsonb), '{sensor_logs}', (data->'sensor_logs') || ?::jsonb) "
                        + "WHERE '\"' || (data->>'part_id') || '\"' IN (SELECT ag_catalog.agtype_to_text(id) FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) WHERE v.material = 'Titânio' RETURN v.id $$, ?) AS (id agtype))",

                // W9 — Reverse Deep Traverse Update
                "SELECT * FROM cypher('" + graphName + "', $$ MATCH (leaf:Part {id: $key_base})-[:BoM*1..299]->(v:Part) SET v.last_inspected = $ts, v.is_temp = true $$, ?) AS (a agtype)",

                // W10 — Memory Diamond Contention
"UPDATE " + qualityTable + " q SET value = jsonb_set(jsonb_set(q.value, '{diamond_hits}', (COALESCE((q.value->>'diamond_hits')::int, 0) + hits.total_hits)::text::jsonb), '{is_temp}', 'true'::jsonb) "
        + "FROM (SELECT ag_catalog.agtype_to_text(id) as part_id, COUNT(*) as total_hits "
        + "FROM cypher('" + graphName + "', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..10]-(v:Part) RETURN v.id $$, ?) AS (id agtype) "
        + "GROUP BY ag_catalog.agtype_to_text(id)) hits "
        + "WHERE '\"' || q.key || '\"' = hits.part_id"
        };

        // RFI: usa as mesmas queries que o workload. O alvo e a profundidade são
        // fixos por query em runRFI() — em particular, todas as queries visam o
        // universo _sf1 para isolar profundidade de volume.
        QUERIES_RFI = new String[] {
                QUERIES_STRESS_TEST_READ[0],
                QUERIES_STRESS_TEST_READ[1],
                QUERIES_STRESS_TEST_READ[2],
                QUERIES_STRESS_TEST_READ[3],
                QUERIES_STRESS_TEST_READ[4],
                QUERIES_STRESS_TEST_READ[5],
                QUERIES_STRESS_TEST_READ[6],
                QUERIES_STRESS_TEST_READ[7],
                QUERIES_STRESS_TEST_READ[8],
                QUERIES_STRESS_TEST_READ[9]
        };
    }

}