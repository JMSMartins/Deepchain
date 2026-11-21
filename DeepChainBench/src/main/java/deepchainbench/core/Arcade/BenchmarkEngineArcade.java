package deepchainbench.core.Arcade;

import com.arcadedb.database.BasicDatabase;
import com.arcadedb.exception.ArcadeDBException;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.exception.TimeoutException;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.remote.RemoteDatabase;
import com.arcadedb.remote.RemoteException;
import deepchainbench.core.AbstractBenchmarkEngine;
import deepchainbench.core.LinuxProcTelemetry;
import deepchainbench.core.TelemetryEngine;

import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Motor de benchmark HTAP (Leitura + Escrita concorrentes) para ArcadeDB.
 * Suporta {@link RemoteDatabase} (execução real via servidor HTTP) e bases embebidas via {@link BasicDatabase}.
 */
public class BenchmarkEngineArcade extends AbstractBenchmarkEngine<BasicDatabase> {

    public static final int ARCADE_QUERY_HARD_TIMEOUT_SEC = 120;
    public static final long ARCADE_QUERY_HARD_TIMEOUT_MS = ARCADE_QUERY_HARD_TIMEOUT_SEC * 1000L;
    public static final long GLOBAL_BENCHMARK_TIMEOUT_MINUTES = 10L;

    static final String[] QUERIES_STRESS_TEST_READ = {
            // Q1 — Lookup Multi-Modelo Simples (Grafo Vértice + KV)
            "SELECT _key AS peca, material, $kv[0].cert AS certificacao "
                    + "FROM Parts "
                    + "LET $kv = (SELECT cert FROM Quality_KV WHERE _key = :key_base) "
                    + "WHERE _key = :key_base TIMEOUT 120000",

            // Q2 — Varredura de Telemetria (Documento + UNWIND + Ordenação)
            "SELECT @rid AS key, part_name AS nome, sensor_logs.ts AS tempo, sensor_logs.v AS temp, anomaly AS anomalia "
                    + "FROM (SELECT @rid, part_name, anomaly, sensor_logs FROM Telemetry WHERE part_id = :key_base UNWIND sensor_logs) "
                    + "ORDER BY tempo ASC TIMEOUT 120000",

            // Q3 — Fan-out Stress (Grafo 1-hop)
            "SELECT :key_base AS raiz, v._key AS filho "
                    + "FROM (MATCH {type: Parts, where: (_key = :key_base)}.in('BoM_Edges'){as: v} RETURN v) "
                    + "TIMEOUT 120000",

            // Q4 — Filtro de Qualidade (Grafo 1-hop + KV)
            "SELECT :key_base AS raiz, v._key AS filho, v.material AS material, $kv[0].cert AS certificacao "
                    + "FROM (MATCH {type: Parts, where: (_key = :key_base)}.in('BoM_Edges'){as: v} RETURN v) "
                    + "LET $kv = (SELECT cert FROM Quality_KV WHERE _key = $parent.$current.v._key AND cert = 'Tipo B') "
                    + "WHERE $kv.size() > 0 TIMEOUT 120000",

            // Q5 — Travessia Linear Extrema (299 níveis)
            "SELECT _key AS key "
                    + "FROM (TRAVERSE in('BoM_Edges') FROM (SELECT FROM Parts WHERE _key = :key_base) MAXDEPTH 299) "
                    + "WHERE $depth > 0 TIMEOUT 120000",

            // Q6 — Early Pruning (Grafo 299 níveis + Filtro Semântico)
            "SELECT _key AS peca, material "
                    + "FROM (TRAVERSE in('BoM_Edges') FROM (SELECT FROM Parts WHERE _key = :key_base) MAXDEPTH 299) "
                    + "WHERE $depth > 0 AND material = 'Titânio' TIMEOUT 120000",

            // Q7 — Agregação Recursiva (Grafo 299 níveis + Telemetria)
            "SELECT avg(log.v) AS media FROM ("
                    + "SELECT log FROM ("
                    + "SELECT $t[0].sensor_logs AS log FROM ("
                    + "SELECT _key FROM (TRAVERSE in('BoM_Edges') FROM (SELECT FROM Parts WHERE _key = :key_base) MAXDEPTH 299) WHERE $depth > 0"
                    + ") LET $t = (SELECT sensor_logs FROM Telemetry WHERE part_id = $parent.$current._key) "
                    + "WHERE $t.size() > 0"
                    + ") UNWIND log"
                    + ") TIMEOUT 120000",

            // Q8 — Colisão Semântica Completa (Grafo + KV + Documento)
            "SELECT :key_base AS raiz, vkey AS peca, 'Tipo B' AS certificacao, log.v AS temperatura "
                    + "FROM ("
                    + "SELECT vkey, log FROM ("
                    + "SELECT _key AS vkey, $t[0].sensor_logs AS log "
                    + "FROM (TRAVERSE in('BoM_Edges') FROM (SELECT FROM Parts WHERE _key = :key_base) MAXDEPTH 299) "
                    + "LET $kv = (SELECT cert FROM Quality_KV WHERE _key = $parent.$current._key AND cert = 'Tipo B'), "
                    + "$t = (SELECT sensor_logs FROM Telemetry WHERE part_id = $parent.$current._key AND anomaly = true) "
                    + "WHERE $depth > 0 AND material = 'Titânio' AND $kv.size() > 0 AND $t.size() > 0"
                    + ") UNWIND log"
                    + ") WHERE log.v > 92.0 TIMEOUT 120000",

            // Q9 — Reverse Graph Bottom-up (299 níveis)
            "SELECT _key AS key "
                    + "FROM (TRAVERSE out('BoM_Edges') FROM (SELECT FROM Parts WHERE _key = :key_base) MAXDEPTH 299) "
                    + "WHERE $depth > 0 TIMEOUT 120000",

            // Q10 — Explosão Combinatória / Diamantes de Memória (profundidade 1..10)
            "SELECT caminho, $t[0].anomaly AS anomalia "
                    + "FROM ("
                    + "MATCH {type: Parts, where: (_key = :key_base)}.in('BoM_Edges'){as: v, while: ($depth < 10), where: ($depth > 0)} "
                    + "RETURN $path AS caminho, v._key AS vkey"
                    + ") "
                    + "LET $t = (SELECT anomaly FROM Telemetry WHERE part_id = $parent.$current.vkey AND anomaly = true) "
                    + "WHERE $t.size() > 0 TIMEOUT 120000"
    };

