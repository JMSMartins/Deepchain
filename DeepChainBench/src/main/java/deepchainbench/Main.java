package deepchainbench;

import deepchainbench.core.DatabaseDriver;
import deepchainbench.core.MetricsExporter;
import deepchainbench.core.Arango.BenchmarkEngineArango;
import deepchainbench.core.Postgres.BenchmarkEnginePostgres;
import deepchainbench.drivers.ArangoDriver;
import deepchainbench.drivers.PostgresAgeDriver;
import deepchainbench.generator.Generator_data;

import java.io.File;
import java.util.Scanner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Bem-vindo ao Gerador de Dados HTAP para Multi-modelo");

        while (true) {
            System.out.println("\nMenu Principal:");
            System.out.println("1. Gerar Arquivos CSV e JSON (Scale Factor)");
            System.out.println("2. Ingerir Dados para o Banco de Dados Multi-modelo");
            System.out.println("3. Benchmark RFI - Resource Footprint Index");
            System.out.println("4. Benchmark HTAP Concorrente");
            System.out.println("0. Sair");
            System.out.print("Escolha uma opção: ");

            String opcao = scanner.nextLine().trim();

            switch (opcao) {
                case "1":
                    menuGerarDados(scanner);
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

    private static void menuGerarDados(Scanner scanner) {
        System.out.println("\n--- GERAÇÃO DE DADOS ---");
        System.out.print("Introduza o Scale Factor (ex: 1 = 2000 peças, 10 = 20000 peças): ");
        int scaleFactor;
        try {
            scaleFactor = Integer.parseInt(scanner.nextLine().trim());
            if (scaleFactor < 1) {
                System.out.println("O Scale Factor deve ser >= 1.");
                return;
            }
        } catch (NumberFormatException e) {
            System.out.println("Erro: Entrada inválida. Deve ser um número inteiro.");
            return;
        }

        System.out.print("Introduza um sufixo para a pasta do dataset (ex: TesteA): ");
        String customFolder = scanner.nextLine().trim();

        System.out.println("\nA iniciar a geração estocástica de grafos e documentos...");

        String nomePastaFinal = "sf" + scaleFactor + "_" + customFolder;
        if (scaleFactor == 1) {
            Generator_data.execute(new String[]{"gen", nomePastaFinal});
        } else {
            Generator_data.execute(new String[]{"scale", String.valueOf(scaleFactor), nomePastaFinal});
        }

        System.out.println("Geração concluída.");
    }

    private static void menuIngestaoDados(Scanner scanner) {
        System.out.println("\n--- INGESTÃO DE DADOS ---");

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
                driver.connect("127.0.0.1", 8529, "root", "password");
                dbNameStr = "ArangoDB";
            } else if (targetDb.equals("2")) {
                System.out.println("O módulo OrientDB ainda não foi implementado.");
                return;
            } else if (targetDb.equals("3")) {
                driver = new PostgresAgeDriver();
                driver.connect("127.0.0.1", 5432, "postgres", "password");
                dbNameStr = "PostgreSQL_AGE";
            } else if (targetDb.equals("4")) {
                return;
            } else {
                System.out.println("Opção inválida.");
                return;
            }

            File dirDatasets = new File("./dataset");
            if (!dirDatasets.exists() || !dirDatasets.isDirectory()) {
                System.out.println("Erro: Pasta ./dataset não encontrada. Rode a opção 1 primeiro.");
                return;
            }

            File[] pastas = dirDatasets.listFiles(File::isDirectory);
            if (pastas == null || pastas.length == 0) {
                System.out.println("Erro: Nenhuma subpasta de dataset encontrada.");
                return;
            }

            System.out.println("\nDatasets disponíveis para ingestão:");
            for (int i = 0; i < pastas.length; i++) {
                System.out.println((i + 1) + ". " + pastas[i].getName());
            }

            System.out.print("Escolha qual dataset pretende ingerir: ");
            int idx = Integer.parseInt(scanner.nextLine().trim()) - 1;

            if (idx < 0 || idx >= pastas.length) {
                System.out.println("Opção inválida.");
                return;
            }

            String datasetSelecionado = pastas[idx].getName();
            String prefixo = "./dataset/" + datasetSelecionado + "/";

            String nomeSchema = "deepchain_" + datasetSelecionado.toLowerCase();
            System.out.println("\nO driver vai criar e usar o esquema/database com o nome: " + nomeSchema);

            driver.setupSchema(nomeSchema);

            System.out.println("\n=> A iniciar a ingestão do motor " + dbNameStr + "...");

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));
            String startTimestamp = formatter.format(Instant.now());
            long startMs = System.currentTimeMillis();

            driver.ingestParts(prefixo + "parts.csv");
            driver.ingestEdges(prefixo + "bom_edges.csv");
            driver.ingestQualityKV(prefixo + "quality_kv.csv");
            driver.ingestTelemetry(prefixo + "telemetry.json");

            long endMs = System.currentTimeMillis();
            String endTimestamp = formatter.format(Instant.now());
            long durationMs = endMs - startMs;

            System.out.println("Ingestão concluída em " + durationMs + " ms.");
            System.out.println("A analisar footprint de hardware...");

            long dbSizeBytes = driver.getSchemaSizeInBytes();

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

    private static void menuExecutarBenchmarkRFI(Scanner scanner) {
        System.out.println("\n--- MODO RFI: DIAGNÓSTICO ESTRUTURAL (1 THREAD) ---");

        System.out.println("Selecione a Base de Dados de Destino:");
        System.out.println("1. ArangoDB");
        System.out.println("2. PostgreSQL + AGE");
        System.out.print("Escolha uma opção: ");
        String targetDb = scanner.nextLine().trim();

        if (targetDb.equals("1")) {
            com.arangodb.ArangoDB ArangoRFI = new com.arangodb.ArangoDB.Builder()
                    .host("127.0.0.1", 8529).user("root").password("password").build();

            try {
                System.out.println("A procurar esquemas (bases de dados) no servidor...");
                java.util.Collection<String> databases = ArangoRFI.getDatabases();
                java.util.List<String> userDbs = new java.util.ArrayList<>();

                int counter = 1;
                for (String dbName : databases) {
                    if (!dbName.equals("_system")) {
                        System.out.println("  " + counter + ". " + dbName);
                        userDbs.add(dbName);
                        counter++;
                    }
                }

                if (userDbs.isEmpty()) {
                    System.out.println("[ERRO] Nenhuma base de dados encontrada! Faça a ingestão primeiro.");
                    return;
                }

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
                com.arangodb.ArangoDatabase dbConnection = ArangoRFI.db(dbAlvo);

                System.out.println("\nSelecione a Consulta (Query) para o Teste RFI:");
                System.out.println("5.  Consulta 5 (Travessia Linear Extrema - 299 Níveis)");
                System.out.println("8.  Consulta 8 (Colisão Semântica - Grafo + KV + Documento)");
                System.out.println("10. Consulta 10 (Agregação de Caminhos Redundantes - Diamantes de Memória)");
                System.out.print("Escolha a Query a isolar (ex: 5): ");
                String queryChoice = scanner.nextLine().trim();

                System.out.print("Quantas iterações 'Warm' (Quentes) deseja executar após o 'Cold Run'? (ex: 5): ");
                int warmRuns;
                try {
                    warmRuns = Integer.parseInt(scanner.nextLine().trim());
                } catch (NumberFormatException e) {
                    warmRuns = 5;
                    System.out.println("Entrada inválida. A assumir 5 Warm Runs por defeito.");
                }

                System.out.println("\n-> A iniciar Teste RFI para a Query " + queryChoice + "...");
                System.out.println("-> 1 Cold Run + " + warmRuns + " Warm Runs. Captura de telemetria ativa.");

                BenchmarkEngineArango engine = new BenchmarkEngineArango();
                engine.warmUpAndHarvest(dbConnection);
                engine.runRFI(dbConnection, queryChoice, warmRuns);

            } catch (Exception e) {
                System.out.println("[ERRO] Falha ao executar o Modo RFI: " + e.getMessage());
                e.printStackTrace();
            } finally {
                ArangoRFI.shutdown();
            }
        } else if (targetDb.equals("2")) {
            Connection conn = null;
            try {
                Class.forName("org.postgresql.Driver");
                conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "password");

                System.out.println("A procurar esquemas (grafos AGE) no servidor...");
                java.util.List<String> userDbs = new java.util.ArrayList<>();
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE EXTENSION IF NOT EXISTS age CASCADE;");
                    stmt.execute("LOAD 'age';");
                    stmt.execute("SET search_path = ag_catalog, \"$user\", public;");
                    ResultSet rs = stmt.executeQuery("SELECT graph_name FROM ag_catalog.ag_graph");
                    int counter = 1;
                    while (rs.next()) {
                        String dbName = rs.getString(1);
                        System.out.println("  " + counter + ". " + dbName);
                        userDbs.add(dbName);
                        counter++;
                    }
                }

                if (userDbs.isEmpty()) {
                    System.out.println("[ERRO] Nenhuma base de dados encontrada! Faça a ingestão primeiro.");
                    return;
                }

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

                System.out.println("\nSelecione a Consulta (Query) para o Teste RFI:");
                System.out.println("5.  Consulta 5 (Travessia Linear Extrema - 299 Níveis)");
                System.out.println("8.  Consulta 8 (Colisão Semântica - Grafo + KV + Documento)");
                System.out.println("10. Consulta 10 (Agregação de Caminhos Redundantes - Diamantes de Memória)");
                System.out.print("Escolha a Query a isolar (ex: 5): ");
                String queryChoice = scanner.nextLine().trim();

                System.out.print("Quantas iterações 'Warm' (Quentes) deseja executar após o 'Cold Run'? (ex: 5): ");
                int warmRuns;
                try {
                    warmRuns = Integer.parseInt(scanner.nextLine().trim());
                } catch (NumberFormatException e) {
                    warmRuns = 5;
                    System.out.println("Entrada inválida. A assumir 5 Warm Runs por defeito.");
                }

                System.out.println("\n-> A iniciar Teste RFI para a Query " + queryChoice + "...");
                System.out.println("-> 1 Cold Run + " + warmRuns + " Warm Runs. Captura de telemetria ativa.");

                BenchmarkEnginePostgres engine = new BenchmarkEnginePostgres(dbAlvo);
                engine.warmUpAndHarvest(conn);
                engine.runRFI(conn, queryChoice, warmRuns);

            } catch (Exception e) {
                System.out.println("[ERRO] Falha ao executar o Modo RFI PostgreSQL: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (conn != null) {
                    try { conn.close(); } catch (Exception ex) {}
                }
            }
        } else {
            System.out.println("Opção inválida.");
        }
    }

    private static void menuExecutarBenchmarkConcorrente(Scanner scanner) {
        System.out.println("\n--- AMBIENTE DE BENCHMARK CONCORRENTE HTAP ---");

        System.out.println("Selecione a Base de Dados de Destino:");
        System.out.println("1. ArangoDB");
        System.out.println("2. PostgreSQL + AGE");
        System.out.print("Escolha uma opção: ");
        String targetDb = scanner.nextLine().trim();

        if (targetDb.equals("1")) {
            com.arangodb.ArangoDB TestArango = new com.arangodb.ArangoDB.Builder()
                    .host("127.0.0.1", 8529).user("root").password("password").maxConnections(150).build();

            try {
                System.out.println("A procurar bases de dados no servidor...");
                java.util.Collection<String> databases = TestArango.getDatabases();
                java.util.List<String> userDbs = new java.util.ArrayList<>();

                int counter = 1;
                for (String dbName : databases) {
                    if (!dbName.equals("_system")) {
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
                BenchmarkEngineArango engine = new BenchmarkEngineArango();

                engine.warmUpAndHarvest(dbConnection);

                System.out.println("\nSelecione o perfil de distribuição de carga (HTAP):");
                System.out.println("1. Read-Heavy (95% Leituras Simples | 5% Escritas Atómicas) - Baseline");
                System.out.println("2. Balanced (50% Leituras Mistas | 50% Escritas Mistas) - Lock Contention");
                System.out.println("3. Analytical Stress (5% Leituras | 95% Escritas Topológicas e Recursivas) - Breakdown");
                System.out.print("Escolha uma opção (1-3): ");
                String perfilOpcao = scanner.nextLine().trim();

                String perfilNome;
                int readPercentage;
                int[] probsLeitura = { 22, 22, 11, 11, 7, 7, 6, 5, 5, 4 };
                int[] probsEscrita = { 22, 22, 11, 11, 7, 7, 6, 5, 5, 4 };

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

                System.out.print("Introduza o número de clientes concorrentes (ex: 100): ");
                int numClientes;
                try {
                    numClientes = Integer.parseInt(scanner.nextLine().trim());
                } catch (Exception e) {
                    numClientes = 100;
                    System.out.println("Entrada inválida. A assumir 100 clientes por defeito.");
                }

                engine.runWorkload(dbConnection, perfilNome, probsLeitura, probsEscrita, readPercentage, numClientes);
                engine.limparDadosTemporarios(dbConnection);

            } catch (Exception e) {
                System.out.println("[ERRO] Falha ao ligar ao ArangoDB ou ao executar o benchmark: " + e.getMessage());
            } finally {
                TestArango.shutdown();
            }
        } else if (targetDb.equals("2")) {
            Connection conn = null;
            try {
                Class.forName("org.postgresql.Driver");
                conn = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "password");

                System.out.println("A procurar esquemas (grafos AGE) no servidor...");
                java.util.List<String> userDbs = new java.util.ArrayList<>();
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE EXTENSION IF NOT EXISTS age CASCADE;");
                    stmt.execute("LOAD 'age';");
                    stmt.execute("SET search_path = ag_catalog, \"$user\", public;");
                    ResultSet rs = stmt.executeQuery("SELECT graph_name FROM ag_catalog.ag_graph");
                    int counter = 1;
                    while (rs.next()) {
                        String dbName = rs.getString(1);
                        System.out.println("  " + counter + ". " + dbName);
                        userDbs.add(dbName);
                        counter++;
                    }
                }

                if (userDbs.isEmpty()) {
                    System.out.println("[ERRO] Nenhuma base de dados encontrada! Faça a ingestão primeiro.");
                    return;
                }

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
                System.out.println("-> Selecionada: " + dbAlvo);

                BenchmarkEnginePostgres engine = new BenchmarkEnginePostgres(dbAlvo);
                engine.warmUpAndHarvest(conn);

                System.out.println("\nSelecione o perfil de distribuição de carga (HTAP):");
                System.out.println("1. Read-Heavy (95% Leituras Simples | 5% Escritas Atómicas) - Baseline");
                System.out.println("2. Balanced (50% Leituras Mistas | 50% Escritas Mistas) - Lock Contention");
                System.out.println("3. Analytical Stress (5% Leituras | 95% Escritas Topológicas e Recursivas) - Breakdown");
                System.out.print("Escolha uma opção (1-3): ");
                String perfilOpcao = scanner.nextLine().trim();

                String perfilNome;
                int readPercentage;
                int[] probsLeitura = { 22, 22, 11, 11, 7, 7, 6, 5, 5, 4 };
                int[] probsEscrita = { 22, 22, 11, 11, 7, 7, 6, 5, 5, 4 };

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

                System.out.print("Introduza o número de clientes concorrentes (ex: 100): ");
                int numClientes;
                try {
                    numClientes = Integer.parseInt(scanner.nextLine().trim());
                } catch (Exception e) {
                    numClientes = 100;
                    System.out.println("Entrada inválida. A assumir 100 clientes por defeito.");
                }

                engine.runWorkload(conn, perfilNome, probsLeitura, probsEscrita, readPercentage, numClientes);
                engine.limparDadosTemporarios(conn);

            } catch (Exception e) {
                System.out.println("[ERRO] Falha ao ligar ao PostgreSQL ou ao executar o benchmark: " + e.getMessage());
            } finally {
                if (conn != null) {
                    try { conn.close(); } catch (Exception ex) {}
                }
            }
        } else {
            System.out.println("Opção inválida.");
        }
    }
}
