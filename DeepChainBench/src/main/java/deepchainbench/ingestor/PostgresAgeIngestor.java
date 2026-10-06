package deepchainbench.ingestor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.sql.*;
import java.util.*;

public class PostgresAgeIngestor {

    private static final String HOST = "localhost"; // Altera para o IP real se necessário
    private static final int PORT = 5432;
    private static final String DB_NAME = "postgres"; // Ou a tua base de dados de testes
    private static final String USER = "postgres";
    private static final String PASSWORD = "password";

    // Nomes das Estruturas Lógicas
    private static final String PARTS_COLL = "parts";
    private static final String BOM_COLL = "bom_edges";
    private static final String QUALITY_COLL = "quality_kv";
    private static final String TELEMETRY_COLL = "telemetry";
    private static final String GRAPH_NAME = "supplychaingraph"; // O AGE prefere minúsculas

    public static void run(String[] args) {
        // 1. Inicializar a ligação JDBC ao PostgreSQL
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB_NAME;
        
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {

            Class.forName("org.postgresql.Driver");

            // Ativar a extensão AGE e configurar o caminho de pesquisa
            stmt.execute("CREATE EXTENSION IF NOT EXISTS age CASCADE;");
            stmt.execute("LOAD 'age';");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public;");

            // Inicializar e limpar o ambiente para garantir o rigor do teste
            // Verifica se o grafo já existe e remove-o
            ResultSet rsGraph = stmt.executeQuery("SELECT count(*) FROM ag_graph WHERE name = '" + GRAPH_NAME + "';");
            if (rsGraph.next() && rsGraph.getInt(1) > 0) {
                stmt.execute("SELECT drop_graph('" + GRAPH_NAME + "', true);");
            }
            stmt.execute("SELECT create_graph('" + GRAPH_NAME + "');");
            
            // Criar explicitamente os labels de vértices e arestas no AGE
            stmt.execute("SELECT create_vlabel('" + GRAPH_NAME + "', 'Part');");
            stmt.execute("SELECT create_elabel('" + GRAPH_NAME + "', 'BoM');");

            // Limpar e Criar Tabelas Relacionais Nativas para KV e Telemetria (JSONB)
            stmt.execute("DROP TABLE IF EXISTS " + QUALITY_COLL + ";");
            stmt.execute("CREATE TABLE " + QUALITY_COLL + " (key VARCHAR PRIMARY KEY, value JSONB);");

            stmt.execute("DROP TABLE IF EXISTS " + TELEMETRY_COLL + ";");
            stmt.execute("CREATE TABLE " + TELEMETRY_COLL + " (id SERIAL PRIMARY KEY, data JSONB);");

            System.out.println("Base de dados PostgreSQL (com AGE e tabelas JSONB) configurada com sucesso.");

            // 4. Iniciar Ingestão Massiva (Bulk Load) medindo o tempo
            long startTime = System.currentTimeMillis();

            String basePath = "./dataset/SF1/"; // Caminho onde o teu gerador guardou os ficheiros

            ingestParts(conn, basePath + "parts.csv");
            ingestBoM(conn, basePath + "bom_edges.csv");
            ingestQualityKV(conn, basePath + "quality_kv.csv");
            ingestTelemetry(conn, basePath + "telemetry.json");

            long endTime = System.currentTimeMillis();
            System.out.println(">>> FASE 1 (Postgres+AGE): Ingestão Concluída em " + (endTime - startTime) + " ms.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Ingestão de Vértices: Mapeia para nós do Apache AGE usando openCypher UNWIND
    private static void ingestParts(Connection conn, String path) throws Exception {
        System.out.println("A carregar Parts (AGE Vértices)...");
        ObjectMapper mapper = new ObjectMapper();
        
        String cypherSql = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$ " +
                           "UNWIND $batch AS p " +
                           "CREATE (:Part {id: p.id, name: p.name, material: p.material}) " +
                           "$$, ?::agtype) AS (v agtype);";

        try (PreparedStatement pstmt = conn.prepareStatement(cypherSql);
             BufferedReader br = new BufferedReader(new FileReader(path))) {
            
            br.readLine(); // Saltar cabeçalho
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", cleanQuotes(tokens[0]));
                doc.put("name", tokens[1]);
                doc.put("material", tokens[2]);
                batch.add(doc);

                if (batch.size() >= 1000) {
                    executeGraphBatch(pstmt, mapper, batch);
                }
            }
            if (!batch.isEmpty()) {
                executeGraphBatch(pstmt, mapper, batch);
            }
        }
    }

    // Ingestão de Arestas: Procura os nós Parts e cria a relação BoM no Apache AGE
    private static void ingestBoM(Connection conn, String path) throws Exception {
        System.out.println("A carregar BoM Edges (AGE Arestas)...");
        ObjectMapper mapper = new ObjectMapper();
        
        String cypherSql = "SELECT * FROM cypher('" + GRAPH_NAME + "', $$ " +
                           "UNWIND $batch AS e " +
                           "MATCH (a:Part {id: e.from}), (b:Part {id: e.to}) " +
                           "CREATE (a)-[:BoM {qty: e.qty}]->(b) " +
                           "$$, ?::agtype) AS (edge agtype);";

        try (PreparedStatement pstmt = conn.prepareStatement(cypherSql);
             BufferedReader br = new BufferedReader(new FileReader(path))) {
            
            br.readLine(); // Saltar cabeçalho
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> edge = new HashMap<>();
                edge.put("from", cleanQuotes(tokens[0]));
                edge.put("to", cleanQuotes(tokens[1]));
                edge.put("qty", Integer.parseInt(tokens[2]));
                batch.add(edge);

                if (batch.size() >= 1000) {
                    executeGraphBatch(pstmt, mapper, batch);
                }
            }
            if (!batch.isEmpty()) {
                executeGraphBatch(pstmt, mapper, batch);
            }
        }
    }

    // Ingestão do Key-Value simulado: Guarda na tabela relacional nativa do Postgres com coluna JSONB
    private static void ingestQualityKV(Connection conn, String path) throws Exception {
        System.out.println("A carregar Quality KV (Postgres JSONB)...");
        ObjectMapper mapper = new ObjectMapper();
        
        String sql = "INSERT INTO " + QUALITY_COLL + " (key, value) VALUES (?, ?::jsonb)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             BufferedReader br = new BufferedReader(new FileReader(path))) {
            
            br.readLine(); // Saltar cabeçalho
            String line;
            int batchCount = 0;
            
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                String key = cleanQuotes(tokens[0]);

                String[] meta = tokens[1].split(";");
                Map<String, Object> kvMap = new HashMap<>();
                kvMap.put("cert", meta[0].split(":")[1]);
                kvMap.put("score", Integer.parseInt(meta[1].split(":")[1]));

                pstmt.setString(1, key);
                pstmt.setString(2, mapper.writeValueAsString(kvMap));
                pstmt.addBatch();
                batchCount++;

                if (batchCount >= 1000) {
                    pstmt.executeBatch();
                    batchCount = 0;
                }
            }
            if (batchCount > 0) {
                pstmt.executeBatch();
            }
        }
    }

    // Ingestão de Telemetria: Insere a string JSON diretamente na coluna JSONB do Postgres
    private static void ingestTelemetry(Connection conn, String path) throws Exception {
        System.out.println("A carregar Telemetry (Postgres JSONB)...");
        
        String sql = "INSERT INTO " + TELEMETRY_COLL + " (data) VALUES (?::jsonb)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             BufferedReader br = new BufferedReader(new FileReader(path))) {
            
            String line;
            int batchCount = 0;
            
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                pstmt.setString(1, line);
                pstmt.addBatch();
                batchCount++;

                if (batchCount >= 1000) {
                    pstmt.executeBatch();
                    batchCount = 0;
                }
            }
            if (batchCount > 0) {
                pstmt.executeBatch();
            }
        }
    }

    // Método auxiliar para executar lotes Cypher no Apache AGE
    private static void executeGraphBatch(PreparedStatement pstmt, ObjectMapper mapper, List<Map<String, Object>> batch) throws Exception {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("batch", batch);
        
        String jsonPayload = mapper.writeValueAsString(wrapper);
        pstmt.setString(1, jsonPayload);
        pstmt.execute();
        batch.clear();
    }

    // Método auxiliar para limpar aspas
    private static String cleanQuotes(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("^\"|\"$", "");
    }
}