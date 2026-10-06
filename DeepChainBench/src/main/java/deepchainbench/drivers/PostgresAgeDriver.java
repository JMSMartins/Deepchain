/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package deepchainbench.drivers;

import deepchainbench.core.DatabaseDriver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * Driver Multi-Modelo para PostgreSQL com extensão Apache AGE.
 * Mapeamento:
 * - AGE (Grafo): Parts e BoM_Edges
 * - Postgres JSONB (KV/Doc): Quality_KV e Telemetry
 * 
 * @author jorgemartins
 */
public class PostgresAgeDriver implements DatabaseDriver {

    private Connection conn;
    private String currentGraphName;
    
    // Nomes lógicos das estruturas
    private static final String QUALITY_TABLE = "quality_kv";
    private static final String TELEMETRY_TABLE = "telemetry";

    @Override
    public void connect(String host, int port, String user, String password) throws Exception {
        System.out.println("   [Postgres+AGE] A estabelecer ligação a " + host + ":" + port + "...");
        
        // Assegura que o driver JDBC do PostgreSQL está carregado
        Class.forName("org.postgresql.Driver");
        
        // Liga à base de dados default (postgres). Podes alterar para "deepchaindb" se preferires
        String url = "jdbc:postgresql://" + host + ":" + port + "/postgres";
        this.conn = DriverManager.getConnection(url, user, password);
        
        // Ativar a extensão AGE e configurar o search_path
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS age CASCADE;");
            stmt.execute("LOAD 'age';");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public;");
        }
    }

    @Override
    public void setupSchema(String nomeSchema) throws Exception {
        this.currentGraphName = nomeSchema.toLowerCase(); // AGE prefere nomes de grafos em minúsculas
        System.out.println("   [Postgres+AGE] A configurar ambiente multi-modelo (" + currentGraphName + ")...");

        try (Statement stmt = conn.createStatement()) {
            // 1. Limpar e Criar o Grafo (AGE)
            ResultSet rs = stmt.executeQuery("SELECT count(*) FROM ag_catalog.ag_graph WHERE name = '" + currentGraphName + "'");
            if (rs.next() && rs.getInt(1) > 0) {
                stmt.execute("SELECT drop_graph('" + currentGraphName + "', true);");
            }
            stmt.execute("SELECT create_graph('" + currentGraphName + "');");
            
            // 2. Criar Labels de Vértices e Arestas explicitamente
            stmt.execute("SELECT create_vlabel('" + currentGraphName + "', 'Part');");
            stmt.execute("SELECT create_elabel('" + currentGraphName + "', 'BoM');");
            
            // ---> ÍNDICES OTIMIZADOS PARA O APACHE AGE <---
            // Índice GIN genérico para as propriedades
            stmt.execute("CREATE INDEX ON " + currentGraphName + ".\"Part\" USING GIN (properties);");
            
            // Índice BTREE específico na propriedade 'id' (CRUCIAL para a velocidade da ingestão de arestas)
            stmt.execute("CREATE INDEX ON " + currentGraphName + ".\"Part\" USING BTREE (ag_catalog.agtype_access_operator(properties, '\"id\"'));");
            // ----------------------------------------------
            
            // 3. Limpar e Criar Tabela para Key-Value Simulado (Nativo Postgres)
            stmt.execute("DROP TABLE IF EXISTS " + currentGraphName + "_" + QUALITY_TABLE + ";");
            stmt.execute("CREATE TABLE " + currentGraphName + "_" + QUALITY_TABLE + " (key VARCHAR PRIMARY KEY, value JSONB);");
            
            // 4. Limpar e Criar Tabela para Documentos Simulado (Nativo Postgres)
            stmt.execute("DROP TABLE IF EXISTS " + currentGraphName + "_" + TELEMETRY_TABLE + ";");
            stmt.execute("CREATE TABLE " + currentGraphName + "_" + TELEMETRY_TABLE + " (id SERIAL PRIMARY KEY, data JSONB);");
            
            System.out.println("   [Postgres+AGE] Esquemas de Grafo e Tabelas JSONB configurados com sucesso.");
        }
    }

    @Override
    public void ingestParts(String csvPath) throws Exception {
        System.out.println("   [Postgres+AGE] A carregar vértices (Parts) via Cypher UNWIND...");
        ObjectMapper mapper = new ObjectMapper();
        
        // Query Cypher otimizada: UNWIND desempacota o array JSON iterando sobre os elementos
        String sql = "SELECT * FROM cypher('" + currentGraphName + "', $$ " +
                     "UNWIND $batch AS p " +
                     "CREATE (:Part {id: p.id, name: p.name, material: p.material}) " +
                     "$$, ?) AS (v agtype);";
                     
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            
            br.readLine(); 
            String line;
            List<Map<String, Object>> batchList = new ArrayList<>();
            
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", cleanQuotes(tokens[0]));
                doc.put("name", tokens[1]);
                doc.put("material", tokens[2]);
                batchList.add(doc);

                if (batchList.size() >= 1000) {
                    executeGraphBatch(pstmt, mapper, batchList);
                }
            }
            if (!batchList.isEmpty()) {
                executeGraphBatch(pstmt, mapper, batchList);
            }
        }
    }

    @Override
    public void ingestEdges(String csvPath) throws Exception {
        System.out.println("   [Postgres+AGE] A atualizar estatísticas do Query Planner (ANALYZE)...");
        
        // ---> ATUALIZAR ESTATÍSTICAS PARA FORÇAR O USO DO ÍNDICE <---
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ANALYZE " + currentGraphName + ".\"Part\";");
        }
        // ---------------------------------------------------------
        
        System.out.println("   [Postgres+AGE] A carregar arestas (BoM Edges) via Cypher MATCH+CREATE...");
        ObjectMapper mapper = new ObjectMapper();
        
        // Query Cypher: Procura os vértices previamente inseridos e cria a aresta entre eles
        String sql = "SELECT * FROM cypher('" + currentGraphName + "', $$ " +
                     "UNWIND $batch AS e " +
                     "MATCH (a:Part {id: e.from}), (b:Part {id: e.to}) " +
                     "CREATE (a)-[:BoM {qty: e.qty}]->(b) " +
                     "$$, ?) AS (e agtype);";

        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            
            br.readLine(); 
            String line;
            List<Map<String, Object>> batchList = new ArrayList<>();
            
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> edge = new HashMap<>();
                edge.put("from", cleanQuotes(tokens[0]));
                edge.put("to", cleanQuotes(tokens[1]));
                edge.put("qty", Integer.parseInt(tokens[2]));
                batchList.add(edge);

                if (batchList.size() >= 1000) {
                    executeGraphBatch(pstmt, mapper, batchList);
                }
            }
            if (!batchList.isEmpty()) {
                executeGraphBatch(pstmt, mapper, batchList);
            }
        }
    }

    @Override
    public void ingestQualityKV(String csvPath) throws Exception {
        System.out.println("   [Postgres+AGE] A carregar metadados (Quality KV) usando Postgres JDBC genérico...");
        ObjectMapper mapper = new ObjectMapper();
        
        // Escrita nativa em tabela PostgreSQL com casting para JSONB
        String sql = "INSERT INTO " + currentGraphName + "_" + QUALITY_TABLE + " (key, value) VALUES (?, ?::jsonb)";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            
            br.readLine(); 
            String line;
            int batchCount = 0;
            
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                String key = cleanQuotes(tokens[0]);
                String[] meta = tokens[1].split(";");
                
                Map<String, Object> jsonDoc = new HashMap<>();
                jsonDoc.put("cert", meta[0].split(":")[1]);
                jsonDoc.put("score", Integer.parseInt(meta[1].split(":")[1]));

                pstmt.setString(1, key);
                pstmt.setString(2, mapper.writeValueAsString(jsonDoc));
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

    @Override
    public void ingestTelemetry(String jsonPath) throws Exception {
        System.out.println("   [Postgres+AGE] A carregar documentos (Telemetry) para Postgres JSONB...");
        
        String sql = "INSERT INTO " + currentGraphName + "_" + TELEMETRY_TABLE + " (data) VALUES (?::jsonb)";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             BufferedReader br = new BufferedReader(new FileReader(jsonPath))) {
            
            String line;
            int batchCount = 0;
            
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                pstmt.setString(1, line); // Inserimos a string JSON diretamente no JSONB
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

    @Override
    public void executeReadQuery() throws Exception {
        System.out.println("      [Simulação] Postgres+AGE a executar Query de Leitura...");
    }

    @Override
    public void executeWriteQuery() throws Exception {
        System.out.println("      [Simulação] Postgres+AGE a executar Operação de Escrita...");
    }

    @Override
    public long getSchemaSizeInBytes() throws Exception {
        System.out.println("   [Postgres+AGE] A calcular tamanho do dataset físico...");
        long totalBytes = 0;
        
        try (Statement stmt = conn.createStatement()) {
            // No PostgreSQL podemos usar diretamente as funções internas para ver o tamanho real em disco das tabelas e índices
            // 1. Tamanho da tabela e índices KV
            ResultSet rsKV = stmt.executeQuery("SELECT pg_total_relation_size('" + currentGraphName + "_" + QUALITY_TABLE + "');");
            if (rsKV.next()) totalBytes += rsKV.getLong(1);
            
            // 2. Tamanho da tabela e índices Telemetria
            ResultSet rsTel = stmt.executeQuery("SELECT pg_total_relation_size('" + currentGraphName + "_" + TELEMETRY_TABLE + "');");
            if (rsTel.next()) totalBytes += rsTel.getLong(1);
            
            // 3. Tamanho das tabelas criadas pelo AGE dentro do Schema do Grafo
            // O AGE cria um schema PostgreSQL com o nome do grafo para armazenar vértices e arestas
            String graphSizeSql = "SELECT sum(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename))) " +
                                  "FROM pg_tables WHERE schemaname = '" + currentGraphName + "';";
            ResultSet rsGraph = stmt.executeQuery(graphSizeSql);
            if (rsGraph.next()) totalBytes += rsGraph.getLong(1);
            
            System.out.println("      [Info] Tamanho total em disco (Grafos + JSONB + Índices): " + totalBytes + " bytes");
        } catch (Exception e) {
            System.err.println("   [DEBUG Erro] Falha ao calcular tamanho: " + e.getMessage());
        }
        
        return totalBytes;
    }

    public Connection getConnection() {
        return this.conn;
    }

    @Override
    public void dropDatabase() throws Exception {
        if (conn != null && currentGraphName != null) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT drop_graph('" + currentGraphName + "', true);");
                stmt.execute("DROP TABLE IF EXISTS " + currentGraphName + "_" + QUALITY_TABLE + ";");
                stmt.execute("DROP TABLE IF EXISTS " + currentGraphName + "_" + TELEMETRY_TABLE + ";");
                System.out.println("   [Postgres+AGE] Limpeza efetuada (Grafo e Tabelas removidos).");
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
            System.out.println("   [Postgres+AGE] Ligação ao servidor encerrada.");
        }
    }

    // --- Métodos Auxiliares Privados ---

    private String cleanQuotes(String value) {
        if (value == null) return null;
        return value.replaceAll("^\"|\"$", "");
    }
    
    /**
     * Injeta um batch JSON para o Apache AGE processar usando o UNWIND do Cypher.
     */
    private void executeGraphBatch(PreparedStatement pstmt, ObjectMapper mapper, List<Map<String, Object>> batchList) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("batch", batchList);
        
        // Converte o Batch para String JSON e associa ao ? da query
        String jsonStr = mapper.writeValueAsString(params);
        
        // Configurar o objeto para o tipo agtype do Apache AGE
        PGobject agtypeParam = new PGobject();
        agtypeParam.setType("agtype");
        agtypeParam.setValue(jsonStr);
        
        pstmt.setObject(1, agtypeParam);
        
        // Executa a transação do Cypher
        pstmt.execute();
        batchList.clear(); // Limpa para o próximo bloco
    }
}