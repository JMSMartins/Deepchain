package deepchainbench.core;

/**
 * Estrutura consolidada com todas as métricas de comparação entre motores de bases de dados
 * para uma execução HTAP do DeepChainBench (latências, throughput/goodput, operações Read/Write,
 * origem das falhas/timeouts, RAM baseline vs pico e delta, CPU e pressões do sistema Linux /proc).
 */
public class HTAPSummaryReport {

    public static class SeriesStats {
        public final int count;
        public final double mean;
        public final double median;
        public final double p95;
        public final double p99;
        public final double min;
        public final double max;
        public final double stdDev;

        public SeriesStats(int count, double mean, double median, double p95, double p99,
                           double min, double max, double stdDev) {
            this.count = count;
            this.mean = mean;
            this.median = median;
            this.p95 = p95;
            this.p99 = p99;
            this.min = min;
            this.max = max;
            this.stdDev = stdDev;
        }

        public static SeriesStats empty() {
            return new SeriesStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // Identificação e Configuração
    public String sessionTimestamp;
    public String dataHoraUtc;
    public String baseDeDados;
    public String esquema;
    public String scaleFactor;
    public String perfil;
    public int clientesConcorrentes;
    public int totalPedidosSolicitados;
    public double tempoTotalSegundos;
    public long intervaloTelemetriaMs;

    // Operações (Global, Leitura, Escrita)
    public int totalExecutadas;
    public int totalConcluidas;
    public int totalFalhadas;
    public double taxaSucessoGlobalPct;

    public int readTotal;
    public int readConcluidas;
    public int readFalhadas;
    public double readTaxaSucessoPct;

    public int writeTotal;
    public int writeConcluidas;
    public int writeFalhadas;
    public double writeTaxaSucessoPct;

    // Throughput
    public double throughputGlobalOpsSec;
    public double goodputEfetivoOpsSec;

    // Origem das Falhas e Timeouts (Código vs Base de Dados)
    public int falhasTimeoutCodigoQuery;
    public int falhasTimeoutCodigoGlobal;
    public int falhasTimeoutBD;
    public int falhasLockDeadlockBD;
    public int falhasErroBD;
    public int falhasOutrasCliente;

    public int getTotalFalhasTimeoutCodigo() {
        return falhasTimeoutCodigoQuery + falhasTimeoutCodigoGlobal;
    }

    public int getTotalFalhasBD() {
        return falhasTimeoutBD + falhasLockDeadlockBD + falhasErroBD;
    }

    // Latências (ms)
    public SeriesStats latenciaGlobal = SeriesStats.empty();
    public SeriesStats latenciaLeitura = SeriesStats.empty();
    public SeriesStats latenciaEscrita = SeriesStats.empty();

    // RAM (MB) - Baseline 2s vs Carga
    public double ramBaseline2sMediaMB;
    public SeriesStats ramCarga = SeriesStats.empty();
    public double ramDeltaMediaMB;
    public double ramDeltaMaxMB;

    // CPU (%) - Baseline 2s vs Carga
    public double cpuBaseline2sMediaPct;
    public SeriesStats cpuCarga = SeriesStats.empty();

    // Medidas Adicionais do Sistema (/proc)
    public SeriesStats cpuIoWaitCarga = SeriesStats.empty();
    public double swapBaseline2sMediaMB;
    public SeriesStats swapCarga = SeriesStats.empty();
    public double swapDeltaMaxMB;
    public SeriesStats dirtyMemCarga = SeriesStats.empty();
    public SeriesStats contextSwitchesCarga = SeriesStats.empty();
    public int procsRunningMax;
    public long majorPageFaultsTotal;
}