    static final String[] QUERIES_WRITE = {
            // W1 — Atualização Atómica de KV
            "UPDATE Quality_KV SET cert = :nova_cert, score = :novo_score, is_temp = true WHERE _key = :key_base",

            // W2 — Injeção de Telemetria IoT (Array Append)
            "UPDATE Telemetry SET sensor_logs += {\"ts\": :ts, \"v\": :v, \"is_temp\": true}, is_temp = true WHERE part_id = :key_base",

            // W3 — Mutação Semântica em Vértice (Grafo)
            "UPDATE Parts SET material = :novo_material, is_temp = true WHERE _key = :key_base",

            // W4 — Colisão de Escrita Multi-Modelo Síncrona (Telemetry + Quality_KV)
            "UPDATE Telemetry SET anomaly = true, is_temp = true WHERE part_id = :key_base;\n"
                    + "UPDATE Quality_KV SET cert = 'Revogado', score = 0, is_temp = true WHERE _key = :key_base;",

            // W5 — Mutação Topológica (Inserção de Aresta Redundante)
            "CREATE EDGE BoM_Edges FROM (SELECT FROM Parts WHERE _key = :from_id) TO (SELECT FROM Parts WHERE _key = :to_id) "
                    + "SET qty = 1, type = 'redundant', is_temp = true",

            // W6 — Atualização de Arestas em Lote
            "UPDATE BoM_Edges SET qty = qty + 1, is_temp = true WHERE @out IN (SELECT @rid FROM Parts WHERE _key = :key_base)",

            // W7 — Cascata 1-Hop (Grafo + KV)
            "LET $k = MATCH {type: Parts, where: (_key = :key_base)}.in('BoM_Edges'){as: v} RETURN v._key AS k;\n"
                    + "UPDATE Quality_KV SET cert = 'Em Revisão', is_temp = true WHERE _key IN $k.k;",

            // W8 — Write Carrasco (Travessia 299 níveis + Telemetria condicional)
            "LET $k = SELECT _key AS k FROM (TRAVERSE in('BoM_Edges') FROM (SELECT FROM Parts WHERE _key = :key_base) MAXDEPTH 299) "
                    + "WHERE $depth > 0 AND material = 'Titânio';\n"
                    + "UPDATE Telemetry SET anomaly = true, is_temp = true, sensor_logs += {\"ts\": :ts, \"v\": :v, \"is_temp\": true} WHERE part_id IN $k.k;",

            // W9 — Reverse Deep Traverse Update (Bottom-up)
            "LET $k = SELECT _key AS k FROM (TRAVERSE out('BoM_Edges') FROM (SELECT FROM Parts WHERE _key = :key_base) MAXDEPTH 299) "
                    + "WHERE $depth > 0;\n"
                    + "UPDATE Parts SET last_inspected = :ts, is_temp = true WHERE _key IN $k.k;",

            // W10 — Contenção em Diamantes de Memória (Grafo + KV)
            "LET $k = MATCH {type: Parts, where: (_key = :key_base)}.in('BoM_Edges'){as: v, while: ($depth < 10), where: ($depth > 0)} "
                    + "RETURN v._key AS k;\n"
                    + "UPDATE Quality_KV SET diamond_hits = ifnull(diamond_hits, 0) + 1, is_temp = true WHERE _key IN $k.k;"
    };

