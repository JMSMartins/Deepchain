package multibench;

import java.io.File;
import java.util.Scanner;
import multibench.core.BenchmarkEngine;
import multibench.core.DatabaseDriver;
import multibench.drivers.ArangoDriver;
import multibench.generator.Multibench;
import multibench.core.MetricsExporter;

// Novos imports para manipular a data e hora em GMT-0 (UTC)
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\n=========================================");
            System.out.println("       DEEPCHAIN BENCHMARK SYSTEM        ");
            System.out.println("=========================================");
            System.out.println("1. Criar novo Dataset (Scale Factor)");
            System.out.println("2. Ingestão de Dados (Configurar Esquema e Carregar)");
            System.out.println("3. Executar Testes (Benchmark de Queries)");
            System.out.println("0. Sair");
            System.out.print("Escolha uma opção: ");

            String opcao = scanner.nextLine().trim();

            switch (opcao) {
                case "1":
                    menuCriarDataset(scanner);
                    break;
                case "2":
                    menuIngestaoDados(scanner);
                    break;
                case "3":
                    menuExecutarBenchmark(scanner);
                    break;
                case "0":
                    System.out.println("A sair do sistema. Até à próxima!");
                    return;
                default:
                    System.out.println("Opção inválida! Tente novamente.");
            }
        }
    }

    // --- OPÇÃO 1: CRIAR DATASET ---
    private static void menuCriarDataset(Scanner scanner) {
        System.out.println("\n--- CRIAÇÃO DE DATASET ---");
        System.out.print("Introduza o número do Scale Factor (ex: 1, 3, 5): ");
        String sfInput = scanner.nextLine().trim();

        System.out.print("Introduza o nome descritivo para este dataset (ex: testeLinear): ");
        String nomeDescritivo = scanner.nextLine().trim().replace(" ", "-");

        String nomePastaFinal = "sf" + sfInput + "_" + nomeDescritivo;
        System.out.println("\n-> A criar o dataset na pasta: ./dataset/" + nomePastaFinal);

        if (sfInput.equals("1")) {
            Multibench.execute(new String[]{"gen", nomePastaFinal});
        } else {
            Multibench.execute(new String[]{"scale", nomePastaFinal});
        }
    }

    // --- OPÇÃO 2: INGESTÃO DE DADOS (Aqui entram as BDs) ---
    private static void menuIngestaoDados(Scanner scanner) {
        System.out.println("\n--- INGESTÃO DE DADOS ---");

        // 1. Escolha da Base de Dados
        System.out.println("Selecione a Base de Dados de Destino:");
        System.out.println("1. ArangoDB");
        System.out.println("2. OrientDB (Não implementado)");
        System.out.println("3. PostgreSQL (Não implementado)");
        System.out.print("Escolha uma opção: ");
        String targetDb = scanner.nextLine().trim();

        DatabaseDriver driver = null;

        try {
            if (targetDb.equals("1")) {
                driver = new ArangoDriver();
                // Usa o teu IP real configurado
                //driver.connect("192.168.0.103", 8529, "root", "password");
                driver.connect("127.0.0.1", 8529, "root", "password");
            } else if (targetDb.equals("2")) {
                System.out.println("O módulo OrientDB ainda não foi implementado.");
                return;
            } else if (targetDb.equals("3")) {
                System.out.println("O módulo PostgreSQL ainda não foi implementado.");
                return;
            } else {
                System.out.println("Opção inválida.");
                return;
            }

            // 2. Escolha de qual Dataset local queremos injetar
            System.out.println("\nDatasets disponíveis na pasta './dataset/':");
            File folder = new File("./dataset/");
            File[] listOfFiles = folder.listFiles(File::isDirectory);

            if (listOfFiles == null || listOfFiles.length == 0) {
                System.out.println("-> Nenhum dataset encontrado! Crie um primeiro na opção 1.");
                return;
            }

            // Listar as pastas para o utilizador ver
            for (int i = 0; i < listOfFiles.length; i++) {
                System.out.println("  " + (i + 1) + ". " + listOfFiles[i].getName());
            }

            System.out.print("Digite o número do dataset que pretende injetar: ");
            int datasetChoice = Integer.parseInt(scanner.nextLine().trim()) - 1;

            if (datasetChoice < 0 || datasetChoice >= listOfFiles.length) {
                System.out.println("Seleção inválida.");
                return;
            }

            String datasetSelecionado = listOfFiles[datasetChoice].getName();
            String pathCompleto = "./dataset/" + datasetSelecionado + "/";

            // 3. Pedir o nome do Schema / Base de Dados que o utilizador quer criar
            System.out.print("Introduza o nome para o novo esquema/base de dados a criar (ex: teste_arango_1): ");
            String nomeSchema = scanner.nextLine().trim().replace(" ", "_");

            System.out.println("\n-> A iniciar processo para o esquema '" + nomeSchema + "' usando os dados de '" + datasetSelecionado + "'...");

            // Configurar Formatador de Data/Hora
            DateTimeFormatter formatadorData = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            // Registar Data/Hora de Início em GMT-0
            ZonedDateTime horaInicio = ZonedDateTime.now(ZoneId.of("UTC"));
            String startTimestamp = horaInicio.format(formatadorData);

            // 1. INICIAR CRONÓMETRO E INGESTÃO
            long startTime = System.currentTimeMillis();
            driver.setupSchema(nomeSchema); // Agora passa o nome dinâmico
            driver.ingestParts(pathCompleto + "parts.csv");
            driver.ingestEdges(pathCompleto + "bom_edges.csv");
            driver.ingestQualityKV(pathCompleto + "quality_kv.csv");
            driver.ingestTelemetry(pathCompleto + "telemetry.json");

            // 2. PARAR CRONÓMETRO E REGISTAR FIM EM GMT-0
            long durationMs = System.currentTimeMillis() - startTime;
            ZonedDateTime horaFim = ZonedDateTime.now(ZoneId.of("UTC"));
            String endTimestamp = horaFim.format(formatadorData);

            // 3. BUSCAR TAMANHO DO ESQUEMA EM DISCO
            long dbSizeBytes = driver.getSchemaSizeInBytes();

            System.out.println("\n-> Ingestão concluída com sucesso!");
            System.out.printf("   Tempo total: %.2f segundos\n", (durationMs / 1000.0));

            // 4. GUARDAR NO EXCEL
            String dbNameStr = targetDb.equals("1") ? "ArangoDB" : "Outra";
            
            // Passar os timestamps de início e fim para a classe MetricsExporter
            MetricsExporter.saveToExcel(
                dbNameStr, 
                datasetSelecionado, 
                nomeSchema, 
                startTimestamp, 
                endTimestamp, 
                durationMs, 
                dbSizeBytes
            );

        } catch (Exception e) {
            System.out.println("[ERRO] Falha durante a ingestão:");
            e.printStackTrace();
        }
    }



 // --- OPÇÃO 3: EXECUTAR BENCHMARK CONCORRENTE ---
    private static void menuExecutarBenchmark(Scanner scanner) {
        System.out.println("\n--- AMBIENTE DE BENCHMARK CONCORRENTE (100 CLIENTES) ---");
        
        // Configuração de ligação rápida para validação do estado ativo
        com.arangodb.ArangoDB TestArango = new com.arangodb.ArangoDB.Builder()
                .host("127.0.0.1", 8529).user("root").password("password").maxConnections(150).build();
        
        try {
            // 1. LISTAR BASES DE DADOS DISPONÍVEIS
            System.out.println("A procurar bases de dados no servidor...");
            java.util.Collection<String> databases = TestArango.getDatabases();
            java.util.List<String> userDbs = new java.util.ArrayList<>();
            
            int counter = 1;
            for (String dbName : databases) {
                if (!dbName.equals("_system")) { // Esconde a BD nativa do sistema
                    System.out.println("  " + counter + ". " + dbName);
                    userDbs.add(dbName);
                    counter++;
                }
            }

            if (userDbs.isEmpty()) {
                System.out.println("[ERRO] Nenhuma base de dados encontrada! Faça a ingestão primeiro.");
                return;
            }

            System.out.print("\nEscolha o número da base de dados a testar: ");
            int dbChoice = -1;
            try {
                dbChoice = Integer.parseInt(scanner.nextLine().trim()) - 1;
            } catch (NumberFormatException e) {
                System.out.println("Entrada inválida. Operação cancelada.");
                return;
            }

            if (dbChoice < 0 || dbChoice >= userDbs.size()) {
                System.out.println("Opção inválida. Operação cancelada.");
                return;
            }

            String dbAlvo = userDbs.get(dbChoice);
            System.out.println("-> Selecionada: " + dbAlvo);

            com.arangodb.ArangoDatabase dbConnection = TestArango.db(dbAlvo);
            BenchmarkEngine engine = new BenchmarkEngine();
            
            // Fase 1: Harvesting de parâmetros em memória
            engine.warmUpAndHarvest(dbConnection);

            System.out.println("\nSelecione o perfil de distribuição de carga:");
            System.out.println("1. Perfil Linear / Simples Predominante (95% Operações Simples [Q1-Q4], 5% Recursivas [Q5-Q10])");
            System.out.println("2. Perfil Equilibrado (50% Operações Simples, 50% Carga Recursiva Complexa)");
            System.out.println("3. Perfil de Stress Extremo (5% Operações Simples, 95% Carga Recursiva Profunda/Analytics)");
            System.out.print("Escolha uma opção (1-3): ");
            String perfilOpcao = scanner.nextLine().trim();

           String perfilNome;
            // Vetores de probabilidade correspondentes às 10 queries (a soma de cada vetor dá 100%)
            // JUSTIFICAÇÃO METODOLÓGICA GERAL:
            // Q1 a Q4: Carga Leve/Simples (Primitivas O(1) e travessias 1-hop).
            // Q5 a Q10: Carga Pesada/Recursiva (Profundidade N-1, agregações e cruzamentos multi-modelo).
            int[] distribuicaoProbabilidades;

            if (perfilOpcao.equals("1")) {
                perfilNome = "MIX_95_LEITURA_SIMPLES_5_RECURSIVO";
                /*
                 * PERFIL 1 (95-5): Simula o "dia a dia" normal da fábrica para estabelecer a Baseline Latency.
                 * - Q1, Q2, Q3 (25% cada): Operações mais frequentes de leitura direta (ex: bipar peça, ver histórico).
                 * - Q4 (20%): Leva menos 5% porque exige filtro com cruzamento semântico (Graph+KV), logo é ligeiramente menos frequente.
                 * - Q5 a Q9 (1% cada): "Ruído de Fundo". Impede o motor ArangoDB de viciar a cache de memória só com caminhos curtos, testando a arquitetura HTAP.
                 * - Q10 (0%): Excluída cirurgicamente. Sendo uma agregação global complexa, causaria "Thread Starvation" 
                 * (bloqueio da CPU) e arruinaria a medição da latência das operações simples.
                 */
                distribuicaoProbabilidades = new int[]{25, 25, 25, 20, 1, 1, 1, 1, 1, 0}; 
            } 
            else if (perfilOpcao.equals("2")) {
                perfilNome = "MIX_50_50_EQUILIBRADO";
                /*
                 * PERFIL 2 (50-50): Cenário misto HTAP (Transacional e Analítico em igualdade de concorrência).
                 * - Metade do tráfego (15+15+10+10) garante uma pressão contínua de operações transacionais.
                 * - A outra metade (10+10+10+10+5+5) força o otimizador a gerir travessias pesadas ao mesmo tempo.
                 * - A Q10 entra aqui (5%) para permitir a medição da degradação de performance do sistema global 
                 * quando há queries de topo em execução.
                 */
                distribuicaoProbabilidades = new int[]{15, 15, 10, 10, 10, 10, 10, 10, 5, 5};
            } 
            else if (perfilOpcao.equals("3")) {
                perfilNome = "MIX_5_SIMPLES_95_STRESS_RECURSIVO";
                /*
                 * PERFIL 3 (5-95): Teste de Stress Extremo (Foco na Tail Latency P99 e Resource Footprint Index - RFI).
                 * - Q1 a Q4 (2+1+1+1): Reduzidas ao mínimo, servem apenas para forçar o processador a fazer "Context-Switches".
                 * - Q5, Q6, Q7 (20% cada): Foco massivo na explosão recursiva e navegação linear profunda.
                 * - Q9 e Q10 (10% cada): Representam o teto máximo computacional. Saturam deliberadamente a memória RAM 
                 * e o Garbage Collector para testar o ponto de colapso (breakdown point) do otimizador nativo.
                 */
                distribuicaoProbabilidades = new int[]{2, 1, 1, 1, 20, 20, 20, 15, 10, 10};
            } 
            else {
                System.out.println("Opção inválida.");
                // O TestArango.shutdown() e return devem estar geridos pelo método que os envolve
                return;
            }

            System.out.print("Introduza o número total de requisições a processar (ex: 5000): ");
            int totalRequests = Integer.parseInt(scanner.nextLine().trim());

            // Execução concorrente real disparada pelas 100 threads em simultâneo
            engine.runWorkload(dbConnection, perfilNome, distribuicaoProbabilidades, totalRequests);

        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao ligar ao ArangoDB ou ao executar o benchmark: " + e.getMessage());
        } finally {
            // Garante que a ligação fecha sempre, mesmo que haja um erro a meio
            TestArango.shutdown();
        }
    }
}