package deepchainbench;

import java.io.File;
import java.util.Scanner;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import deepchainbench.core.Arango.BenchmarkEngineArango;
import deepchainbench.core.Postgres.BenchmarkEnginePostgres;
import deepchainbench.core.DatabaseDriver;
import deepchainbench.drivers.ArangoDriver;
import deepchainbench.drivers.PostgresAgeDriver;
import deepchainbench.generator.Generator_data;
import deepchainbench.core.MetricsExporter;

// Novos imports para manipular a data e hora em GMT-0 (UTC)
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (scanner.hasNextLine()) {
            System.out.println("\n=========================================");
            System.out.println("       DEEPCHAIN BENCHMARK SYSTEM        ");
            System.out.println("=========================================");
            System.out.println("1. Criar novo Dataset (Scale Factor)");
            System.out.println("2. Ingestão de Dados (Configurar Esquema e Carregar)");
            System.out.println("3. Executar Testes RFI (Benchmark de Queries)");
            System.out.println("4. Executar Testes Concorrencia (Benchmark de Queries) ");
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
                    menuExecutarBenchmarkRFI(scanner);
                    break;
                case "4":
                    menuExecutarBenchmarkConcorrente(scanner);
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
            Generator_data.execute(new String[]{"gen", nomePastaFinal});
        } else {
            Generator_data.execute(new String[]{"scale", nomePastaFinal});
        }
    }

    // --- OPÇÃO 2: INGESTÃO DE DADOS (Aqui entram as BDs) ---
    private static void menuIngestaoDados(Scanner scanner) {
        System.out.println("\n--- INGESTÃO DE DADOS ---");

        // 1. Escolha da Base de Dados
        System.out.println("Selecione a Base de Dados de Destino:");
        System.out.println("1. ArangoDB");
        System.out.println("2. OrientDB (Não implementado)");
        System.out.println("3. PostgreSQL ");
        System.err.println("4. Voltar atrás ");
        System.out.print("Escolha uma opção: ");
        String targetDb = scanner.nextLine().trim();

        DatabaseDriver driver = null;
        String dbNameStr = "";

        try {
            if (targetDb.equals("1")) {
                driver = new ArangoDriver();
                // Usa o teu IP real configurado
                //driver.connect("192.168.0.103", 8529, "root", "password");
                driver.connect("127.0.0.1", 8529, "root", "password");
                dbNameStr = "ArangoDB";
            } else if (targetDb.equals("2")) {
                System.out.println("O módulo OrientDB ainda não foi implementado.");
                return;
            } else if (targetDb.equals("3")) {
                // Instancia o teu PostgresAgeDriver (implementa DatabaseDriver)
                driver = new deepchainbench.drivers.PostgresAgeDriver();
                
                driver.connect("127.0.0.1", 5432, "postgres", "password");
                dbNameStr = "PostgreSQL_AGE";
                
            } else if (targetDb.equals("4")) {
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

    // --- OPÇÂO 3: Executar Benchmark RFI
    // --- OPÇÃO 3: EXECUTAR BENCHMARK RFI (DIAGNÓSTICO ESTRUTURAL) ---
    private static void menuExecutarBenchmarkRFI(Scanner scanner) {
        System.out.println("\n--- MODO RFI: DIAGNÓSTICO ESTRUTURAL (1 THREAD) ---");

        System.out.println("Selecione a Base de Dados de Destino:");
        System.out.println("1. ArangoDB");
        System.out.println("2. PostgreSQL + AGE");
        System.err.println("3. Voltar atrás ");
        System.out.print("Escolha uma opção: ");
        String targetDb = scanner.nextLine().trim();

        if (targetDb.equals("3")) {
            return;
        }

        com.arangodb.ArangoDB ArangoRFI = null;
        PostgresAgeDriver pgDriver = null;

        try {
            java.util.List<String> userDbs = new java.util.ArrayList<>();

            if (targetDb.equals("1")) {
                ArangoRFI = new com.arangodb.ArangoDB.Builder()
                        .host("127.0.0.1", 8529).user("root").password("password").build();

                System.out.println("A procurar esquemas (bases de dados) no servidor...");
                java.util.Collection<String> databases = ArangoRFI.getDatabases();

                int counter = 1;
                for (String dbName : databases) {
                    if (!dbName.equals("_system")) {
                        System.out.println("  " + counter + ". " + dbName);
                        userDbs.add(dbName);
                        counter++;
                    }
                }
            } else if (targetDb.equals("2")) {
                pgDriver = new PostgresAgeDriver();
                pgDriver.connect("127.0.0.1", 5432, "postgres", "password");
                Connection conn = pgDriver.getConnection();

                System.out.println("A procurar esquemas (bases de dados) no servidor...");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT name FROM ag_catalog.ag_graph")) {
                    int counter = 1;
                    while (rs.next()) {
                        String dbName = rs.getString(1);
                        System.out.println("  " + counter + ". " + dbName);
                        userDbs.add(dbName);
                        counter++;
                    }
                }
            } else {
                System.out.println("Opção inválida. Operação cancelada.");
                return;
            }

            if (userDbs.isEmpty()) {
                System.out.println("[ERRO] Nenhuma base de dados encontrada! Faça a ingestão primeiro.");
                return;
            }

            // 2. ESCOLHER O ESQUEMA
            System.out.print("\nEscolha o número do esquema a testar: ");
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
            System.out.println("-> Esquema Selecionado: " + dbAlvo);

            // 3. SELECIONAR A QUERY DE STRESS RFI
            System.out.println("\nSelecione a Consulta (Query) para o Teste RFI:");
            System.out.println("5.  Consulta 5 (Travessia Linear Extrema - 299 Níveis)");
            System.out.println("8.  Consulta 8 (Colisão Semântica - Grafo + KV + Documento)");
            System.out.println("10. Consulta 10 (Agregação de Caminhos Redundantes - Diamantes de Memória)");
            System.out.print("Escolha a Query a isolar (ex: 5): ");
            String queryChoice = scanner.nextLine().trim();

            // 4. CONFIGURAR COLD VS WARM RUNS
            System.out.print("Quantas iterações 'Warm' (Quentes) deseja executar após o 'Cold Run'? (ex: 5): ");
            int warmRuns;
            try {
                warmRuns = Integer.parseInt(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                warmRuns = 5; // Default seguro
                System.out.println("Entrada inválida. A assumir 5 Warm Runs por defeito.");
            }

            System.out.println("\n-> A iniciar Teste RFI para a Query " + queryChoice + "...");
            System.out.println("-> 1 Cold Run + " + warmRuns + " Warm Runs. Captura de telemetria ativa.");

            // 5. DELEGAR EXECUÇÃO PARA O MOTOR
            if (targetDb.equals("1")) {
                com.arangodb.ArangoDatabase dbConnection = ArangoRFI.db(dbAlvo);
                BenchmarkEngineArango engine = new BenchmarkEngineArango();
                engine.warmUpAndHarvest(dbConnection);
                engine.runRFI(dbConnection, queryChoice, warmRuns);
            } else if (targetDb.equals("2")) {
                Connection conn = pgDriver.getConnection();
                BenchmarkEnginePostgres engine = new BenchmarkEnginePostgres(dbAlvo);
                engine.warmUpAndHarvest(conn);
                engine.runRFI(conn, queryChoice, warmRuns);
            }

        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao executar o Modo RFI: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (ArangoRFI != null) {
                ArangoRFI.shutdown();
            }
            if (pgDriver != null) {
                try {
                    pgDriver.close();
                } catch (Exception e) {
                    System.out.println("[ERRO] Falha ao fechar conexão com PostgreSQL: " + e.getMessage());
                }
            }
        }
    }

    // --- OPÇÃO 4: EXECUTAR BENCHMARK CONCORRENTE (HTAP) ---
    private static void menuExecutarBenchmarkConcorrente(Scanner scanner) {
        System.out.println("\n--- AMBIENTE DE BENCHMARK CONCORRENTE HTAP ---");

        System.out.println("Selecione a Base de Dados de Destino:");
        System.out.println("1. ArangoDB");
        System.out.println("2. PostgreSQL + AGE");
        System.err.println("3. Voltar atrás ");
        System.out.print("Escolha uma opção: ");
        String targetDb = scanner.nextLine().trim();

        if (targetDb.equals("3")) {
            return;
        }

        com.arangodb.ArangoDB TestArango = null;
        PostgresAgeDriver pgDriver = null;

        try {
            java.util.List<String> userDbs = new java.util.ArrayList<>();

            if (targetDb.equals("1")) {
                TestArango = new com.arangodb.ArangoDB.Builder()
                        .host("127.0.0.1", 8529).user("root").password("password").maxConnections(150).build();

                System.out.println("A procurar bases de dados no servidor...");
                java.util.Collection<String> databases = TestArango.getDatabases();

                int counter = 1;
                for (String dbName : databases) {
                    if (!dbName.equals("_system")) {
                        System.out.println("  " + counter + ". " + dbName);
                        userDbs.add(dbName);
                        counter++;
                    }
                }
            } else if (targetDb.equals("2")) {
                pgDriver = new PostgresAgeDriver();
                pgDriver.connect("127.0.0.1", 5432, "postgres", "password");
                Connection conn = pgDriver.getConnection();

                System.out.println("A procurar bases de dados no servidor...");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT name FROM ag_catalog.ag_graph")) {
                    int counter = 1;
                    while (rs.next()) {
                        String dbName = rs.getString(1);
                        System.out.println("  " + counter + ". " + dbName);
                        userDbs.add(dbName);
                        counter++;
                    }
                }
            } else {
                System.out.println("Opção inválida. Operação cancelada.");
                return;
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

            com.arangodb.ArangoDatabase arangoDbConnection = null;
            Connection postgresConn = null;
            BenchmarkEngineArango arangoEngine = null;
            BenchmarkEnginePostgres postgresEngine = null;

            if (targetDb.equals("1")) {
                arangoDbConnection = TestArango.db(dbAlvo);
                arangoEngine = new BenchmarkEngineArango();
                // Fase 1: Harvesting de parâmetros em memória
                arangoEngine.warmUpAndHarvest(arangoDbConnection);
            } else if (targetDb.equals("2")) {
                postgresConn = pgDriver.getConnection();
                postgresEngine = new BenchmarkEnginePostgres(dbAlvo);
                postgresEngine.warmUpAndHarvest(postgresConn);
            }

            System.out.println("\nSelecione o perfil de distribuição de carga (HTAP):");
            System.out.println("1. Read-Heavy (95% Leituras Simples | 5% Escritas Atómicas) - Baseline");
            System.out.println("2. Balanced (50% Leituras Mistas | 50% Escritas Mistas) - Lock Contention");
            System.out.println("3. Analytical Stress (5% Leituras | 95% Escritas Topológicas e Recursivas) - Breakdown");
            System.out.print("Escolha uma opção (1-3): ");
            String perfilOpcao = scanner.nextLine().trim();

            String perfilNome;
            int readPercentage;
            // O array DEVE somar 100. 
            // Distribuição Zipfian s=1.0 para os 5 Níveis de Complexidade
            int[] probsLeitura = {
                22, 22, // Q1, Q2   (Nível 1 - 44%): Acesso Atómico e Telemetria Simples
                11, 11, // Q3, Q4   (Nível 2 - 22%): 1-Hop e Filtro Qualidade
                7, 7, // Q5, Q6   (Nível 3 - 14%): Travessia 299 Níveis e Early Pruning
                6, 5, // Q7, Q8   (Nível 4 - 11%): Agregação Recursiva e Colisão Semântica
                5, 4 // Q9, Q10  (Nível 5 -  9%): Reverse Graph e Explosão Combinatória
            };

            int[] probsEscrita = {
                22, 22, // W1, W2   (Nível 1 - 44%): KV Update e IoT Append
                11, 11, // W3, W4   (Nível 2 - 22%): Mutação Grafo e Colisão Síncrona
                7, 7, // W5, W6   (Nível 3 - 14%): Insert Edge e Batch Update
                6, 5, // W7, W8   (Nível 4 - 11%): 1-hop Cascade e Write Carrasco
                5, 4 // W9, W10  (Nível 5 -  9%): Reverse Update e Memory Diamond Contention
            };

            if (perfilOpcao.equals("1")) {
                perfilNome = "MIX_95_READ_5_WRITE";
                readPercentage = 95;

            } else if (perfilOpcao.equals("2")) {
                perfilNome = "MIX_50_50_BALANCED";
                readPercentage = 50;

            } else if (perfilOpcao.equals("3")) {
                perfilNome = "MIX_5_READ_95_WRITE_STRESS";
                readPercentage = 5;
            } else {
                System.out.println("Opção inválida. Operação cancelada.");
                return;
            }

            // Pedir o número de Clientes
            System.out.print("Introduza o número de clientes concorrentes (ex: 100): ");
            int numClientes;
            try {
                numClientes = Integer.parseInt(scanner.nextLine().trim());
            } catch (Exception e) {
                numClientes = 100;
                System.out.println("Entrada inválida. A assumir 100 clientes por defeito.");
            }

            // Disparar a Worker Pool com todos os parâmetros!
            if (targetDb.equals("1")) {
                arangoEngine.runWorkload(arangoDbConnection, perfilNome, probsLeitura, probsEscrita, readPercentage, numClientes);
                // Limpa automaticamente o lixo HTAP no final do teste
                arangoEngine.limparDadosTemporarios(arangoDbConnection);
            } else if (targetDb.equals("2")) {
                postgresEngine.runWorkload(postgresConn, perfilNome, probsLeitura, probsEscrita, readPercentage, numClientes);
                postgresEngine.limparDadosTemporarios(postgresConn);
            }

        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao executar o benchmark: " + e.getMessage());
        } finally {
            if (TestArango != null) {
                TestArango.shutdown();
            }
            if (pgDriver != null) {
                try {
                    pgDriver.close();
                } catch (Exception e) {
                    System.out.println("[ERRO] Falha ao fechar conexão com PostgreSQL: " + e.getMessage());
                }
            }
        }
    }
}
