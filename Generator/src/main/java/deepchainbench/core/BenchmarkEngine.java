package deepchainbench.core;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDatabase;
import deepchainbench.generator.GraphGenerator;
import java.util.*;
import java.util.concurrent.*;

public class BenchmarkEngine {

    private List<String> partKeysPool = new ArrayList<>();
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

//    Oposição Direta à Literatura (Quebrar o Small-World): 
//            O estado da arte dos benchmarks (como o UniBench ou o M2Bench) foca-se na variedade horizontal de dados, 
//            mas utiliza queries de grafos extremamente superficiais, raramente ultrapassando os 2 ou 3 hops de distância. 
//            Para provar que essas ferramentas falham em testar a verdadeira resiliência do hardware, era necessário um número que fosse 
//            indiscutivelmente extremo. Os 100 níveis garantiam uma rutura total com o small-world phenomenon.
//    Simulação de Cadeias de Abastecimento Industriais (BoM): Numa linha de produção complexa (como a indústria automóvel ou aeroespacial), uma Bill of Materials pode atingir dezenas de níveis de profundidade estrutural 
//            (Produto Final $\rightarrow$ Motor $\rightarrow$ Sub-bloco $\rightarrow$ Válvula $\rightarrow$ Mola $\rightarrow$ Liga Metálica). 
//    O limite de 100 pretendia mimetizar o pior cenário possível de rastreabilidade de linhagem profunda (deep lineage) num ambiente de produção real.
//    Garantia Matemática de Exaustão (Wrapper Penalty): Para expor fragilidades como o Wrapper Penalty 
//        (o custo de tradução e abstração entre modelos num motor multimodelo) e inflacionar o Resource Footprint Index (RFI), 
//        o teste tinha de sobrecarregar a memória RAM. Cem níveis de profundidade, aliados aos 30% de redundância na árvore (DAG), 
//    criavam uma garantia matemática de que qualquer motor com uma fraca gestão de memória ou dependente de paginação virtual iria colapsar. Era, essencialmente, desenhado para ser o "carrasco" dos otimizadores.
    // Armazenamento das 10 queries AQL estruturadas do artigo
    private static final String[] QUERIES = {
        /* Q1: Acesso Atómico (KV) */
        "LET part = DOCUMENT(CONCAT('Parts/', @key)) LET kv = DOCUMENT(CONCAT('Quality_KV/', @key)) RETURN { peca: part._key, material: part.material, certificacao: kv.cert }",
        /* Q2: Varredura de Telemetria (Documento) */
        "FOR t IN Telemetry FILTER t.part_id == @key FOR log IN t.sensor_logs SORT log.ts ASC RETURN { key: t._key, nome: t.part_name, tempo: log.ts, temp: log.v, anomalia: t.anomaly }",
        /* Q3: Fan-out Stress (1-hop) */
        "FOR v IN 1..1 INBOUND CONCAT('Parts/', @key) BoM_Edges RETURN { raiz: @key, filho: v._key }",
        /* Q4: Filtro de Qualidade (Grafo + KV) */
        "FOR v IN 1..1 INBOUND CONCAT('Parts/', @key) BoM_Edges LET kv = DOCUMENT(CONCAT('Quality_KV/', v._key)) FILTER kv.cert == 'Tipo B' RETURN { raiz: @key, filho: v._key, material: v.material, certificacao: kv.cert }",
        /* Q5: Travessia Linear Extrema (299 Níveis) */
        "FOR v IN 1..299 INBOUND CONCAT('Parts/', @key) BoM_Edges RETURN { key: v._key }",
        /* Q6: Early Pruning (Grafo + Atributo) */
        "FOR v IN 1..299 INBOUND CONCAT('Parts/', @key) BoM_Edges FILTER v.material == 'Titânio' RETURN { peca: v._key, material: v.material }",
        /* Q7: Agregação Recursiva (Grafo + Documento) */
        "LET total_temps = (FOR v IN 1..299 INBOUND CONCAT('Parts/', @key) BoM_Edges FOR t IN Telemetry FILTER t.part_id == v._key FOR log IN t.sensor_logs RETURN log.v) RETURN AVERAGE(total_temps)",
        /* Q8: A Colisão Semântica (Grafo + KV + Documento) */
        "FOR p IN 1..299 INBOUND CONCAT('Parts/', @key) BoM_Edges FILTER p.material == 'Titânio' LET kv = DOCUMENT(CONCAT('Quality_KV/', p._key)) FILTER kv.cert == 'Tipo B' FOR t IN Telemetry FILTER t.part_id == p._key FILTER t.anomaly == true FOR log IN t.sensor_logs FILTER log.v > 92.0 RETURN { raiz: @key, peca: p._key, certificacao: kv.cert, temperatura: log.v }",
        /* Q9: Reverse Graph (Bottom-up) */
        "FOR v IN 1..299 OUTBOUND CONCAT('Parts/', @key) BoM_Edges RETURN { key: v._key }",
        /* Q10: Explosão Combinatória / Diamantes de Memória */
        "FOR v, e, p IN 1..10 INBOUND CONCAT('Parts/', @key) BoM_Edges OPTIONS { uniqueVertices: 'none', bfs: false } FOR t IN Telemetry FILTER t.part_id == v._key FILTER t.anomaly == true RETURN { caminho: p.vertices[*]._key, anomalia: t.anomaly }"
    };

