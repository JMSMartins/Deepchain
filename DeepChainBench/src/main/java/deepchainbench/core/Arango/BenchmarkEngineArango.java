package deepchainbench.core.Arango;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDatabase;
import deepchainbench.generator.GraphGenerator;
import java.util.*;
import java.util.concurrent.*;
import com.arangodb.model.AqlQueryOptions;
import deepchainbench.core.AbstractBenchmarkEngine;
import deepchainbench.core.MetricsExporter;
import deepchainbench.core.TelemetryEngine;


public class BenchmarkEngineArango extends AbstractBenchmarkEngine<ArangoDatabase>{

    private List<String> partKeysPool = new ArrayList<>();
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> falhasContencao = Collections.synchronizedList(new ArrayList<>());
    private final List<String> latencyLogs = Collections.synchronizedList(new ArrayList<>()); // <-- NOVA LISTA
    private final List<String> executionLogs = Collections.synchronizedList(new ArrayList<>());

    // Variável global para guardar o SF detetado
    private int sfGlobalAtivo = 1;

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
    private static final String[] QUERIES_STRESS_TEST_READ = {
        /*
         * Q1: Acesso Atómico (KV)
         * - @key_base ideal: "15" (ou múltiplos de 15).
         * - Porquê: Valida a injeção determinística de "Titânio" e a pontuação KV (i mod 15 = 0).
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET target = CONCAT(@key_base, '_sf', i) LET part = DOCUMENT(CONCAT('Parts/', target)) LET kv = DOCUMENT(CONCAT('Quality_KV/', target)) RETURN { sf_origem: i, peca: part._key, material: part.material, certificacao: kv.cert }",
        /*
         * Q2: Varredura de Telemetria (Documento)
         * - @key_base ideal: "12" (ou múltiplos de 12).
         * - Porquê: Atinge os componentes forçados a ter a anomalia térmica > 92ºC (i mod 12 = 0).
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET target = CONCAT(@key_base, '_sf', i) FOR t IN Telemetry FILTER t.part_id == target FOR log IN t.sensor_logs SORT log.ts ASC RETURN { sf_origem: i, key: t._key, nome: t.part_name, tempo: log.ts, temp: log.v, anomalia: t.anomaly }",
        /*
         * Q3: Fan-out Stress (1-hop)
         * - @key_base ideal: "800" (Buraco negro Zipf / Zona 3) ou "0" (Árvore Balanceada / Zona 1).
         * - Porquê: Avalia a contenção de memória ao extrair imediatamente dezenas/centenas de filhos versus 4 filhos.
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..1 INBOUND root BoM_Edges RETURN { sf_origem: i, raiz: root, filho: v._key }",
        /*
         * Q4: Filtro de Qualidade (Grafo + KV)
         * - @key_base ideal: "800" ou "0".
         * - Porquê: Força o motor a resolver o 1-hop e filtrar os resultados no dicionário KV (cert == 'Tipo B').
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..1 INBOUND root BoM_Edges LET kv = DOCUMENT(CONCAT('Quality_KV/', v._key)) FILTER kv.cert == 'Tipo B' RETURN { sf_origem: i, raiz: root, filho: v._key, material: v.material, certificacao: kv.cert }",
        /*
         * Q5: Travessia Linear Extrema (299 Níveis)
         * - @key_base ideal: "500" (Raiz da Zona 2) ou "1300" (Raiz da Zona 4).
         * - Porquê: O "carrasco" da Call Stack. Força a descida de 299 níveis em linha reta para esgotar RAM/Paginação.
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..299 INBOUND root BoM_Edges RETURN { sf_origem: i, key: v._key }",
        /*
         * Q6: Early Pruning (Grafo + Atributo)
         * - @key_base ideal: "500" ou "1300".
         * - Porquê: Testa se o otimizador descarta caminhos mortos cedo (predicate pushdown) ao procurar Titânio no poço de 299 níveis.
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..299 INBOUND root BoM_Edges FILTER v.material == 'Titânio' RETURN { sf_origem: i, peca: v._key, material: v.material }",
        /*
         * Q7: Agregação Recursiva (Grafo + Documento)
         * - @key_base ideal: "500" ou "1300".
         * - Porquê: A pior junção multi-modelo. Navega 299 níveis enquanto desserializa arrays JSON para calcular médias.
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) LET total_temps = (FOR v IN 1..299 INBOUND root BoM_Edges FOR t IN Telemetry FILTER t.part_id == v._key FOR log IN t.sensor_logs RETURN log.v) RETURN { sf_origem: i, media: AVERAGE(total_temps) }",
        /*
         * Q8: A Colisão Semântica (Grafo + KV + Documento)
         * - @key_base ideal: "500" ou "1300".
         * - Porquê: Mede a Wrapper Penalty total ao cruzar Titanium (mod 15), Temperatura (mod 12) e KV (mod 8) em profundidade máxima.
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR p IN 1..299 INBOUND root BoM_Edges FILTER p.material == 'Titânio' LET kv = DOCUMENT(CONCAT('Quality_KV/', p._key)) FILTER kv.cert == 'Tipo B' FOR t IN Telemetry FILTER t.part_id == p._key FILTER t.anomaly == true FOR log IN t.sensor_logs FILTER log.v > 92.0 RETURN { sf_origem: i, raiz: root, peca: p._key, certificacao: kv.cert, temperatura: log.v }",
        /*
         * Q9: Reverse Graph (Bottom-up)
         * - @key_base ideal: "799" (Folha extrema da Zona 2) ou "1599" (Folha da Zona 4).
         * - Porquê: Sobe a árvore (OUTBOUND) a partir do fundo para testar a indexação bidirecional das arestas.
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET leaf = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..299 OUTBOUND leaf BoM_Edges RETURN { sf_origem: i, key: v._key }",
        /*
         * Q10: Explosão Combinatória / Diamantes de Memória
         * - @key_base ideal: "800" (Raiz da Zona 3).
         * - Porquê: Força a reavaliação matemática O(2^n) nas redundâncias (k=2 a 5) ao desativar o BFS e o uniqueVertices.
         * - Parâmetros: @sf, @key_base
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v, e, p IN 1..10 INBOUND root BoM_Edges OPTIONS { uniqueVertices: 'none', bfs: false } FOR t IN Telemetry FILTER t.part_id == v._key FILTER t.anomaly == true RETURN { sf_origem: i, caminho: p.vertices[*]._key, anomalia: t.anomaly }"
    };

    private static final String[] QUERIES_WRITE = {
        /*
         * W1: Atualização Atómica de KV (Qualidade)
         * - Nível 1: Modifica a certificação de uma peça. Muito rápido, testa locks de nível de documento.
         * - @key_base ideal: Aleatório (0 a 1999).
         */
        "FOR i IN 1..@sf LET target = CONCAT(@key_base, '_sf', i) UPDATE target WITH { cert: @nova_cert, score: @novo_score, is_temp: true } IN Quality_KV",
        /*
         * W2: Injeção de Telemetria IoT (JSON Array Append)
         * - Nível 1: Adiciona um novo registo térmico simulando um sensor. Força I/O e desserialização JSON.
         * - @key_base ideal: Aleatório (0 a 1999).
         */
        "FOR i IN 1..@sf LET target = CONCAT(@key_base, '_sf', i) FOR t IN Telemetry FILTER t.part_id == target UPDATE t WITH { sensor_logs: PUSH(t.sensor_logs, {ts: @ts, v: @temp, is_temp: true}) } IN Telemetry",
        /*
         * W3: Mutação Semântica (Grafo Atributos)
         * - Nível 1: Altera o material de uma peça. Brutal para testar se o SGBD invalida as caches dos planos de execução (Early Pruning).
         * - @key_base ideal: Aleatório (0 a 1999).
         */
        "FOR i IN 1..@sf LET target = CONCAT(@key_base, '_sf', i) UPDATE target WITH { material: @novo_material, is_temp: true } IN Parts",
        /*
         * W4: Colisão de Escrita Multi-Modelo Síncrona
         * - Nível 2: Uma peça falha! Marca a anomalia na Telemetria (Documento) E revoga o certificado na coleção Quality_KV (Dicionário) na mesma transação.
         * - @key_base ideal: Aleatório.
         */
        "FOR i IN 1..@sf LET target = CONCAT(@key_base, '_sf', i) FOR t IN Telemetry FILTER t.part_id == target UPDATE t WITH { anomaly: true, is_temp: true } IN Telemetry UPDATE target WITH { cert: 'Revogado', score: 0, is_temp: true } IN Quality_KV",
        /*
         * W5: Mutação Topológica (Insert Edge)
         * - Nível 3: Adiciona uma nova aresta de redundância em tempo real. Altera a forma da rede (Zone 3 - Hub Zipf) durante as travessias.
         * - @key_base ideal: "800" (Zona 3). @target_child: "805" (ou similar).
         */
        "FOR i IN 1..@sf LET from_node = CONCAT('Parts/', @key_base, '_sf', i) LET to_node = CONCAT('Parts/', @target_child, '_sf', i) INSERT { _from: from_node, _to: to_node, qty: 1, type: 'redundant', is_temp: true } INTO BoM_Edges",
        /*
         * W6: Atualização de Arestas em Lote (Fan-out Update)
         * - Nível 3: Aumenta a quantidade (qty) de todas as peças ligadas imediatamente ao Super-Hub. Testa write-locks em índices de arestas.
         * - @key_base ideal: "800" (Para bloquear centenas de arestas) ou "0".
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR e IN BoM_Edges FILTER e._from == root UPDATE e WITH { qty: e.qty + 1, is_temp: true } IN BoM_Edges",
        /*
         * W7: Travessia Curta + Atualização (1-hop Cascade)
         * - Nível 4: Desce um nível a partir de uma raiz e altera o certificado de TODOS os filhos para "Em Revisão".
         * - @key_base ideal: "0" ou "800".
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..1 INBOUND root BoM_Edges UPDATE v._key WITH { cert: 'Em Revisão', is_temp: true } IN Quality_KV",
        /*
         * W8: O "Write Carrasco" (Deep Traverse Conditional Update)
         * - Nível 5: Navega os 299 níveis extremos. Onde o material for 'Titânio', injeta uma temperatura fatal. Bloqueia múltiplos documentos em profundidade.
         * - @key_base ideal: "500" ou "1300".
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..299 INBOUND root BoM_Edges FILTER v.material == 'Titânio' FOR t IN Telemetry FILTER t.part_id == v._key UPDATE t WITH { anomaly: true, is_temp: true, sensor_logs: PUSH(t.sensor_logs, {ts: @ts, v: 99.9, is_temp: true}) } IN Telemetry",
        /*
         * W9: Reverse Deep Traverse Update (Bottom-up Mutability)
         * - Nível 5: Sobe a árvore 299 níveis a partir da folha e marca o caminho como inspecionado. Testa concorrência no index reverso.
         * - @key_base ideal: "799" ou "1599".
         */
        "FOR i IN 1..@sf LET leaf = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..299 OUTBOUND leaf BoM_Edges UPDATE v._key WITH { last_inspected: @ts, is_temp: true } IN Parts",
        /*
         * W10: Memory Diamond Contention (A Bomba de Locks)
         * - Nível 5: Navega a rede sem uniqueVertices. Atualiza um contador no KV *sempre* que passar por um nó. Como há sobreposição exponencial (O(2^n)), as threads vão digladiar-se por Write Locks no mesmo documento dezenas de vezes.
         * - @key_base ideal: "800" (Zona 3 - Zipf).
         */
        "FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i) FOR v IN 1..10 INBOUND root BoM_Edges OPTIONS { uniqueVertices: 'none', bfs: false } LET kv = DOCUMENT(CONCAT('Quality_KV/', v._key)) UPDATE v._key WITH { diamond_hits: (kv.diamond_hits || 0) + 1, is_temp: true } IN Quality_KV"
    };

