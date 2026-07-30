package deepchainbench.core;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDatabase;
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
        /* Q1 */"LET part = DOCUMENT('Parts', @key) LET qual = DOCUMENT('Quality_KV', @key) RETURN {id: part._key, mat: part.material, score: qual.score}",
        /* Q2 */ "FOR t IN Telemetry FILTER t.part_id == TO_NUMBER(@key) FOR log IN t.sensor_logs SORT log.ts ASC RETURN log",
        /* Q3 */ "FOR c IN 1..1 OUTBOUND CONCAT('Parts/', @key) BoM_Edges RETURN c",
        /* Q4 */ "FOR c IN 1..1 OUTBOUND CONCAT('Parts/', @key) BoM_Edges LET q = DOCUMENT(CONCAT('Quality_KV/', c._key)) FILTER q.score < 50 RETURN c",
        //Originalmente queria por 0 a 100 mas o sistema não aguentou
        /* Q5 */ "FOR c IN 0..50 OUTBOUND CONCAT('Parts/', @key) BoM_Edges RETURN c",
        /* Q6 */ "FOR c IN 0..50 OUTBOUND CONCAT('Parts/', @key) BoM_Edges FILTER c.material == 'Titanium' RETURN c._key",
        /* Q7 */ "FOR c IN 0..50 OUTBOUND CONCAT('Parts/', @key) BoM_Edges FOR t IN Telemetry FILTER t.part_id == TO_NUMBER(c._key) RETURN {p: c._key, avg: AVERAGE(t.sensor_logs[*].v)}",
        /* Q8 */ "FOR c IN 0..50 OUTBOUND CONCAT('Parts/', @key) BoM_Edges LET q = DOCUMENT(CONCAT('Quality_KV/', c._key)) FILTER q.cert != 'Type A' FOR t IN Telemetry FILTER t.part_id == TO_NUMBER(c._key) FILTER t.sensor_logs[*].v ANY > 90.0 RETURN c._key",
        /* Q9 */ "FOR p IN 1..50 INBOUND CONCAT('Parts/', @key) BoM_Edges FILTER LENGTH(FOR parent IN 1..1 INBOUND p BoM_Edges RETURN 1) == 0 RETURN p._key",
        /* Q10 */ "FOR p, e, path IN 0..50 OUTBOUND CONCAT('Parts/', @key) BoM_Edges LET total = SUM(FOR n IN path.vertices FOR t IN Telemetry FILTER t.part_id == TO_NUMBER(n._key) RETURN LENGTH(FOR l IN t.sensor_logs FILTER l.v > 92.0 RETURN 1)) SORT total DESC LIMIT 1 RETURN path.vertices[*]._key"
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
     * Executa o teste concorrente usando Execution Streams Determinísticos PRNG.
     * Garante reprodutibilidade científica e evita "Cache Cheating".
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
                        cursor.close();
                        
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
            if (randomValue < soma) return i;
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
}