    private final String host;
    private final int port;
    private final String user;
    private final String password;

    public BenchmarkEngineArcade() {
        this("127.0.0.1", 2480, "root", "password");
    }

    public BenchmarkEngineArcade(String host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    /**
     * Classifica a origem de falha numa operação ArcadeDB (timeout cliente/HTTP, timeout de servidor,
     * conflito MVCC / lock ou erro interno de servidor).
     */
    public static FailureOrigin classifyArcadeFailure(Throwable e, long elapsedMs, int queryTimeoutSec) {
        if (e == null) return FailureOrigin.CLIENT_OR_OTHER;

        if (hasCause(e, SocketTimeoutException.class)
                || hasCause(e, java.util.concurrent.TimeoutException.class)) {
            return FailureOrigin.TIMEOUT_CODE_QUERY;
        }
        if (hasCause(e, TimeoutException.class)) {
            return (elapsedMs >= queryTimeoutSec * 900L)
                    ? FailureOrigin.TIMEOUT_CODE_QUERY
                    : FailureOrigin.TIMEOUT_DB;
        }
        if (hasCause(e, ConcurrentModificationException.class)
                || hasCause(e, NeedRetryException.class)) {
            return FailureOrigin.DB_LOCK_DEADLOCK;
        }

        String msg = collectMessagesLower(e);
        if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("time out")) {
            return (elapsedMs >= queryTimeoutSec * 900L)
                    ? FailureOrigin.TIMEOUT_CODE_QUERY
                    : FailureOrigin.TIMEOUT_DB;
        }
        if (msg.contains("concurrent modification") || msg.contains("mvcc")
                || msg.contains("deadlock") || msg.contains("lock") || msg.contains("needretry")) {
            return FailureOrigin.DB_LOCK_DEADLOCK;
        }
        if (hasCause(e, RemoteException.class) || hasCause(e, ArcadeDBException.class)) {
            return FailureOrigin.DB_RESOURCE_OR_ERROR;
        }
        return FailureOrigin.CLIENT_OR_OTHER;
    }

    private static String collectMessagesLower(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable curr = e;
        int depth = 0;
        while (curr != null && depth < 10) {
            if (curr.getMessage() != null) {
                sb.append(' ').append(curr.getMessage().toLowerCase(Locale.ROOT));
            }
            curr = curr.getCause();
            depth++;
        }
        return sb.toString();
    }

