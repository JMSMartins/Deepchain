package deepchainbench.drivers;

import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.entity.CollectionType;
import com.arangodb.entity.EdgeDefinition;
import com.arangodb.model.CollectionCreateOptions;
import com.arangodb.model.GraphCreateOptions;
import deepchainbench.core.DatabaseDriver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;


/**
 *
 * @author jorgemartins
 */
public class ArangoDriver implements DatabaseDriver {

    private ArangoDB arangoDB;
    private ArangoDatabase db;
    private String currentDbName; 
    
    // Variáveis para guardar credenciais para o pedido HTTP nativo
    private String dbHost;
    private int dbPort;
    private String dbUser;
    private String dbPassword;

    private static final String PARTS_COLL = "Parts";
    private static final String BOM_COLL = "BoM_Edges";
    private static final String QUALITY_COLL = "Quality_KV";
    private static final String TELEMETRY_COLL = "Telemetry";
    private static final String GRAPH_NAME = "SupplyChainGraph";

    @Override
    public void connect(String host, int port, String user, String password) throws Exception {
        // Guarda as credenciais para o cálculo do tamanho mais tarde
        this.dbHost = host;
        this.dbPort = port;
        this.dbUser = user;
        this.dbPassword = password;

        System.out.println("   [ArangoDB] A estabelecer ligação a " + host + ":" + port + "...");
        this.arangoDB = new ArangoDB.Builder()
                .host(host, port)
                .user(user)
                .password(password)
                .maxConnections(150)
                .build();
    }

    @Override
    public void setupSchema(String nomeSchema) throws Exception {
        this.currentDbName = nomeSchema;

        if (arangoDB.db(currentDbName).exists()) {
            System.out.println("   [ArangoDB] A remover base de dados antiga (" + currentDbName + ")...");
            arangoDB.db(currentDbName).drop();
        }

        arangoDB.createDatabase(currentDbName);
        this.db = arangoDB.db(currentDbName);
        System.out.println("   [ArangoDB] Nova base de dados '" + currentDbName + "' criada.");

        db.createCollection(PARTS_COLL);
        db.createCollection(QUALITY_COLL);
        db.createCollection(TELEMETRY_COLL);

        db.createCollection(BOM_COLL, new CollectionCreateOptions().type(CollectionType.EDGES));

        EdgeDefinition edgeDefinition = new EdgeDefinition()
                .collection(BOM_COLL)
                .from(PARTS_COLL)
                .to(PARTS_COLL);

        db.createGraph(GRAPH_NAME, Collections.singletonList(edgeDefinition), new GraphCreateOptions());
        System.out.println("   [ArangoDB] Estrutura de Grafo Nativo configurada.");
    }

    @Override
    public void ingestParts(String csvPath) throws Exception {
        System.out.println("   [ArangoDB] A carregar vértices (Parts) de: " + csvPath);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            br.readLine(); 
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> doc = new HashMap<>();
                doc.put("_key", tokens[0]);
                doc.put("name", tokens[1]);
                doc.put("material", tokens[2]);
                batch.add(doc);

                if (batch.size() >= 1000) {
                    db.collection(PARTS_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                db.collection(PARTS_COLL).insertDocuments(batch);
            }
        }
    }

    @Override
    public void ingestEdges(String csvPath) throws Exception {
        System.out.println("   [ArangoDB] A carregar arestas (BoM Edges) de: " + csvPath);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            br.readLine(); 
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> edge = new HashMap<>();
                edge.put("_from", PARTS_COLL + "/" + tokens[0]);
                edge.put("_to", PARTS_COLL + "/" + tokens[1]);
                edge.put("qty", Integer.parseInt(tokens[2]));
                batch.add(edge);

                if (batch.size() >= 1000) {
                    db.collection(BOM_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                db.collection(BOM_COLL).insertDocuments(batch);
            }
        }
    }