    // Alvos analíticos fixos para garantir que as queries pesadas (Q3-Q10) não falham
    private static final String[] TARGETS_ANALITICOS = {"500", "1300", "800", "799", "1599", "0"};

    /**
     * Fase de Aquecimento (Warm-up / Parameter Harvesting) Extrai IDs reais
     * criados na ingestão para garantir caminhos de travessia válidos.
     */
    @Override
    public void warmUpAndHarvest(ArangoDatabase db) {
        System.out.println("   [Benchmark] A iniciar Warm-up e colheita de parâmetros...");
        try {
            // 1. Descobrir o Scale Factor Dinamicamente
            long totalParts = db.collection("Parts").count().getCount();
            this.sfGlobalAtivo = (int) (totalParts / 2000);

            if (this.sfGlobalAtivo < 1) {
                this.sfGlobalAtivo = 1; // Salvaguarda
            }
            System.out.println("   [Benchmark] Scale Factor detetado no motor: SF" + this.sfGlobalAtivo);

            // 2. Colheita de IDs reais
            String aql = "FOR p IN Parts LIMIT 1000 RETURN p._key";
            ArangoCursor<String> cursor = db.query(aql, String.class);
            partKeysPool = cursor.asListRemaining();

            if (partKeysPool.isEmpty()) {
                throw new IllegalStateException("A coleção 'Parts' está vazia. Execute a ingestão primeiro.");
            }
            System.out.println("   [Benchmark] Pool carregada com " + partKeysPool.size() + " IDs.");
        } catch (Exception e) {
            System.err.println("   [ERRO] Falha no warm-up: " + e.getMessage());
        }
    }