    @Override
    public void warmUpAndHarvest(BasicDatabase db) {
        System.out.println("   [Benchmark] A iniciar Warm-up e colheita de parâmetros no ArcadeDB...");
        try {
            long totalParts = db.countType("Parts", true);
            this.sfGlobalAtivo = (int) (totalParts / 2000L);
            if (this.sfGlobalAtivo < 1) this.sfGlobalAtivo = 1;
            System.out.println("   [Benchmark] Scale Factor detetado no motor: SF" + this.sfGlobalAtivo);

            partKeysPool.clear();
            try (ResultSet rs = db.query("sql", "SELECT _key FROM Parts LIMIT 1000")) {
                while (rs.hasNext()) {
                    String key = rs.next().getProperty("_key");
                    if (key != null && !key.isEmpty()) {
                        partKeysPool.add(key);
                    }
                }
            }
            if (partKeysPool.isEmpty()) {
                throw new IllegalStateException("O tipo 'Parts' está vazio na BD ArcadeDB '" + db.getName() + "'. Execute a ingestão primeiro.");
            }
            System.out.println("   [Benchmark] Pool carregada com " + partKeysPool.size() + " IDs.");
        } catch (Exception e) {
            System.err.println("   [ERRO] Falha no warm-up: " + e.getMessage());
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    @Override
    public void runWorkload(BasicDatabase db, String perfilNome, int[] probsLeitura, int[] probsEscrita,
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

        String sessionTimestamp = String.valueOf(System.currentTimeMillis());
        String folderPath = "./metrics/Task_" + perfilNome + "_" + sessionTimestamp;
        java.io.File directory = new java.io.File(folderPath);
        if (!directory.exists()) directory.mkdirs();

        SimpleDateFormat sdfUtc = new SimpleDateFormat("HH:mm:ss.SSS");
        sdfUtc.setTimeZone(TimeZone.getTimeZone("UTC"));

        System.out.println("\n--- EXECUÇÃO DO BENCHMARK: " + perfilNome + " ---");
        System.out.println("-> Clientes Simultâneos: " + numClientes);
        System.out.println("-> Carga: " + pedidosPorCliente + " queries/cliente (Total: " + totalPedidos + " operações)");
        System.out.println("-> Rácio Leitura/Escrita: " + readPercentage + "% / " + (100 - readPercentage) + "%");
        System.out.println("-> Intervalo de Telemetria (CPU/RAM): " + this.telemetryIntervalMs + " ms");
        System.out.println("-> Diretório de Métricas: " + folderPath);

        // Telemetria global unificada via /proc/meminfo
        String globalCsv = folderPath + "/hardware_telemetry.csv";
        TelemetryEngine observador = new LinuxProcTelemetry(globalCsv, this.telemetryIntervalMs);
        long baselineStartTs = System.currentTimeMillis();
        Thread threadObservador = new Thread(observador);
        threadObservador.start();

        System.out.println("-> A gravar baseline de hardware (Aguardando 2 segundos)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        long baselineEndTs = System.currentTimeMillis();

        // ROUND 0 (COLD START)
        System.out.println("-> A EXECUTAR ROUND 0 (COLD START) - Aquecer Caches do Motor...");
        long tInicioRound0 = System.currentTimeMillis();
        Random coldRandom = new Random(99999);

        for (int q = 0; q < QUERIES_STRESS_TEST_READ.length; q++) {
            String keyBase = (q < 2)
                    ? String.valueOf(coldRandom.nextInt(2000))
                    : TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)];
            String targetKey = keyBase + "_sf" + this.sfGlobalAtivo;

            long startQuery = System.nanoTime();
            boolean sucesso = true;
            FailureOrigin origin = FailureOrigin.SUCCESS;
            try {
                executeReadOperation(db, q, targetKey);
            } catch (Exception e) {
                sucesso = false;
                long elapsed = (System.nanoTime() - startQuery) / 1_000_000;
                origin = classifyArcadeFailure(e, elapsed, ARCADE_QUERY_HARD_TIMEOUT_SEC);
            }

            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            latencyLogs.add(System.currentTimeMillis() + ",COLD_READ," + latencyMs + "," + sucesso + "," + origin.name());
            executionLogs.add("[" + sdfUtc.format(new Date()) + "] [Round 0] CONCLUIU COLD_READ (Q" + (q + 1) + ") em " + latencyMs + " ms.");
        }

        for (int w = 0; w < QUERIES_WRITE.length; w++) {
            String keyBase = (w < 4)
                    ? String.valueOf(coldRandom.nextInt(2000))
                    : TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)];
            if (w == 8) keyBase = coldRandom.nextBoolean() ? "799" : "1599";
            String targetKey = keyBase + "_sf" + this.sfGlobalAtivo;

            long startQuery = System.nanoTime();
            boolean sucesso = true;
            FailureOrigin origin = FailureOrigin.SUCCESS;
            try {
                executeWriteOperation(db, w, targetKey, coldRandom);
            } catch (Exception e) {
                sucesso = false;
                long elapsed = (System.nanoTime() - startQuery) / 1_000_000;
                origin = classifyArcadeFailure(e, elapsed, ARCADE_QUERY_HARD_TIMEOUT_SEC);
            }

            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            latencyLogs.add(System.currentTimeMillis() + ",COLD_WRITE," + latencyMs + "," + sucesso + "," + origin.name());
            executionLogs.add("[" + sdfUtc.format(new Date()) + "] [Round 0] CONCLUIU COLD_WRITE (W" + (w + 1) + ") em " + latencyMs + " ms.");
        }
        long tFimRound0 = System.currentTimeMillis();
        System.out.println("-> ROUND 0 CONCLUÍDO em " + (tFimRound0 - tInicioRound0) + "ms. Planos em cache.");

