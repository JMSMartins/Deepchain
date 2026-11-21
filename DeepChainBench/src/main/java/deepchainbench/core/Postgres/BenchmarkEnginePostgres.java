package deepchainbench.core.Postgres;

import deepchainbench.core.AbstractBenchmarkEngine;
import deepchainbench.core.LinuxProcTelemetry;
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

/**
 * Motor de benchmark para PostgreSQL + Apache AGE.
 *
 * <h2>Nota arquitetural</h2>
 * Este motor testa <b>PostgreSQL + AGE</b> como um MMDBMS, não "PostgreSQL puro".
 * As queries analíticas são expressas em Cypher (via AGE), porque é assim que o
 * motor suporta o modelo de grafo exigido pelo DeepChainBench.
 *
 * <h2>Métrica RFI</h2>
 * O modo RFI está <b>desativado</b> para PostgreSQL + AGE porque o motor não
 * expõe o pico de memória interna alocado por query individual (apenas deltas
 * residuais antes/depois via {@code pg_backend_memory_contexts}).
 *
 * <h2>Stress Test (HTAP)</h2>
 * O stress test usa {@link LinuxProcTelemetry} ({@code /proc/meminfo} e
 * {@code /proc/stat}) de forma idêntica ao motor ArangoDB, e abre uma conexão
 * JDBC dedicada por worker thread para garantir concorrência real no servidor.
 *
 * @see deepchainbench.core.LinuxProcTelemetry
 */
public class BenchmarkEnginePostgres extends AbstractBenchmarkEngine<Connection> {

    public static final int POSTGRES_QUERY_HARD_TIMEOUT_SEC = 120;
    public static final long GLOBAL_BENCHMARK_TIMEOUT_MINUTES = 10L;

    /**
     * Classifica exatamente se a falha no PostgreSQL + AGE foi provocada pelo timeout do código
     * (PreparedStatement.setQueryTimeout) ou por timeout/deadlock/erro da própria base de dados.
     */
    public static FailureOrigin classifyPostgresFailure(SQLException e, long elapsedMs, int queryTimeoutSec) {
        String sqlState = e.getSQLState();
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";

        if ("57014".equals(sqlState) || e instanceof java.sql.SQLTimeoutException) {
            boolean isDbConfiguredTimeout = msg.contains("statement timeout")
                    || msg.contains("lock timeout")
                    || msg.contains("idle-in-transaction")
                    || msg.contains("idle session");
            boolean isCodeTimerWindow = elapsedMs >= (long) (queryTimeoutSec * 1000.0 * 0.90);
            if (!isDbConfiguredTimeout && (isCodeTimerWindow || e instanceof java.sql.SQLTimeoutException)) {
                return FailureOrigin.TIMEOUT_CODE_QUERY;
            }
            return FailureOrigin.TIMEOUT_DB;
        }
        if ("40P01".equals(sqlState) || "40001".equals(sqlState) || "55P03".equals(sqlState)) {
            return FailureOrigin.DB_LOCK_DEADLOCK;
        }
        if (hasCause(e, java.net.SocketTimeoutException.class) || msg.contains("timeout")) {
            return FailureOrigin.TIMEOUT_DB;
        }
        return FailureOrigin.DB_RESOURCE_OR_ERROR;
    }

    public static FailureOrigin classifyPostgresGenericFailure(Throwable e) {
        if (hasCause(e, java.net.SocketTimeoutException.class) || hasCause(e, java.util.concurrent.TimeoutException.class)) {
            return FailureOrigin.TIMEOUT_DB;
        }
        return FailureOrigin.CLIENT_OR_OTHER;
    }

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
        operationRecords.clear();

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
        System.out.println("-> Intervalo de Telemetria (CPU/RAM): " + this.telemetryIntervalMs + " ms");
        System.out.println("-> Diretório de Métricas: " + folderPath);

        // 2. INICIAR TELEMETRIA (RAM total do sistema via /proc/meminfo — alinhado com ArangoDB)
        String globalCsv = folderPath + "/hardware_telemetry.csv";
        TelemetryEngine observador = new LinuxProcTelemetry(globalCsv, this.telemetryIntervalMs);
        long baselineStartTs = System.currentTimeMillis();
        Thread threadObservador = new Thread(observador);
        threadObservador.start();

        System.out.println("-> A gravar baseline de hardware (Aguardando 2 segundos)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        long baselineEndTs = System.currentTimeMillis();

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
            latencyLogs.add(System.currentTimeMillis() + ",COLD_READ," + latencyMs + "," + sucesso + "," + (sucesso ? FailureOrigin.SUCCESS.name() : FailureOrigin.DB_RESOURCE_OR_ERROR.name()));
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
            latencyLogs.add(System.currentTimeMillis() + ",COLD_WRITE," + latencyMs + "," + sucesso + "," + (sucesso ? FailureOrigin.SUCCESS.name() : FailureOrigin.DB_RESOURCE_OR_ERROR.name()));
            executionLogs.add("[" + sdfUtc.format(new java.util.Date()) + "] [Round 0] CONCLUIU COLD_WRITE (W" + (w + 1) + ") em " + latencyMs + " ms.");
        }
        long tFimRound0 = System.currentTimeMillis();
        System.out.println("-> ROUND 0 CONCLUÍDO em " + (tFimRound0 - tInicioRound0) + "ms. Planos em cache.");

        // =====================================================================
        // ATAQUE HTAP
        // =====================================================================
        ExecutorService executor = Executors.newFixedThreadPool(numClientes);
        CountDownLatch latch = new CountDownLatch(numClientes);
        long startTimeGlobal = System.currentTimeMillis();
        System.out.println("-> A INICIAR ATAQUE DE CONCORRÊNCIA HTAP...");

