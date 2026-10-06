package deepchainbench.core;

import java.util.*;
import deepchainbench.core.MetricsExporter;

public abstract class AbstractBenchmarkEngine<T> {

    // 1. VARIÁVEIS PARTILHADAS
    // Usamos 'protected' para que as classes que herdem esta tenham acesso direto a elas.
    protected List<String> partKeysPool = new ArrayList<>();
    protected final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> falhasContencao = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> latencyLogs = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> executionLogs = Collections.synchronizedList(new ArrayList<>());
    
    protected int sfGlobalAtivo = 1;
    protected static final String[] TARGETS_ANALITICOS = {"500", "1300", "800", "799", "1599", "0"};

    // 2. MÉTODOS ABSTRATOS (Obriga as classes filhas a implementar)
    public abstract void warmUpAndHarvest(T db);
    public abstract void runWorkload(T db, String perfilNome, int[] probsLeitura, int[] probsEscrita, int readPercentage, int numClientes);
    public abstract void limparDadosTemporarios(T db);
    public abstract void runRFI(T db, String queryId, int warmRuns);

    // 3. MÉTODOS REUTILIZÁVEIS (Lógica comum que já não precisa de ser repetida nas outras classes)
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

    protected void processarEstatisticas(String sessionTimestamp, String perfil, int clientes, int totalPedidos, long tempoTotalMs) {
        if (latencies.isEmpty()) {
            System.out.println("Nenhum pedido foi processado com sucesso.");
            return;
        }

        List<Long> ordenadas = new ArrayList<>(latencies);
        Collections.sort(ordenadas);

        int size = ordenadas.size();
        long p50 = ordenadas.get((int) (size * 0.50));
        long p95 = ordenadas.get((int) (size * 0.95));
        long p99 = ordenadas.get((int) (size * 0.99));

        double throughput = (size / (tempoTotalMs / 1000.0));
        double tempoTotalSegundos = tempoTotalMs / 1000.0;
        int falhas = falhasContencao.size();

        System.out.println("\n================ METRICAS FINAIS (" + perfil + ") ================");
        System.out.printf("Throughput Global : %.2f ops/sec\n", throughput);
        System.out.println("Latência Média P50: " + p50 + " ms");
        System.out.println("Latência Cauda P95: " + p95 + " ms");
        System.out.println("Latência Crítica P99: " + p99 + " ms  <-- Tail Latency");
        System.out.println("Tempo Total de Carga: " + tempoTotalSegundos + " segundos");
        System.out.println("Falhas de Concorrência (Timeouts): " + falhas + " de " + totalPedidos);
        System.out.println("===============================================================");
        
        MetricsExporter.saveHTAPSummary(sessionTimestamp, perfil, clientes, totalPedidos, throughput, p50, p95, p99, tempoTotalSegundos, falhas);
    }
}