    /**
     * Executa o teste concorrente HTAP. Grava RAM/CPU em background e exporta a
     * Timeline de Latências individual.
     */
    @Override
    public void runWorkload(ArangoDatabase db, String perfilNome, int[] probsLeitura, int[] probsEscrita, int readPercentage, int numClientes) {
        if (partKeysPool.isEmpty()) {
            System.out.println("[ERRO] Pool de parâmetros vazia. Execute o warm-up primeiro.");
            return;
        }

        latencies.clear();
        falhasContencao.clear();
        latencyLogs.clear();
        executionLogs.clear(); // <-- NOVA // Limpar a timeline

        int pedidosPorCliente = 10;
        int totalPedidos = numClientes * pedidosPorCliente;

        // 1. ISOLAMENTO DE PASTA PARA ESTA TASK (RAM, CPU, Latências, Gráficos)
        String sessionTimestamp = String.valueOf(System.currentTimeMillis());
        String folderPath = "./metrics/Task_" + perfilNome + "_" + sessionTimestamp;
        java.io.File directory = new java.io.File(folderPath);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        System.out.println("\n--- EXECUÇÃO DO BENCHMARK: " + perfilNome + " ---");
        System.out.println("-> Clientes Simultâneos: " + numClientes);
        System.out.println("-> Carga: " + pedidosPorCliente + " queries/cliente (Total: " + totalPedidos + " operações)");
        System.out.println("-> Rácio Leitura/Escrita: " + readPercentage + "% / " + (100 - readPercentage) + "%");
        System.out.println("-> Diretório de Métricas: " + folderPath);

        // 2. INICIAR TELEMETRIA DE CPU E RAM (Ficheiro hardware_telemetry.csv)
        String globalCsv = folderPath + "/hardware_telemetry.csv";
        TelemetryEngine observador = new ArangoTelemetry(
                globalCsv, "127.0.0.1", 8529, db.name(), "root", "password"
        );
        Thread threadObservador = new Thread(observador);
        threadObservador.start();

        System.out.println("-> A gravar baseline de hardware (Aguardando 2 segundos)...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
        }
        
        // =========================================================================
        // ROUND 0 (COLD START): Forçar compilação dos planos de execução no SGBD
        // =========================================================================
        System.out.println("-> A EXECUTAR ROUND 0 (COLD START) - Aquecer Caches do Motor...");
        long tInicioRound0 = System.currentTimeMillis();
        Random coldRandom = new Random(99999); // Semente isolada apenas para o Round 0
        AqlQueryOptions coldOptions = new AqlQueryOptions().maxRuntime(60.0);

        // 0.1 - Correr 1 vez cada Query de Leitura
        for (int q = 0; q < QUERIES_STRESS_TEST_READ.length; q++) {
            Map<String, Object> bindVars = new HashMap<>();
            bindVars.put("sf", this.sfGlobalAtivo);
            if (q < 2) {
                bindVars.put("key_base", String.valueOf(coldRandom.nextInt(2000)));
            } else {
                bindVars.put("key_base", TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)]);
            }