        String jdbcUrl = null;
        String dbUser = "postgres";
        try {
            jdbcUrl = db.getMetaData().getURL();
            String metaUser = db.getMetaData().getUserName();
            if (metaUser != null && !metaUser.isEmpty()) {
                dbUser = metaUser;
            }
        } catch (SQLException e) {
            System.err.println("[Aviso] Não foi possível ler metadata da ligação JDBC: " + e.getMessage());
        }
        final String workerJdbcUrl = jdbcUrl;
        final String workerDbUser = dbUser;

        for (int i = 0; i < numClientes; i++) {
            final int streamId = i;
            executor.submit(() -> {
                Connection workerConn = openWorkerConnection(workerJdbcUrl, workerDbUser, db);
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
                        FailureOrigin failureOrigin = FailureOrigin.SUCCESS;
                        String erroDetalhe = "";

                        try (PreparedStatement pstmt = workerConn.prepareStatement(queryExecutar)) {
                            pstmt.setQueryTimeout(POSTGRES_QUERY_HARD_TIMEOUT_SEC);

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
                            long elapsedMs = (System.nanoTime() - startQuery) / 1_000_000;
                            failureOrigin = classifyPostgresFailure(e, elapsedMs, POSTGRES_QUERY_HARD_TIMEOUT_SEC);
                            erroDetalhe = failureOrigin.getLabel() + " [SQLSTATE " + e.getSQLState() + "]";
                            falhasContencao.add(failureOrigin.name());
                        } catch (Exception e) {
                            sucesso = false;
                            failureOrigin = classifyPostgresGenericFailure(e);
                            erroDetalhe = failureOrigin.getLabel() + " (" + e.getClass().getSimpleName() + ")";
                            falhasContencao.add(failureOrigin.name());
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
                                    + " [" + failureOrigin.getLabel() + "] (" + erroDetalhe + ") após " + latencyMs + " ms.";
                        }

                        System.out.println("   " + logEnd);
                        executionLogs.add(logEnd);

                        latencies.add(latencyMs);
                        long timeNow = System.currentTimeMillis();
                        operationRecords.add(new OperationRecord(timeNow, isRead, latencyMs, sucesso, failureOrigin));
                        latencyLogs.add(timeNow + "," + (isRead ? "READ" : "WRITE")
                                + "," + latencyMs + "," + sucesso + "," + failureOrigin.name());
                    }
                } catch (Exception e) {
                    System.err.println("Erro crítico no Stream " + streamId + ": " + e.getMessage());
                } finally {
                    closeWorkerConnection(workerConn, db);
                    latch.countDown();
                }
            });
        }

        boolean terminouNormalmente = true;
        try {
            terminouNormalmente = latch.await(GLOBAL_BENCHMARK_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
            if (!terminouNormalmente) {
                System.err.println("\n[ERRO FATAL] O Benchmark excedeu o limite global de " + GLOBAL_BENCHMARK_TIMEOUT_MINUTES + " minutos. A forçar paragem.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            terminouNormalmente = false;
            executor.shutdownNow();
        }

        long endTimeGlobal = System.currentTimeMillis();
        long durationTotalMs = endTimeGlobal - startTimeGlobal;
        if (!terminouNormalmente) {
            registarOperacoesNaoConcluidasPorTimeoutGlobal(totalPedidos, durationTotalMs);
        }
        executor.shutdown();

        // 4. PARAR TELEMETRIA
        System.out.println("-> A gravar cooldown de hardware (Aguardando 1 segundo)...");
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        observador.stopEngine();
        try { Thread.sleep(200); } catch (InterruptedException e) {}

        finalizarEReportarHTAP(
                sessionTimestamp, "PostgreSQL+AGE", this.graphName, perfilNome,
                numClientes, totalPedidos, folderPath, globalCsv, observador,
                baselineStartTs, baselineEndTs, tInicioRound0, tFimRound0,
                startTimeGlobal, endTimeGlobal, durationTotalMs
        );
    }

    // =========================================================================
    // RFI (Resource Footprint Index) — DESATIVADO PARA POSTGRESQL + AGE
    // =========================================================================

    /**
     * Modo RFI desativado para PostgreSQL + AGE: o motor não expõe o pico de
     * memória alocado por query individual (apenas deltas residuais), pelo que
     * apenas o ArangoDB suporta a métrica RFI.
     */
    @Override
    public void runRFI(Connection db, String queryId, int warmRuns) {
        String msg = "Modo RFI desativado para PostgreSQL + AGE: o motor não expõe o pico de memória por query.";
        System.out.println("[AVISO] " + msg);
        throw new UnsupportedOperationException(msg);
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

    private Connection openWorkerConnection(String jdbcUrl, String user, Connection fallback) {
        if (jdbcUrl == null) {
            return fallback;
        }
        String password = System.getenv("DB_PASS");
        if (password == null) {
            password = "password";
        }
        try {
            Connection workerConn = DriverManager.getConnection(jdbcUrl, user, password);
            try (Statement stmt = workerConn.createStatement()) {
                stmt.execute("LOAD 'age';");
                stmt.execute("SET search_path = public, ag_catalog, \"$user\";");
            }
            return workerConn;
        } catch (Exception e) {
            System.err.println("[Aviso] Falha ao abrir ligação dedicada para worker. A usar a principal: " + e.getMessage());
            return fallback;
        }
    }

    private void closeWorkerConnection(Connection workerConn, Connection fallback) {
        if (workerConn != null && workerConn != fallback) {
            try { workerConn.close(); } catch (Exception ignored) {}
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

    private String[] QUERIES_STRESS_TEST_READ;
    private String[] QUERIES_WRITE;

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
    }

}