    /**
     * Fase de Aquecimento (Warm-up / Parameter Harvesting) Extrai IDs reais
     * criados na ingestão para garantir caminhos de travessia válidos.
     */
    public void warmUpAndHarvest(ArangoDatabase db) {
        System.out.println("   [Benchmark] A iniciar Warm-up e colheita de parâmetros...");
        try {
            String aql = "FOR p IN Parts LIMIT 1000 RETURN p._key";
            ArangoCursor<String> cursor = db.query(aql, String.class);
            partKeysPool = cursor.asListRemaining();

            if (partKeysPool.isEmpty()) {
                throw new IllegalStateException("A coleção 'Parts' está vazia. Execute a ingestão primeiro.");
            }
            System.out.println("   [Benchmark] Pool de parâmetros carregada com " + partKeysPool.size() + " IDs reais.");
        } catch (Exception e) {
            System.err.println("   [ERRO] Falha no warm-up: " + e.getMessage());
        }
    }

    /**
     * Executa o teste concorrente usando Execution Streams Determinísticos
     * PRNG. Garante reprodutibilidade científica e evita "Cache Cheating".
     */
    public void runWorkload(ArangoDatabase db, String perfilNome, int[] distribuicaoProbabilidades, int totalPedidos) {
        if (partKeysPool.isEmpty()) {
            System.out.println("[ERRO] Pool de parâmetros vazia. Execute o warm-up.");
            return;
        }

        latencies.clear();
        int numClientes = 100; // 100 Execution Streams em simultâneo
        int pedidosPorCliente = totalPedidos / numClientes; // Ex: 5000 / 100 = 50 queries por Stream

        ExecutorService executor = Executors.newFixedThreadPool(numClientes);
        CountDownLatch latch = new CountDownLatch(numClientes); // Agora o árbitro conta as 100 threads

        System.out.println("\n--- EXECUÇÃO DO BENCHMARK: " + perfilNome + " ---");
        System.out.println("-> Clientes Simultâneos (Streams): " + numClientes);
        System.out.println("-> Pedidos por Cliente: " + pedidosPorCliente + " | Total: " + (pedidosPorCliente * numClientes));
        System.out.println("-> A aplicar sementes determinísticas para impedir Cache Cheating...");

        long startTimeGlobal = System.currentTimeMillis();

        // Criar as 100 Threads (Streams)
        for (int i = 0; i < numClientes; i++) {
            final int streamId = i; // O ID único deste cliente

            executor.submit(() -> {
                try {
                    // O SEGREDO CIENTÍFICO: Uma semente fixa baseada no ID do Stream.
                    // O Stream 0 gerará sempre a mesma matriz de caos, hoje ou amanhã.
                    Random deterministico = new Random(42000 + streamId);

                    for (int p = 0; p < pedidosPorCliente; p++) {
                        // 1. Escolhe a Query garantindo as probabilidades do Perfil (ex: 95-5)
                        int rQuery = deterministico.nextInt(100);
                        int querySelecionada = escolherQueryPorPerfil(distribuicaoProbabilidades, rQuery);

                        // 2. Escolhe um ID real de forma caótica (rebenta a cache do ArangoDB)
                        int rKey = deterministico.nextInt(partKeysPool.size());
                        String randomKey = partKeysPool.get(rKey);

                        Map<String, Object> bindVars = new HashMap<>();
                        bindVars.put("key", randomKey);

                        long startQuery = System.nanoTime();

                        // Executa a Query selecionada com o parâmetro injetado
                        ArangoCursor<Map> cursor = db.query(QUERIES[querySelecionada], Map.class, bindVars);
                        try {
                            cursor.close();
                        } catch (Exception e) {
                            System.out.println("Cursor foi fechado pelo engine da bd ");
                        }

                        long endQuery = System.nanoTime();
                        long latencyMs = (endQuery - startQuery) / 1_000_000;

                        latencies.add(latencyMs);
                    }
                } catch (Exception e) {
                    // Falhas sob stress pesado
                    System.err.println("Erro no Stream " + streamId + ": " + e.getMessage());
                } finally {
                    // O cliente terminou todo o seu lote de trabalho
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(); // Aguarda que os 100 clientes terminem
        } catch (InterruptedException e) {
            System.err.println("Benchmark interrompido.");
        }

        long durationTotalMs = System.currentTimeMillis() - startTimeGlobal;
        executor.shutdown();

        processarEstatisticas(perfilNome, (pedidosPorCliente * numClientes), durationTotalMs);
    }

    private int escolherQueryPorPerfil(int[] probs, int randomValue) {
        int soma = 0;
        for (int i = 0; i < probs.length; i++) {
            soma += probs[i];
            if (randomValue < soma) {
                return i;
            }
        }
        return 0;
    }

    private void processarEstatisticas(String perfil, int totalPedidos, long tempoTotalMs) {
        if (latencies.isEmpty()) {
            System.out.println("Nenhum pedido foi processado com sucesso.");
            return;
        }

        // Ordenar a lista partilhada para extrair os percentis com rigor matemático
        List<Long> ordenadas = new ArrayList<>(latencies);
        Collections.sort(ordenadas);

        int size = ordenadas.size();
        long p50 = ordenadas.get((int) (size * 0.50));
        long p95 = ordenadas.get((int) (size * 0.95));
        long p99 = ordenadas.get((int) (size * 0.99)); // Tail Latency fulcral para o artigo

        double throughput = (size / (tempoTotalMs / 1000.0));

        System.out.println("\n================ METRICAS FINAIS (" + perfil + ") ================");
        System.out.printf("Throughput Global : %.2f ops/sec\n", throughput);
        System.out.println("Latência Média P50: " + p50 + " ms");
        System.out.println("Latência Cauda P95: " + p95 + " ms");
        System.out.println("Latência Crítica P99: " + p99 + " ms  <-- Tail Latency");
        System.out.println("Tempo Total de Carga: " + (tempoTotalMs / 1000.0) + " segundos");
        System.out.println("===============================================================");
    }

 /**
     * Executa o Modo RFI (Resource Footprint Index). Isola 1 Thread e mede o
     * Cold Run vs Warm Runs para testar o Memoization do Otimizador.
     */
    public void runRFI(ArangoDatabase db, String queryId, int warmRuns) {
        if (partKeysPool.isEmpty()) {
            System.out.println("[ERRO] Pool de parâmetros vazia. O motor precisa do Warm-up primeiro.");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(queryId) - 1;
        } catch (NumberFormatException e) {
            System.out.println("[ERRO] ID de Query inválido.");
            return;
        }

        if (index < 0 || index >= QUERIES.length) {
            System.out.println("[ERRO] A Query selecionada não existe no array (1 a 10).");
            return;
        }

        String aqlQuery = QUERIES[index];

//        // Selecionar o nó alvo para o RFI de forma inteligente.
//        // Tenta focar no Nó 500 (raiz da Zona 2 - Cadeia Linear), se não encontrar, usa o primeiro da pool.
//          Ideia descontinuada, vai ser usada mas para os testes de stress
//        String targetKey = partKeysPool.get(0);
//        for (String key : partKeysPool) {
//            if (key.startsWith("500_")) { // Procura por ex: 500_sf1
//                targetKey = key;
//                break;
//            }
//        }

        final String[] TARGET_KEYS = {
            "15_sf1",  // Q1: Outlier de Titânio
            "12_sf1",  // Q2: Exceção JSON (i mod 12 = 0)
            "800_sf1", // Q3: Fan-out Stress (Zone 3 - Zipf)
            "800_sf1", // Q4: Fan-out + Qualidade KV (Zone 3)
            "500_sf1", // Q5: The Great Bottleneck (Raiz da Zone 2 - 299 Níveis)
            "500_sf1", // Q6: Early Pruning (Zone 2)
            "500_sf1", // Q7: Agregação Recursiva (Zone 2)
            "500_sf1", // Q8: Colisão Semântica Completa (Zone 2)
            "799_sf1", // Q9: Reverse Graph (Nó folha no fundo da Zone 2)
            "800_sf1"  // Q10: Diamantes de Memória (Zone 3 - Redundância de 30%)
        };
        
        String targetKey = TARGET_KEYS[index];

        // Mapeamento topológico de profundidade das queries do DeepChainBench
        final int[] DEPTHS = {1, 1, 1, 1, 299, 299, 299, 299, 299, 10};
        int depth = DEPTHS[index];

        Map<String, Object> bindVars = new HashMap<>();
        bindVars.put("key", targetKey);

        System.out.println("\n-> A preparar Teste RFI para a Query " + queryId);
        System.out.println("-> Target Node (Key injetada): " + targetKey);
        System.out.println("-> AQL: " + aqlQuery);

        // 1. Criar a assinatura única da sessão
        String sessionTimestamp = String.valueOf(System.currentTimeMillis());

        // 2. Iniciar o Observador em Background
        String globalCsv = "./metrics/resultados_observador_" + sessionTimestamp + ".csv";
        TelemetryEngine observador = new TelemetryEngine(globalCsv);
   
        Thread threadObservador = new Thread(observador);
        threadObservador.start();

        System.out.println("-> A gravar baseline global (Aguardando 2 segundos)...");
        try { Thread.sleep(2000); } catch (InterruptedException e) {}

        System.out.println("\n=========================================");
        System.out.println(" INICIANDO COLD RUN (RUN 1)");
        System.out.println("=========================================");

        // Captura EXATA da RAM antes da query
        long ramAntesCold = TelemetryEngine.getSystemRamUsedMB();
        System.out.println("Inicio ram: "+ramAntesCold);
        long tColdStart = System.currentTimeMillis();
        System.out.println("AQL -> "+aqlQuery+ " \n");
        try {
            ArangoCursor<Map> coldCursor = db.query(aqlQuery, Map.class, bindVars);
            while (coldCursor.hasNext()) {
                coldCursor.next();
            }
        } catch (Exception e) {
            System.err.println("Erro no Cold Run: " + e.getMessage());
        }
        
        long tColdEnd = System.currentTimeMillis();
        // Captura EXATA da RAM depois da query
        long ramDepoisCold = TelemetryEngine.getSystemRamUsedMB();
        System.out.println("Fim ram: "+ramDepoisCold);

        long coldRunTime = (tColdEnd - tColdStart);
        long deltaRamCold = ramDepoisCold - ramAntesCold;
        double rfiCold = (double) deltaRamCold / depth; // Cálculo exato do RFI

        System.out.println("Cold Run Tempo: " + coldRunTime + " ms | Delta RAM: " + deltaRamCold + " MB | RFI: " + rfiCold);

        String coldStartStr = String.valueOf(tColdStart);
        String coldEndStr = String.valueOf(tColdEnd);

        // Atualiza a chamada para enviar o Start, End, Delta RAM e RFI
        MetricsExporter.saveRFIRun(sessionTimestamp, "ArangoDB", db.name(), queryId, targetKey, "Cold", coldStartStr, coldEndStr, coldRunTime, index, deltaRamCold, rfiCold);

        System.out.println("\n=========================================");
        System.out.println(" INICIANDO WARM RUNS (RUNS 2 a " + (warmRuns + 1) + ")");
        System.out.println("=========================================");

        for (int i = 1; i <= warmRuns; i++) {

            // Captura EXATA da RAM antes da query
            long ramAntesWarm = TelemetryEngine.getSystemRamUsedMB();
            System.out.println("Inicio ram warm: "+ramAntesWarm);
            
            long tWarmStart = System.currentTimeMillis();

            try {
                ArangoCursor<Map> warmCursor = db.query(aqlQuery, Map.class, bindVars);
                while (warmCursor.hasNext()) {
                    warmCursor.next();
                }
            } catch (Exception e) {
                System.err.println("Erro no Warm Run " + i + ": " + e.getMessage());
            }

            long tWarmEnd = System.currentTimeMillis();
            // Captura EXATA da RAM depois da query
            long ramDepoisWarm = TelemetryEngine.getSystemRamUsedMB();
            System.out.println("Fim ram warm: "+ramDepoisWarm);
            
            long warmTime = (tWarmEnd - tWarmStart);
            long deltaRamWarm = ramDepoisWarm - ramAntesWarm;
            double rfiWarm = (double) deltaRamWarm / depth; // Cálculo exato do RFI

            System.out.println("Warm Run " + i + " Tempo: " + warmTime + " ms | Delta RAM: " + deltaRamWarm + " MB | RFI: " + rfiWarm);

            String warmStartStr = String.valueOf(tWarmStart);
            String warmEndStr = String.valueOf(tWarmEnd);

            // Atualiza a chamada para enviar o Start, End, Delta RAM e RFI
            MetricsExporter.saveRFIRun(sessionTimestamp, "ArangoDB", db.name(), queryId, targetKey, "Warm_" + i, warmStartStr, warmEndStr, warmTime, index, deltaRamWarm, rfiWarm);
        }

        // Desligar o observador após capturar o rescaldo (warm-down)
        System.out.println("-> A gravar cooldown global (Aguardando 1 segundo)...");
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        observador.stopEngine();
        
        //CRAR O GRAFICO
        
        // Aguarda 100ms extra apenas para garantir que o SO fechou o ficheiro do observador em disco
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // 1. Reconstruir o nome exato do ficheiro gerado pelo MetricsExporter
        // Nota: O teu exporter soma +1 ao index para criar o QueryID (ex: index 4 -> Q5)
        int queryNum = index + 1; 
        String rfiCsv = "./metrics/rfi_metrics_QUERY-" + queryNum + "_" + db.name().toLowerCase() + "_" + sessionTimestamp + ".csv";
        
        // 2. Definir o nome do PNG final
        String pngPath = "./metrics/grafico_RFI_Q" + queryNum + "_" + sessionTimestamp;

        // 3. Chamar a classe geradora do gráfico
        System.out.println("-> A gerar gráfico de telemetria...");
        GraphGenerator.createRFIChart(globalCsv, rfiCsv, pngPath);

        System.out.println("-> Teste RFI concluído. Métricas gravadas no ficheiro RFI seguro.");
    }
}