            long startQuery = System.nanoTime();
            boolean sucesso = true;
            try {
                ArangoCursor<Map> cursor = db.query(QUERIES_STRESS_TEST_READ[q], Map.class, bindVars, coldOptions);
                while (cursor.hasNext()) { cursor.next(); }
                cursor.close();
            } catch (Exception e) {
                sucesso = false;
            }
            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            long timeNow = System.currentTimeMillis();
            
            // O segredo está aqui: A tag "COLD_READ" em vez de "READ"
            latencyLogs.add(timeNow + ",COLD_READ," + latencyMs + "," + sucesso);
            executionLogs.add("[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + "] [Round 0] CONCLUIU COLD_READ (Q" + (q + 1) + ") em " + latencyMs + " ms.");
        }

        // 0.2 - Correr 1 vez cada Query de Escrita
        for (int w = 0; w < QUERIES_WRITE.length; w++) {
            Map<String, Object> bindVars = new HashMap<>();
            bindVars.put("sf", this.sfGlobalAtivo);
            
            if (w < 4) { bindVars.put("key_base", String.valueOf(coldRandom.nextInt(2000))); }
            else if (w == 8) { bindVars.put("key_base", (coldRandom.nextBoolean() ? "799" : "1599")); }
            else { bindVars.put("key_base", TARGETS_ANALITICOS[coldRandom.nextInt(TARGETS_ANALITICOS.length)]); }

            // Preencher variáveis obrigatórias para não dar erro de sintaxe
            bindVars.put("nova_cert", "Tipo A");
            bindVars.put("novo_score", 99);
            bindVars.put("ts", System.currentTimeMillis());
            bindVars.put("temp", 85.5);
            bindVars.put("novo_material", "Titânio");
            bindVars.put("target_child", String.valueOf(coldRandom.nextInt(2000)));

            long startQuery = System.nanoTime();
            boolean sucesso = true;
            try {
                db.query(QUERIES_WRITE[w], Map.class, bindVars, coldOptions);
            } catch (Exception e) {
                sucesso = false;
            }
            long latencyMs = (System.nanoTime() - startQuery) / 1_000_000;
            long timeNow = System.currentTimeMillis();
            
            // O segredo está aqui: A tag "COLD_WRITE" em vez de "WRITE"
            latencyLogs.add(timeNow + ",COLD_WRITE," + latencyMs + "," + sucesso);
            executionLogs.add("[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + "] [Round 0] CONCLUIU COLD_WRITE (W" + (w + 1) + ") em " + latencyMs + " ms.");
        }
        System.out.println("-> ROUND 0 CONCLUÍDO em " + (System.currentTimeMillis() - tInicioRound0) + "ms. Planos de execução em cache.");
        // =========================================================================
        // FIM DO ROUND 0
        // =========================================================================

        ExecutorService executor = Executors.newFixedThreadPool(numClientes);
        CountDownLatch latch = new CountDownLatch(numClientes);

        long startTimeGlobal = System.currentTimeMillis();
        System.out.println("-> A INICIAR ATAQUE DE CONCORRÊNCIA HTAP...");

        // 3. DISPARAR WORKER POOL
        for (int i = 0; i < numClientes; i++) {
            final int streamId = i;

            executor.submit(() -> {
                try {
                    Random deterministico = new Random(42000 + streamId);

                    for (int p = 0; p < pedidosPorCliente; p++) {

                        // 1. O dado decide se a operação é Leitura ou Escrita
                        boolean isRead = deterministico.nextInt(100) < readPercentage;

                        Map<String, Object> bindVars = new HashMap<>();
                        bindVars.put("sf", this.sfGlobalAtivo);
                        String queryExecutar;
                        String labelQuery; // <-- NOVA VARIÁVEL PARA O LOG DO TERMINAL

                        if (isRead) {
                            int qIndex = escolherQueryPorPerfil(probsLeitura, deterministico.nextInt(100));
                            queryExecutar = QUERIES_STRESS_TEST_READ[qIndex];
                            labelQuery = "READ (Q" + (qIndex + 1) + ")"; // Ex: READ (Q8)

                            if (qIndex < 2) {
                                bindVars.put("key_base", String.valueOf(deterministico.nextInt(2000)));
                            } else {
                                bindVars.put("key_base", TARGETS_ANALITICOS[deterministico.nextInt(TARGETS_ANALITICOS.length)]);
                            }
                        } else {
                            int wIndex = escolherQueryPorPerfil(probsEscrita, deterministico.nextInt(100));
                            queryExecutar = QUERIES_WRITE[wIndex];
                            labelQuery = "WRITE (W" + (wIndex + 1) + ")"; // Ex: WRITE (W5)

                            if (wIndex < 4) {
                                bindVars.put("key_base", String.valueOf(deterministico.nextInt(2000)));
                            } else if (wIndex == 8) {
                                bindVars.put("key_base", (deterministico.nextBoolean() ? "799" : "1599"));
                            } else {
                                bindVars.put("key_base", TARGETS_ANALITICOS[deterministico.nextInt(TARGETS_ANALITICOS.length)]);
                            }

                            switch (wIndex) {
                                case 0:
                                    String[] certs = {"Tipo A", "Tipo B", "Tipo C", "Em Revisão"};
                                    bindVars.put("nova_cert", certs[deterministico.nextInt(certs.length)]);
                                    bindVars.put("novo_score", deterministico.nextInt(100));
                                    break;
                                case 1:
                                    bindVars.put("ts", System.currentTimeMillis());
                                    bindVars.put("temp", 60.0 + (deterministico.nextDouble() * 40.0));
                                    break;
                                case 2:
                                    bindVars.put("novo_material", deterministico.nextBoolean() ? "Aço Especial" : "Fibra de Carbono");
                                    break;
                                case 4:
                                    bindVars.put("target_child", String.valueOf(deterministico.nextInt(2000)));
                                    break;
                                case 7:
                                case 8:
                                    bindVars.put("ts", System.currentTimeMillis());
                                    break;
                            }
                        }

                        // =======================================================
                        // EXECUÇÃO E CAPTURA DE LATÊNCIA COM OBSERVABILIDADE E LOGS
                        // =======================================================
                        String timeFormatStr = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date());
                        String logStart = "[" + timeFormatStr + "] [Stream " + String.format("%03d", streamId) + "] INICIOU " + labelQuery + " no target: " + bindVars.get("key_base");

                        System.out.println("   " + logStart);
                        executionLogs.add(logStart); // Grava na RAM para o ficheiro final

                        long startQuery = System.nanoTime();
                        boolean sucesso = true;
                        String erroDetalhe = "";

                        try {
                            AqlQueryOptions options = new AqlQueryOptions().maxRuntime(60.0);
                            ArangoCursor<Map> cursor = db.query(queryExecutar, Map.class, bindVars, options);
                            while (cursor.hasNext()) {
                                cursor.next();
                            }
                            cursor.close();
                        } catch (com.arangodb.ArangoDBException e) {
                            sucesso = false;
                            if (e.getErrorNum() != null && (e.getErrorNum() == 1200 || e.getErrorNum() == 1500)) {
                                erroDetalhe = "Erro " + e.getErrorNum() + ": " + e.getErrorMessage();
                                falhasContencao.add("Timeout/Lock");
                            } else {
                                erroDetalhe = "Overload (" + e.getErrorNum() + ")";
                                falhasContencao.add("Overload: " + e.getErrorNum());
                            }
                        } catch (Exception e) {
                            sucesso = false;
                            erroDetalhe = "Generic Error";
                            falhasContencao.add("Generic Error");
                        }

                        long endQuery = System.nanoTime();
                        long latencyMs = (endQuery - startQuery) / 1_000_000;

                        timeFormatStr = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date());
                        String logEnd;
                        if (sucesso) {
                            logEnd = "[" + timeFormatStr + "] [Stream " + String.format("%03d", streamId) + "] CONCLUIU " + labelQuery + " em " + latencyMs + " ms.";
                        } else {
                            logEnd = "[" + timeFormatStr + "] [Stream " + String.format("%03d", streamId) + "] FALHOU   " + labelQuery + " (" + erroDetalhe + ") após " + latencyMs + " ms.";
                        }

                        System.out.println("   " + logEnd);
                        executionLogs.add(logEnd); // Grava na RAM para o ficheiro final

                        latencies.add(latencyMs);
                        long timeNow = System.currentTimeMillis();
                        latencyLogs.add(timeNow + "," + (isRead ? "READ" : "WRITE") + "," + latencyMs + "," + sucesso);
                    }
                } catch (Exception e) {
                    System.err.println("Erro crítico no Stream " + streamId + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
        }
        long endTimeGlobal = System.currentTimeMillis();
        long durationTotalMs = endTimeGlobal - startTimeGlobal;
        executor.shutdown();

        // 4. PARAR TELEMETRIA
        System.out.println("-> A gravar cooldown de hardware (Aguardando 1 segundo)...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
        observador.stopEngine();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
        }

        // 5. EXPORTAR FICHEIROS E GERAR GRÁFICOS RAM/CPU
        String janelasCsv = folderPath + "/janela_workload.csv";
        try (java.io.FileWriter fw = new java.io.FileWriter(janelasCsv)) {
            fw.append("Start_Timestamp,End_Timestamp,Engine,Schema,Query_ID,Target_Key,Run_Type,Latency_ms,Delta_RAM_MB,RFI\n");
            fw.append(startTimeGlobal + "," + endTimeGlobal + ",ArangoDB," + db.name() + ",MIX,N/A,Warm_HTAP," + durationTotalMs + ",0,0\n");
        } catch (Exception e) {
        }

        String pngPath = folderPath + "/grafico_HTAP";
        System.out.println("\n-> A processar gráficos do Benchmark HTAP...");
        GraphGenerator.createRFIChart(globalCsv, janelasCsv, pngPath);

        // 6. EXPORTAR A TIMELINE DAS LATÊNCIAS
        String timelineCsv = folderPath + "/latency_timeline.csv";
        try (java.io.FileWriter fw = new java.io.FileWriter(timelineCsv)) {
            fw.append("Timestamp_ms,Tipo_Operacao,Latencia_ms,Sucesso\n");
            for (String log : latencyLogs) {
                fw.append(log + "\n");
            }
        } catch (Exception e) {
        }

        // =======================================================
        // NOVOS PASSOS: GRÁFICOS DE LATÊNCIA E RELATÓRIO DE LOGS
        // =======================================================
        // 7. CHAMAR O GERADOR DE GRÁFICOS DE LATÊNCIA E BARRAS
        GraphGenerator.createHTAPCharts(timelineCsv, pngPath);

        // 8. EXPORTAR O RELATÓRIO TEXTUAL COMPLETO (Logs do Terminal)
        String logTextPath = folderPath + "/execution_log.txt";
        try (java.io.FileWriter fw = new java.io.FileWriter(logTextPath)) {
            fw.append("========== RELATÓRIO DE EXECUÇÃO HTAP: " + perfilNome + " ==========\n");
            fw.append("Tempo Total de Benchmark: " + (durationTotalMs / 1000.0) + " s\n");
            fw.append("Clientes Simultâneos: " + numClientes + "\n");
            fw.append("Total de Operações Submetidas: " + totalPedidos + "\n");
            fw.append("============================================================\n\n");
            for (String logLine : executionLogs) {
                fw.append(logLine + "\n");
            }
            System.out.println("-> Relatório em texto (Logs) exportado para: " + logTextPath);
        } catch (Exception e) {
            System.err.println("[ERRO] Falha ao exportar os logs: " + e.getMessage());
        }

        
        processarEstatisticas(sessionTimestamp, perfilNome, numClientes, totalPedidos, durationTotalMs);

    }

    /**
     * Limpa o lixo inserido no HTAP.
     */
    @Override
    public void limparDadosTemporarios(ArangoDatabase db) {
        System.out.println("\n-> A INICIAR LIMPEZA DE DADOS TEMPORÁRIOS (HTAP)...");

        String[] cleanupQueries = {
            "FOR e IN BoM_Edges FILTER e.is_temp == true REMOVE e IN BoM_Edges",
            "FOR q IN Quality_KV FILTER q.is_temp == true UPDATE q WITH { is_temp: null, diamond_hits: null } IN Quality_KV OPTIONS { keepNull: false }",
            "FOR p IN Parts FILTER p.is_temp == true UPDATE p WITH { last_inspected: null, is_temp: null } IN Parts OPTIONS { keepNull: false }",
            "FOR t IN Telemetry FILTER t.is_temp == true LET clean_logs = (FOR log IN t.sensor_logs FILTER log.is_temp != true RETURN log) UPDATE t WITH { anomaly: false, is_temp: null, sensor_logs: clean_logs } IN Telemetry OPTIONS { keepNull: false }"
        };

        for (int i = 0; i < cleanupQueries.length; i++) {
            try {
                db.query(cleanupQueries[i], Map.class);
            } catch (Exception e) {
                System.err.println("Erro na rotina de limpeza " + (i + 1) + ": " + e.getMessage());
            }
        }
        System.out.println("-> Limpeza concluída! Base de dados restaurada para o estado de avaliação.");
    }
    

// =====================================================================================================
    /**
     * ========================== ****** RFI ********* ==========================================================
     */
    // =====================================================================================================
    private static final String[] QUERIES_RFI = {
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

//TODO: ESTE METODO ESTÁ ACOPOLADO AO ARANGO, DEPOIS VOU TER DE CRIAR ABSTRAÇÃO PARA ESCALAR O CÓDIGO
    /**
     * Executa o Modo RFI (Resource Footprint Index). Isola 1 Thread e mede o
     * Cold Run vs Warm Runs para testar o Memoization do Otimizador.
     */
    @Override
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

        if (index < 0 || index >= QUERIES_RFI.length) {
            System.out.println("[ERRO] A Query selecionada não existe no array (1 a 10).");
            return;
        }

        String aqlQuery = QUERIES_RFI[index];

        final String[] TARGET_KEYS = {
            "15_sf1", // Q1: Outlier de Titânio
            "12_sf1", // Q2: Exceção JSON (i mod 12 = 0)
            "800_sf1", // Q3: Fan-out Stress (Zone 3 - Zipf)
            "800_sf1", // Q4: Fan-out + Qualidade KV (Zone 3)
            "500_sf1", // Q5: The Great Bottleneck (Raiz da Zone 2 - 299 Níveis)
            "500_sf1", // Q6: Early Pruning (Zone 2)
            "500_sf1", // Q7: Agregação Recursiva (Zone 2)
            "500_sf1", // Q8: Colisão Semântica Completa (Zone 2)
            "799_sf1", // Q9: Reverse Graph (Nó folha no fundo da Zone 2)
            "800_sf1" // Q10: Diamantes de Memória (Zone 3 - Redundância de 30%)
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

        // 1. Criar a assinatura única da sessão (já usa Epoch/UTC naturalmente via currentTimeMillis)
        String sessionTimestamp = String.valueOf(System.currentTimeMillis());

        // 2. Iniciar o Observador em Background
        String globalCsv = "./metrics/resultados_observador_" + sessionTimestamp + ".csv";

        // ALTERAÇÃO 1: Instanciação específica para ArangoDB (Ajusta user/pass se necessário)
        TelemetryEngine observador = new ArangoTelemetry(
                globalCsv,
                "127.0.0.1",
                8529,
                db.name(),
                "root",
                "password"
        );

        Thread threadObservador = new Thread(observador);
        threadObservador.start();

        System.out.println("-> A gravar baseline global (Aguardando 2 segundos)...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
        }

        System.out.println("\n=========================================");
        System.out.println(" INICIANDO COLD RUN (RUN 1)");
        System.out.println("=========================================");

        AqlQueryOptions options = new AqlQueryOptions().profile(true);

        // ALTERAÇÃO 2: Captura EXATA da RAM invocando o método da instância (API RocksDB)
        double ramAntesCold = observador.getSystemRamUsedMB();
        System.out.println("Inicio ram: " + ramAntesCold);
        long tColdStart = System.currentTimeMillis();
        System.out.println("AQL -> " + aqlQuery + " \n");

        double peakMemoryBytesCold = 0;
        try {

            ArangoCursor<Map> coldCursor = db.query(aqlQuery, Map.class, bindVars, options);
            while (coldCursor.hasNext()) {
                coldCursor.next();
            }

            // EXTRAÇÃO CIRÚRGICA DA RAM USADA PELA QUERY
            if (coldCursor.getStats() != null) {
                peakMemoryBytesCold = coldCursor.getStats().getPeakMemoryUsage();
            }

        } catch (Exception e) {
            System.err.println("Erro no Cold Run: " + e.getMessage());
        }

        long tColdEnd = System.currentTimeMillis();
        // ALTERAÇÃO 3: RAM pós query via instância
        //double ramDepoisCold = observador.getSystemRamUsedMB();
        //System.out.println("Fim ram: "+ramDepoisCold);

        long coldRunTime = (tColdEnd - tColdStart);

        //Porque em mb
        double queryRamMB = peakMemoryBytesCold / (1024.0 * 1024.0);

        double deltaRamCold = queryRamMB;
        double rfiCold = (double) deltaRamCold / depth;

        System.out.println("Cold Run Tempo: " + coldRunTime + " ms | Delta RAM: " + deltaRamCold + " MB | RFI: " + rfiCold);

        String coldStartStr = String.valueOf(tColdStart);
        String coldEndStr = String.valueOf(tColdEnd);

        MetricsExporter.saveRFIRun(sessionTimestamp, "ArangoDB", db.name(), queryId, targetKey, "Cold", coldStartStr, coldEndStr, coldRunTime, index, deltaRamCold, rfiCold);

        System.out.println("\n=========================================");
        System.out.println(" INICIANDO WARM RUNS (RUNS 2 a " + (warmRuns + 1) + ")");
        System.out.println("=========================================");

        for (int i = 1; i <= warmRuns; i++) {

            // ALTERAÇÃO 4: RAM pré warm-run via instância
            double ramAntesWarm = observador.getSystemRamUsedMB();
            System.out.println("Inicio ram warm: " + ramAntesWarm);

            long tWarmStart = System.currentTimeMillis();
            double peakMemoryBytesWarm = 0;
            try {
                ArangoCursor<Map> warmCursor = db.query(aqlQuery, Map.class, bindVars, options);
                while (warmCursor.hasNext()) {
                    warmCursor.next();
                }
                // EXTRAÇÃO CIRÚRGICA DA RAM USADA PELA QUERY
                if (warmCursor.getStats() != null) {
                    peakMemoryBytesWarm = warmCursor.getStats().getPeakMemoryUsage();
                }
            } catch (Exception e) {
                System.err.println("Erro no Warm Run " + i + ": " + e.getMessage());
            }

            long tWarmEnd = System.currentTimeMillis();
            // ALTERAÇÃO 5: RAM pós warm-run via instância
            double ramDepoisWarm = observador.getSystemRamUsedMB();
            System.out.println("Fim ram warm: " + ramDepoisWarm);

            long warmTime = (tWarmEnd - tWarmStart);
            queryRamMB = peakMemoryBytesWarm / (1024.0 * 1024.0);
            double deltaRamWarm = queryRamMB;
            double rfiWarm = (double) deltaRamWarm / depth;

            System.out.println("Warm Run " + i + " Tempo: " + warmTime + " ms | Delta RAM: " + deltaRamWarm + " MB | RFI: " + rfiWarm);

            String warmStartStr = String.valueOf(tWarmStart);
            String warmEndStr = String.valueOf(tWarmEnd);

            MetricsExporter.saveRFIRun(sessionTimestamp, "ArangoDB", db.name(), queryId, targetKey, "Warm_" + i, warmStartStr, warmEndStr, warmTime, index, deltaRamWarm, rfiWarm);
        }

        System.out.println("-> A gravar cooldown global (Aguardando 1 segundo)...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
        observador.stopEngine();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }

        int queryNum = index + 1;
        String rfiCsv = "./metrics/rfi_metrics_QUERY-" + queryNum + "_arangodb_" + sessionTimestamp + ".csv";

        String pngPath = "./metrics/grafico_RFI_Q" + queryNum + "_" + sessionTimestamp;

        System.out.println("-> A gerar gráfico de telemetria...");
        GraphGenerator.createRFIChart(globalCsv, rfiCsv, pngPath);

        System.out.println("-> Teste RFI concluído. Métricas gravadas no ficheiro RFI seguro.");
    }
}
