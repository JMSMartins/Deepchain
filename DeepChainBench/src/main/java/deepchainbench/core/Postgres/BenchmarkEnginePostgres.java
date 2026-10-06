package deepchainbench.core.Postgres;

import deepchainbench.core.AbstractBenchmarkEngine;
import deepchainbench.core.MetricsExporter;
import deepchainbench.core.TelemetryEngine;
import deepchainbench.generator.GraphGenerator;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BenchmarkEnginePostgres extends AbstractBenchmarkEngine<Connection> {

    private String graphName = "deepchaindb";
    private String qualityTable;
    private String telemetryTable;

    public BenchmarkEnginePostgres() {
        this.qualityTable = this.graphName + "_quality_kv";
        this.telemetryTable = this.graphName + "_telemetry";
    }

    public BenchmarkEnginePostgres(String graphName) {
        this.graphName = graphName.toLowerCase();
        this.qualityTable = this.graphName + "_quality_kv";
        this.telemetryTable = this.graphName + "_telemetry";
    }

    private String prepareQuery(String query) {
        return query.replace("deepchaindb", this.graphName);
    }

    @Override
    public void warmUpAndHarvest(Connection conn) {
        System.out.println("   [Benchmark] A iniciar Warm-up e colheita de parâmetros no PostgreSQL (AGE)...");
        try {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("LOAD 'age';");
                stmt.execute("SET search_path = ag_catalog, \"$user\", public;");
            }
            long totalParts = 0;
            String countSql = prepareQuery("SELECT count(*) FROM cypher('deepchaindb', $$ MATCH (p:Part) RETURN p $$) AS (p agtype);");
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(countSql)) {
                if (rs.next()) totalParts = rs.getLong(1);
            }
            this.sfGlobalAtivo = (int) (totalParts / 2000);
            if (this.sfGlobalAtivo < 1) this.sfGlobalAtivo = 1;
            System.out.println("   [Benchmark] Scale Factor detetado no motor: SF" + this.sfGlobalAtivo);
            partKeysPool.clear();
            String harvestSql = prepareQuery("SELECT ag_catalog.agtype_to_text(id) FROM cypher('deepchaindb', $$ MATCH (p:Part) RETURN p.id $$) AS (id agtype) LIMIT 1000;");
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(harvestSql)) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    if (id != null) partKeysPool.add(id.replaceAll("^\"|\"$", ""));
                }
            }
            if (partKeysPool.isEmpty()) throw new IllegalStateException("A coleção 'Part' está vazia no Grafo " + graphName + ". Execute a ingestão primeiro.");
            System.out.println("   [Benchmark] Pool carregada com " + partKeysPool.size() + " IDs.");
        } catch (Exception e) {
            System.err.println("   [ERRO] Falha no warm-up: " + e.getMessage());
        }
    }

    @Override
    public void runWorkload(Connection db, String perfilNome, int[] probsLeitura, int[] probsEscrita, int readPercentage, int numClientes) {
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
        String sessionTimestamp = String.valueOf(System.currentTimeMillis());
        String folderPath = "./metrics/Task_" + perfilNome + "_" + sessionTimestamp;
        java.io.File directory = new java.io.File(folderPath);
        if (!directory.exists()) directory.mkdirs();
        System.out.println("\n--- EXECUÇÃO DO BENCHMARK: " + perfilNome + " ---");
        System.out.println("-> Clientes Simultâneos: " + numClientes);
        System.out.println("-> Carga: " + pedidosPorCliente + " queries/cliente (Total: " + totalPedidos + " operações)");
        System.out.println("-> Rácio Leitura/Escrita: " + readPercentage + "% / " + (100 - readPercentage) + "%");
        System.out.println("-> Diretório de Métricas: " + folderPath);
        String globalCsv = folderPath + "/hardware_telemetry.csv";
        TelemetryEngine observador = new PostgresTelemetry(globalCsv, db);
        Thread threadObservador = new Thread(observador);
        threadObservador.start();
        System.out.println("-> A gravar baseline de hardware (Aguardando 2 segundos)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        System.out.println("-> A EXECUTAR ROUND 0 (COLD START) - Aquecer Caches do Motor...");
        long tInicioRound0 = System.currentTimeMillis();
        Random coldRandom = new Random(99999);
        for (int q = 0; q < QUERIES_STRESS_TEST_READ.length; q++) {
            String keyBase = (q < 2) ? String.valueOf(coldRandom.nextInt(2000)) : TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)];
            long startQuery = System.nanoTime();
            boolean sucesso = true;
            try (PreparedStatement pstmt = db.prepareStatement(prepareQuery(QUERIES_STRESS_TEST_READ[q]))) {
                PGobject param = new PGobject();
                param.setType("agtype");
                param.setValue("{\"key_base\": \"" + keyBase + "_sf" + this.sfGlobalAtivo + "\"}");
                if (q == 1) pstmt.setString(1, keyBase + "_sf" + this.sfGlobalAtivo);
                else pstmt.setObject(1, param);
                try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) {} }
            } catch (Exception e) { sucesso = false; }
            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            latencyLogs.add(System.currentTimeMillis() + ",COLD_READ," + latencyMs + "," + sucesso);
        }
        for (int w = 0; w < QUERIES_WRITE.length; w++) {
            String keyBase = (w < 4) ? String.valueOf(coldRandom.nextInt(2000)) : TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)];
            if (w == 8) keyBase = coldRandom.nextBoolean() ? "799" : "1599";
            String targetKey = keyBase + "_sf" + this.sfGlobalAtivo;
            long startQuery = System.nanoTime();
            boolean sucesso = true;
            try (PreparedStatement pstmt = db.prepareStatement(prepareQuery(QUERIES_WRITE[w]))) {
                if (w == 0) {
                    pstmt.setString(1, "\"Tipo A\"");
                    pstmt.setString(2, "99");
                    pstmt.setString(3, targetKey);
                } else if (w == 1) {
                    pstmt.setString(1, "{\"ts\": " + System.currentTimeMillis() + ", \"v\": 85.5, \"is_temp\": true}");
                    pstmt.setString(2, targetKey);
                } else if (w == 2) {
                    PGobject param = new PGobject(); param.setType("agtype");
                    param.setValue("{\"key_base\": \"" + targetKey + "\", \"novo_material\": \"Titânio\"}");
                    pstmt.setObject(1, param);
                } else if (w == 3) {
                    pstmt.setString(1, targetKey);
                    pstmt.setString(2, targetKey);
                } else if (w == 4) {
                    PGobject param = new PGobject(); param.setType("agtype");
                    param.setValue("{\"from_id\": \"" + targetKey + "\", \"to_id\": \"" + String.valueOf(coldRandom.nextInt(2000)) + "_sf" + this.sfGlobalAtivo + "\"}");
                    pstmt.setObject(1, param);
                } else if (w == 5 || w == 6 || w == 8 || w == 9) {
                    PGobject param = new PGobject(); param.setType("agtype");
                    param.setValue("{\"key_base\": \"" + targetKey + "\", \"ts\": " + System.currentTimeMillis() + "}");
                    pstmt.setObject(1, param);
                } else if (w == 7) {
                    pstmt.setString(1, "{\"ts\": " + System.currentTimeMillis() + ", \"v\": 99.9, \"is_temp\": true}");
                    PGobject param = new PGobject(); param.setType("agtype");
                    param.setValue("{\"key_base\": \"" + targetKey + "\"}");
                    pstmt.setObject(2, param);
                }
                if (QUERIES_WRITE[w].startsWith("SELECT")) pstmt.executeQuery().close();
                else pstmt.executeUpdate();
            } catch (Exception e) { sucesso = false; }
            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            latencyLogs.add(System.currentTimeMillis() + ",COLD_WRITE," + latencyMs + "," + sucesso);
        }
        System.out.println("-> ROUND 0 CONCLUÍDO em " + (System.currentTimeMillis() - tInicioRound0) + "ms. Planos de execução em cache.");
        ExecutorService executor = Executors.newFixedThreadPool(numClientes);
        CountDownLatch latch = new CountDownLatch(numClientes);
        long startTimeGlobal = System.currentTimeMillis();
        for (int i = 0; i < numClientes; i++) {
            final int streamId = i;
            executor.submit(() -> {
                try {
                    Random deterministico = new Random(42000 + streamId);
                    for (int p = 0; p < pedidosPorCliente; p++) {
                        boolean isRead = deterministico.nextInt(100) < readPercentage;
                        String targetKey = (isRead ? (deterministico.nextBoolean() ? String.valueOf(deterministico.nextInt(2000)) : TARGETS_ANALITICOS[deterministico.nextInt(TARGETS_ANALITICOS.length)]) : String.valueOf(deterministico.nextInt(2000))) + "_sf" + this.sfGlobalAtivo;
                        long startQuery = System.nanoTime();
                        boolean sucesso = true;
                        try {
                            if (isRead) {
                                int qIndex = escolherQueryPorPerfil(probsLeitura, deterministico.nextInt(100));
                                try (PreparedStatement pstmt = db.prepareStatement(prepareQuery(QUERIES_STRESS_TEST_READ[qIndex]))) {
                                    if (qIndex == 1) pstmt.setString(1, targetKey);
                                    else {
                                        PGobject param = new PGobject(); param.setType("agtype");
                                        param.setValue("{\"key_base\": \"" + targetKey + "\"}");
                                        pstmt.setObject(1, param);
                                    }
                                    try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) {} }
                                }
                            } else {
                                int wIndex = escolherQueryPorPerfil(probsEscrita, deterministico.nextInt(100));
                                try (PreparedStatement pstmt = db.prepareStatement(prepareQuery(QUERIES_WRITE[wIndex]))) {
                                    if (wIndex == 0) {
                                        pstmt.setString(1, "\"Tipo A\"");
                                        pstmt.setString(2, "99");
                                        pstmt.setString(3, targetKey);
                                    } else if (wIndex == 1) {
                                        pstmt.setString(1, "{\"ts\": " + System.currentTimeMillis() + ", \"v\": 85.5, \"is_temp\": true}");
                                        pstmt.setString(2, targetKey);
                                    } else if (wIndex == 2) {
                                        PGobject param = new PGobject(); param.setType("agtype");
                                        param.setValue("{\"key_base\": \"" + targetKey + "\", \"novo_material\": \"Titânio\"}");
                                        pstmt.setObject(1, param);
                                    } else if (wIndex == 3) {
                                        pstmt.setString(1, targetKey);
                                        pstmt.setString(2, targetKey);
                                    } else if (wIndex == 4) {
                                        PGobject param = new PGobject(); param.setType("agtype");
                                        param.setValue("{\"from_id\": \"" + targetKey + "\", \"to_id\": \"" + String.valueOf(deterministico.nextInt(2000)) + "_sf" + this.sfGlobalAtivo + "\"}");
                                        pstmt.setObject(1, param);
                                    } else if (wIndex == 5 || wIndex == 6 || wIndex == 8 || wIndex == 9) {
                                        PGobject param = new PGobject(); param.setType("agtype");
                                        param.setValue("{\"key_base\": \"" + targetKey + "\", \"ts\": " + System.currentTimeMillis() + "}");
                                        pstmt.setObject(1, param);
                                    } else if (wIndex == 7) {
                                        pstmt.setString(1, "{\"ts\": " + System.currentTimeMillis() + ", \"v\": 99.9, \"is_temp\": true}");
                                        PGobject param = new PGobject(); param.setType("agtype");
                                        param.setValue("{\"key_base\": \"" + targetKey + "\"}");
                                        pstmt.setObject(2, param);
                                    }
                                    if (QUERIES_WRITE[wIndex].startsWith("SELECT")) pstmt.executeQuery().close();
                                    else pstmt.executeUpdate();
                                }
                            }
                        } catch (Exception e) { sucesso = false; falhasContencao.add("Timeout/Lock"); }
                        long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
                        latencies.add(latencyMs);
                        latencyLogs.add(System.currentTimeMillis() + "," + (isRead ? "READ" : "WRITE") + "," + latencyMs + "," + sucesso);
                    }
                } finally { latch.countDown(); }
            });
        }
        try { latch.await(); } catch (InterruptedException e) {}
        long tempoTotalMs = System.currentTimeMillis() - startTimeGlobal;
        observador.stopEngine();
        processarEstatisticas(sessionTimestamp, perfilNome, numClientes, totalPedidos, tempoTotalMs);
    }

    private int extractSumFromPlan(String jsonPlan, String key) {
        int sum = 0;
        Pattern p = Pattern.compile("\"" + key + "\":\\s*(\\d+)");
        Matcher m = p.matcher(jsonPlan);
        while (m.find()) {
            sum += Integer.parseInt(m.group(1));
        }
        return sum;
    }

    @Override
    public void runRFI(Connection db, String queryId, int warmRuns) {
        if (partKeysPool.isEmpty()) {
            System.out.println("[ERRO] Pool de parâmetros vazia. O motor precisa do Warm-up primeiro.");
            return;
        }
        int index;
        try { index = Integer.parseInt(queryId) - 1; } catch (NumberFormatException e) { System.out.println("[ERRO] ID de Query inválido."); return; }
        if (index < 0 || index >= QUERIES_RFI.length) { System.out.println("[ERRO] A Query selecionada não existe no array (1 a 10)."); return; }
        String aqlQuery = prepareQuery(QUERIES_RFI[index]);
        final String[] TARGET_KEYS = { "15", "12", "800", "800", "500", "500", "500", "500", "799", "800" };
        String targetKey = TARGET_KEYS[index] + "_sf" + this.sfGlobalAtivo;
        final int[] DEPTHS = {1, 1, 1, 1, 299, 299, 299, 299, 299, 10};
        int depth = DEPTHS[index];
        System.out.println("\n-> A preparar Teste RFI para a Query " + queryId);
        System.out.println("-> Target Node (Key injetada): " + targetKey);
        System.out.println("-> Postgres AGE Query: " + aqlQuery);
        String sessionTimestamp = String.valueOf(System.currentTimeMillis());
        String globalCsv = "./metrics/resultados_observador_" + sessionTimestamp + ".csv";
        TelemetryEngine observador = new PostgresTelemetry(globalCsv, db);
        Thread threadObservador = new Thread(observador);
        threadObservador.start();
        System.out.println("-> A gravar baseline global (Aguardando 2 segundos)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        System.out.println("\n=========================================");
        System.out.println(" INICIANDO COLD RUN (RUN 1)");
        System.out.println("=========================================");
        double ramAntesCold = observador.getSystemRamUsedMB();
        System.out.println("Inicio ram: " + ramAntesCold);
        long tColdStart = System.currentTimeMillis();
        double peakMemoryBytesCold = 0;
        try {
            String explainQuery = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + aqlQuery;
            try (PreparedStatement pstmt = db.prepareStatement(explainQuery)) {
                if (index == 1) pstmt.setString(1, targetKey);
                else {
                    PGobject param = new PGobject(); param.setType("agtype");
                    param.setValue("{\"key_base\": \"" + targetKey + "\"}");
                    pstmt.setObject(1, param);
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String jsonPlan = rs.getString(1);
                        int sharedHit = extractSumFromPlan(jsonPlan, "Shared Hit Blocks");
                        int sharedRead = extractSumFromPlan(jsonPlan, "Shared Read Blocks");
                        peakMemoryBytesCold = (sharedHit + sharedRead) * 8192.0;
                    }
                }
            }
        } catch (Exception e) { System.err.println("Erro no Cold Run: " + e.getMessage()); }
        long tColdEnd = System.currentTimeMillis();
        long coldRunTime = (tColdEnd - tColdStart);
        double queryRamMB = peakMemoryBytesCold / (1024.0 * 1024.0);
        double rfiCold = queryRamMB / depth;
        System.out.println("Cold Run Tempo: " + coldRunTime + " ms | Delta RAM: " + queryRamMB + " MB | RFI: " + rfiCold);
        MetricsExporter.saveRFIRun(sessionTimestamp, "PostgreSQL", graphName, queryId, targetKey, "Cold", String.valueOf(tColdStart), String.valueOf(tColdEnd), coldRunTime, index, queryRamMB, rfiCold);
        System.out.println("\n=========================================");
        System.out.println(" INICIANDO WARM RUNS (RUNS 2 a " + (warmRuns + 1) + ")");
        System.out.println("=========================================");
        for (int i = 1; i <= warmRuns; i++) {
            double ramAntesWarm = observador.getSystemRamUsedMB();
            System.out.println("Inicio ram warm: " + ramAntesWarm);
            long tWarmStart = System.currentTimeMillis();
            double peakMemoryBytesWarm = 0;
            try {
                String explainQuery = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + aqlQuery;
                try (PreparedStatement pstmt = db.prepareStatement(explainQuery)) {
                    if (index == 1) pstmt.setString(1, targetKey);
                    else {
                        PGobject param = new PGobject(); param.setType("agtype");
                        param.setValue("{\"key_base\": \"" + targetKey + "\"}");
                        pstmt.setObject(1, param);
                    }
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            String jsonPlan = rs.getString(1);
                            int sharedHit = extractSumFromPlan(jsonPlan, "Shared Hit Blocks");
                            int sharedRead = extractSumFromPlan(jsonPlan, "Shared Read Blocks");
                            peakMemoryBytesWarm = (sharedHit + sharedRead) * 8192.0;
                        }
                    }
                }
            } catch (Exception e) { System.err.println("Erro no Warm Run " + i + ": " + e.getMessage()); }
            long tWarmEnd = System.currentTimeMillis();
            long warmTime = (tWarmEnd - tWarmStart);
            double warmRamMB = peakMemoryBytesWarm / (1024.0 * 1024.0);
            double rfiWarm = warmRamMB / depth;
            System.out.println("Warm Run " + i + " Tempo: " + warmTime + " ms | Delta RAM: " + warmRamMB + " MB | RFI: " + rfiWarm);
            MetricsExporter.saveRFIRun(sessionTimestamp, "PostgreSQL", graphName, queryId, targetKey, "Warm_" + i, String.valueOf(tWarmStart), String.valueOf(tWarmEnd), warmTime, index, warmRamMB, rfiWarm);
        }
        System.out.println("-> A gravar cooldown global (Aguardando 1 segundo)...");
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        observador.stopEngine();
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        int queryNum = index + 1;
        String rfiCsv = "./metrics/rfi_metrics_QUERY-" + queryNum + "_postgresql_" + sessionTimestamp + ".csv";
        String pngPath = "./metrics/grafico_RFI_Q" + queryNum + "_" + sessionTimestamp;
        System.out.println("-> A gerar gráfico de telemetria...");
        GraphGenerator.createRFIChart(globalCsv, rfiCsv, pngPath);
        System.out.println("-> Teste RFI concluído. Métricas gravadas.");
    }

    @Override
    public void limparDadosTemporarios(Connection db) {
        System.out.println("\n-> A INICIAR LIMPEZA DE DADOS TEMPORÁRIOS (HTAP) no Postgres...");
        String[] cleanupQueries = {
            prepareQuery("SELECT * FROM cypher('deepchaindb', $$ MATCH ()-[e:BoM]->() WHERE e.is_temp = true DELETE e $$, 'null'::agtype) AS (a agtype)"),
            "UPDATE " + qualityTable + " SET value = value - 'is_temp' - 'diamond_hits' WHERE value->>'is_temp' = 'true'",
            prepareQuery("SELECT * FROM cypher('deepchaindb', $$ MATCH (p:Part) WHERE p.is_temp = true REMOVE p.last_inspected, p.is_temp $$, 'null'::agtype) AS (a agtype)"),
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

    private static final String[] QUERIES_STRESS_TEST_READ = {
        "SELECT ag_catalog.agtype_to_text(id) as id, ag_catalog.agtype_to_text(material) as material, q.value->>'cert' AS cert FROM cypher('deepchaindb', $$ MATCH (p:Part {id: $key_base}) RETURN p.id, p.material $$, ?) AS (id agtype, material agtype) JOIN deepchaindb_quality_kv q ON ag_catalog.agtype_to_text(id) = '\"' || q.key || '\"'",
        "SELECT t.id, t.data->>'part_name' as nome, log->>'ts' as tempo, log->>'v' as temp, t.data->>'anomaly' as anomalia FROM deepchaindb_telemetry t, jsonb_array_elements(t.data->'sensor_logs') as log WHERE t.data->>'part_id' = ? ORDER BY (log->>'ts')::numeric ASC",
        "SELECT ag_catalog.agtype_to_text(id) as id FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM]-(v:Part) RETURN v.id $$, ?) AS (id agtype)",
        "SELECT ag_catalog.agtype_to_text(id) as id, ag_catalog.agtype_to_text(material) as material, q.value->>'cert' AS cert FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM]-(v:Part) RETURN v.id, v.material $$, ?) AS (id agtype, material agtype) JOIN deepchaindb_quality_kv q ON ag_catalog.agtype_to_text(id) = '\"' || q.key || '\"' WHERE q.value->>'cert' = 'Tipo B'",
        "SELECT ag_catalog.agtype_to_text(id) as id FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) RETURN v.id $$, ?) AS (id agtype)",
        "SELECT ag_catalog.agtype_to_text(id) as id, ag_catalog.agtype_to_text(material) as material FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) WHERE v.material = 'Titânio' RETURN v.id, v.material $$, ?) AS (id agtype, material agtype)",
        "SELECT AVG((log->>'v')::numeric) as media FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) RETURN v.id $$, ?) AS (id agtype) JOIN deepchaindb_telemetry t ON ag_catalog.agtype_to_text(id) = '\"' || (t.data->>'part_id') || '\"' CROSS JOIN jsonb_array_elements(t.data->'sensor_logs') as log",
        "SELECT ag_catalog.agtype_to_text(id) as id, q.value->>'cert' AS cert, (log->>'v')::numeric as temperatura FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(p:Part) WHERE p.material = 'Titânio' RETURN p.id $$, ?) AS (id agtype) JOIN deepchaindb_quality_kv q ON ag_catalog.agtype_to_text(id) = '\"' || q.key || '\"' JOIN deepchaindb_telemetry t ON ag_catalog.agtype_to_text(id) = '\"' || (t.data->>'part_id') || '\"' CROSS JOIN jsonb_array_elements(t.data->'sensor_logs') as log WHERE q.value->>'cert' = 'Tipo B' AND t.data->>'anomaly' = 'true' AND (log->>'v')::numeric > 92.0",
        "SELECT ag_catalog.agtype_to_text(id) as id FROM cypher('deepchaindb', $$ MATCH (leaf:Part {id: $key_base})-[:BoM*1..299]->(v:Part) RETURN v.id $$, ?) AS (id agtype)",
        "SELECT ag_catalog.agtype_to_text(path) as path, t.data->>'anomaly' as anomalia FROM cypher('deepchaindb', $$ MATCH p=(root:Part {id: $key_base})<-[:BoM*1..10]-(v:Part) RETURN [n IN nodes(p) | n.id], v.id $$, ?) AS (path agtype, id agtype) JOIN deepchaindb_telemetry t ON ag_catalog.agtype_to_text(id) = '\"' || (t.data->>'part_id') || '\"' WHERE t.data->>'anomaly' = 'true'"
    };

    private static final String[] QUERIES_WRITE = {
        "UPDATE deepchaindb_quality_kv SET value = jsonb_set(jsonb_set(jsonb_set(value, '{cert}', ?::jsonb), '{score}', ?::jsonb), '{is_temp}', 'true'::jsonb) WHERE key = ?",
        "UPDATE deepchaindb_telemetry SET data = jsonb_set(data, '{sensor_logs}', (data->'sensor_logs') || ?::jsonb) WHERE data->>'part_id' = ?",
        "SELECT * FROM cypher('deepchaindb', $$ MATCH (p:Part {id: $key_base}) SET p.material = $novo_material, p.is_temp = true $$, ?) AS (a agtype)",
        "WITH t AS (UPDATE deepchaindb_telemetry SET data = jsonb_set(jsonb_set(data, '{anomaly}', 'true'::jsonb), '{is_temp}', 'true'::jsonb) WHERE data->>'part_id' = ? RETURNING 1) UPDATE deepchaindb_quality_kv SET value = jsonb_set(jsonb_set(jsonb_set(value, '{cert}', '\"Revogado\"'::jsonb), '{score}', '0'::jsonb), '{is_temp}', 'true'::jsonb) WHERE key = ?",
        "SELECT * FROM cypher('deepchaindb', $$ MATCH (from_node:Part {id: $from_id}), (to_node:Part {id: $to_id}) CREATE (from_node)-[:BoM {qty: 1, type: 'redundant', is_temp: true}]->(to_node) $$, ?) AS (a agtype)",
        "SELECT * FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})-[e:BoM]->() SET e.qty = e.qty + 1, e.is_temp = true $$, ?) AS (a agtype)",
        "UPDATE deepchaindb_quality_kv SET value = jsonb_set(jsonb_set(value, '{cert}', '\"Em Revisão\"'::jsonb), '{is_temp}', 'true'::jsonb) WHERE '\"' || key || '\"' IN (SELECT ag_catalog.agtype_to_text(id) FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM]-(v:Part) RETURN v.id $$, ?) AS (id agtype))",
        "UPDATE deepchaindb_telemetry SET data = jsonb_set(jsonb_set(jsonb_set(data, '{anomaly}', 'true'::jsonb), '{is_temp}', 'true'::jsonb), '{sensor_logs}', (data->'sensor_logs') || ?::jsonb) WHERE '\"' || (data->>'part_id') || '\"' IN (SELECT ag_catalog.agtype_to_text(id) FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..299]-(v:Part) WHERE v.material = 'Titânio' RETURN v.id $$, ?) AS (id agtype))",
        "SELECT * FROM cypher('deepchaindb', $$ MATCH (leaf:Part {id: $key_base})-[:BoM*1..299]->(v:Part) SET v.last_inspected = $ts, v.is_temp = true $$, ?) AS (a agtype)",
        "UPDATE deepchaindb_quality_kv SET value = jsonb_set(jsonb_set(value, '{diamond_hits}', (COALESCE((value->>'diamond_hits')::int, 0) + 1)::text::jsonb), '{is_temp}', 'true'::jsonb) WHERE '\"' || key || '\"' IN (SELECT ag_catalog.agtype_to_text(id) FROM cypher('deepchaindb', $$ MATCH (root:Part {id: $key_base})<-[:BoM*1..10]-(v:Part) RETURN v.id $$, ?) AS (id agtype))"
    };

    private static final String[] QUERIES_RFI = {
        QUERIES_STRESS_TEST_READ[0], QUERIES_STRESS_TEST_READ[1], QUERIES_STRESS_TEST_READ[2],
        QUERIES_STRESS_TEST_READ[3], QUERIES_STRESS_TEST_READ[4], QUERIES_STRESS_TEST_READ[5],
        QUERIES_STRESS_TEST_READ[6], QUERIES_STRESS_TEST_READ[7], QUERIES_STRESS_TEST_READ[8],
        QUERIES_STRESS_TEST_READ[9]
    };
}