    @Override
    public void ingestQualityKV(String csvPath) throws Exception {
        System.out.println("   [ArangoDB] A carregar metadados (Quality KV) de: " + csvPath);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            br.readLine(); 
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> kv = new HashMap<>();
                kv.put("_key", tokens[0]);

                String[] meta = tokens[1].split(";");
                kv.put("cert", meta[0].split(":")[1]);
                kv.put("score", Integer.parseInt(meta[1].split(":")[1]));
                batch.add(kv);

                if (batch.size() >= 1000) {
                    db.collection(QUALITY_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                db.collection(QUALITY_COLL).insertDocuments(batch);
            }
        }
    }

   @Override
    public void ingestTelemetry(String jsonPath) throws Exception {
        System.out.println("   [ArangoDB] A carregar documentos aninhados (Telemetry) de: " + jsonPath);
        
        // Instancia o conversor de JSON
        ObjectMapper mapper = new ObjectMapper();
        
        try (BufferedReader br = new BufferedReader(new FileReader(jsonPath))) {
            String line;
            // O batch agora é uma lista de Maps (tal como as Parts e os Edges)
            List<Map<String, Object>> batch = new ArrayList<>(); 
            
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue; // Salta linhas em branco
                
                // Transforma a String JSON num objeto Map nativo do Java
                Map<String, Object> doc = mapper.readValue(line, Map.class);
                batch.add(doc);
                
                if (batch.size() >= 1000) {
                    db.collection(TELEMETRY_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                db.collection(TELEMETRY_COLL).insertDocuments(batch);
            }
        }
    }

    @Override
    public void executeReadQuery() throws Exception {
        System.out.println("      [Simulação] ArangoDB a executar Query de Leitura...");
    }

    @Override
    public void executeWriteQuery() throws Exception {
        System.out.println("      [Simulação] ArangoDB a executar Operação de Escrita...");
    }

    @Override
    public void dropDatabase() throws Exception {
        if (arangoDB != null && currentDbName != null && arangoDB.db(currentDbName).exists()) {
            arangoDB.db(currentDbName).drop();
            System.out.println("   [ArangoDB] Base de dados '" + currentDbName + "' removida para limpeza.");
        }
    }

@Override
    public long getSchemaSizeInBytes() throws Exception {
        System.out.println("   [ArangoDB] A calcular tamanho do dataset através de metadados lógicos...");
        
        long totalSize = 0;
        String[] collections = {PARTS_COLL, BOM_COLL, QUALITY_COLL, TELEMETRY_COLL};
        
        if (db == null) return 0;

        for (String coll : collections) {
            try {
                // 1. Conta quantos documentos estão realmente lá dentro (Isto é instantâneo e fiável)
                long docCount = db.collection(coll).count().getCount();
                
                if (docCount > 0) {
                    // 2. Vai buscar 1 documento apenas para amostra
                    String sampleQuery = "FOR d IN " + coll + " LIMIT 1 RETURN d";
                    com.arangodb.ArangoCursor<Map> cursor = db.query(sampleQuery, Map.class);
                    
                    if (cursor.hasNext()) {
                        Map doc = cursor.next();
                        
                        // 3. Usa o toString() nativo do Java em vez do serializador do ArangoDB
                        String docAsString = doc.toString();
                        
                        // Converte para bytes forçando o padrão UTF-8 para não haver erros de encoding
                        long avgDocSizeBytes = docAsString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        
                        // 4. Soma ao tamanho total da Base de Dados
                        totalSize += (avgDocSizeBytes * docCount);
                        System.out.println("      [Info] " + coll + ": " + docCount + " documentos (Tamanho base: " + avgDocSizeBytes + " bytes/doc)");
                    }
                }
            } catch (Exception e) {
                System.err.println("   [DEBUG Erro] Falha ao calcular tamanho lógico de " + coll + ": " + e.getMessage());
            }
        }
        return totalSize;
    }

    @Override
    public void close() throws Exception {
        if (arangoDB != null) {
            arangoDB.shutdown();
            System.out.println("   [ArangoDB] Ligação ao servidor encerrada.");
        }
    }
}