        // ATAQUE HTAP
        ExecutorService executor = Executors.newFixedThreadPool(numClientes);
        CountDownLatch latch = new CountDownLatch(numClientes);
        long startTimeGlobal = System.currentTimeMillis();
        System.out.println("-> A INICIAR ATAQUE DE CONCORRÊNCIA HTAP...");

        for (int i = 0; i < numClientes; i++) {
            final int streamId = i;
            executor.submit(() -> {
                BasicDatabase workerDb = openWorkerDatabase(db);
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

                        int queryIndex = isRead
                                ? escolherQueryPorPerfil(probsLeitura, deterministico.nextInt(100))
                                : escolherQueryPorPerfil(probsEscrita, deterministico.nextInt(100));
                        String labelQuery = isRead
                                ? "READ (Q" + (queryIndex + 1) + ")"
                                : "WRITE (W" + (queryIndex + 1) + ")";

                        String logStart = "[" + sdfUtc.format(new Date()) + "] [Stream "
                                + String.format("%03d", streamId) + "] INICIOU " + labelQuery
                                + " no target: " + targetKey;
                        System.out.println("   " + logStart);
                        executionLogs.add(logStart);

                        long startQuery = System.nanoTime();
                        boolean sucesso = true;
                        FailureOrigin failureOrigin = FailureOrigin.SUCCESS;
                        String erroDetalhe = "";

                        try {
                            if (isRead) {
                                executeReadOperation(workerDb, queryIndex, targetKey);
                            } else {
                                executeWriteOperation(workerDb, queryIndex, targetKey, deterministico);
                            }
                        } catch (Exception e) {
                            sucesso = false;
                            long elapsedMs = (System.nanoTime() - startQuery) / 1_000_000;
                            failureOrigin = classifyArcadeFailure(e, elapsedMs, ARCADE_QUERY_HARD_TIMEOUT_SEC);
                            erroDetalhe = failureOrigin.getLabel() + " (" + e.getClass().getSimpleName() + ")";
                            falhasContencao.add(failureOrigin.name());
                        }

                        long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
                        String logEnd = sucesso
                                ? "[" + sdfUtc.format(new Date()) + "] [Stream "
                                    + String.format("%03d", streamId) + "] CONCLUIU " + labelQuery
                                    + " em " + latencyMs + " ms."
                                : "[" + sdfUtc.format(new Date()) + "] [Stream "
                                    + String.format("%03d", streamId) + "] FALHOU   " + labelQuery
                                    + " [" + failureOrigin.getLabel() + "] (" + erroDetalhe + ") após " + latencyMs + " ms.";

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
                    closeWorkerDatabase(workerDb, db);
                    latch.countDown();
                }
            });
        }

        boolean terminouNormalmente = true;
        try {
            terminouNormalmente = latch.await(GLOBAL_BENCHMARK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
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

        System.out.println("-> A gravar cooldown de hardware (Aguardando 1 segundo)...");
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        observador.stopEngine();
        try { Thread.sleep(200); } catch (InterruptedException e) {}

        finalizarEReportarHTAP(
                sessionTimestamp, "ArcadeDB", db.getName(), perfilNome,
                numClientes, totalPedidos, folderPath, globalCsv, observador,
                baselineStartTs, baselineEndTs, tInicioRound0, tFimRound0,
                startTimeGlobal, endTimeGlobal, durationTotalMs
        );
    }

    void executeReadOperation(BasicDatabase targetDb, int queryIndex, String targetKey) {
        try (ResultSet rs = targetDb.query("sql", QUERIES_STRESS_TEST_READ[queryIndex], Map.of("key_base", targetKey))) {
            while (rs.hasNext()) {
                rs.next();
            }
        }
    }

    void executeWriteOperation(BasicDatabase targetDb, int writeIndex, String targetKey, Random rng) {
        String sql = QUERIES_WRITE[writeIndex];
        Map<String, Object> params = buildWriteParams(writeIndex, targetKey, rng);
        boolean isScript = (writeIndex == 3 || writeIndex == 6 || writeIndex == 7 || writeIndex == 8 || writeIndex == 9);

        targetDb.transaction(() -> {
            try (ResultSet rs = targetDb.command(isScript ? "sqlscript" : "sql", sql, params)) {
                while (rs.hasNext()) {
                    rs.next();
                }
            }
        });
    }

    private Map<String, Object> buildWriteParams(int writeIndex, String targetKey, Random rng) {
        Map<String, Object> params = new HashMap<>();
        switch (writeIndex) {
            case 0: // W1
                params.put("key_base", targetKey);
                params.put("nova_cert", "Tipo A");
                params.put("novo_score", rng.nextInt(100));
                break;
            case 1: // W2
                params.put("key_base", targetKey);
                params.put("ts", System.currentTimeMillis());
                params.put("v", 70.0 + (rng.nextDouble() * 35.0));
                break;
            case 2: // W3
                params.put("key_base", targetKey);
                params.put("novo_material", "Titânio");
                break;
            case 4: // W5
                String toKey = ((rng.nextInt(1999) + 1)) + "_sf" + this.sfGlobalAtivo;
                if (toKey.equals(targetKey)) {
                    toKey = "0_sf" + this.sfGlobalAtivo;
                }
                params.put("from_id", targetKey);
                params.put("to_id", toKey);
                break;
            case 7: // W8
                params.put("key_base", targetKey);
                params.put("ts", System.currentTimeMillis());
                params.put("v", 99.9);
                break;
            case 8: // W9
                params.put("key_base", targetKey);
                params.put("ts", System.currentTimeMillis());
                break;
            default: // W4 (3), W6 (5), W7 (6), W10 (9)
                params.put("key_base", targetKey);
                break;
        }
        return params;
    }

    private BasicDatabase openWorkerDatabase(BasicDatabase sharedDb) {
        if (sharedDb instanceof RemoteDatabase) {
            try {
                RemoteDatabase worker = new RemoteDatabase(host, port, sharedDb.getName(), user, password);
                worker.setTimeout((int) (ARCADE_QUERY_HARD_TIMEOUT_MS + 5_000L));
                return worker;
            } catch (Exception e) {
                System.err.println("[Aviso] Não foi possível abrir sessão dedicada ArcadeDB, a usar partilhada: " + e.getMessage());
            }
        }
        return sharedDb;
    }

    private void closeWorkerDatabase(BasicDatabase workerDb, BasicDatabase sharedDb) {
        if (workerDb != null && workerDb != sharedDb) {
            try {
                workerDb.close();
            } catch (Exception e) {
                // Ignorar erro ao fechar sessão do worker
            }
        }
    }

    @Override
    public void limparDadosTemporarios(BasicDatabase db) {
        System.out.println("\n-> [Limpeza] A iniciar protocolo de reversibilidade (ArcadeDB)...");
        long t0 = System.currentTimeMillis();
        String[] cleanupSql = {
                "DELETE FROM BoM_Edges WHERE is_temp = true AND type = 'redundant'",
                "UPDATE BoM_Edges REMOVE is_temp WHERE is_temp = true",
                "UPDATE Quality_KV REMOVE is_temp, diamond_hits WHERE is_temp = true",
                "UPDATE Parts REMOVE last_inspected, is_temp WHERE is_temp = true",
                "UPDATE Telemetry SET anomaly = false REMOVE is_temp WHERE is_temp = true"
        };
        try {
            db.transaction(() -> {
                for (String stmt : cleanupSql) {
                    try (ResultSet rs = db.command("sql", stmt)) {
                        while (rs.hasNext()) {
                            rs.next();
                        }
                    }
                }
            });
            System.out.println("-> [Limpeza] Base de dados restaurada ao estado original em " + (System.currentTimeMillis() - t0) + " ms.");
        } catch (Exception e) {
            System.err.println("[ERRO] Falha durante limpeza de dados temporários no ArcadeDB: " + e.getMessage());
        }
    }

    @Override
    public void runRFI(BasicDatabase db, String queryId, int iterations) {
        throw new UnsupportedOperationException(
                "Modo RFI desativado para ArcadeDB: o motor não expõe o pico de memória por query (peakMemoryUsage).");
    }
}
