package multibench.ingestor;

import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.entity.CollectionType;
import com.arangodb.model.CollectionCreateOptions;
import com.arangodb.model.GraphCreateOptions;
import com.arangodb.entity.EdgeDefinition;

import java.io.*;
import java.util.*;

public class ArangoIngestor {
    private static final String HOST = "IP_DO_TEU_SERVIDOR"; // Altera para o IP real
    private static final int PORT = 8529;
    private static final String DB_NAME = "DeepChainDB";
    
    // Nomes das Coleções lógicas do teu gerador
    private static final String PARTS_COLL = "Parts";
    private static final String BOM_COLL = "BoM_Edges";
    private static final String QUALITY_COLL = "Quality_KV";
    private static final String TELEMETRY_COLL = "Telemetry";
    private static final String GRAPH_NAME = "SupplyChainGraph";

    public static void run(String[] args) {
        // 1. Inicializar a ligação ao servidor remoto
        
        ArangoDB arangoDB = new ArangoDB.Builder()
                .host(HOST, PORT)
                .user("root")
                .password("tuapassword")
                .build();
        try {
            // Inicializar e limpar o ambiente para garantir o rigor do teste
            if (arangoDB.db(DB_NAME).exists()) {
                arangoDB.db(DB_NAME).drop();
            }
            arangoDB.createDatabase(DB_NAME);
            ArangoDatabase db = arangoDB.db(DB_NAME);
            System.out.println("Base de dados DeepChainDB criada.");

            // 2. Criar Coleções Documentais normais (Vértices, KV e Telemetria)
            db.createCollection(PARTS_COLL);
            db.createCollection(QUALITY_COLL);
            db.createCollection(TELEMETRY_COLL);

            // 3. Criar Coleção de Arestas (Edge Collection) e definir o Grafo Nativo
            db.createCollection(BOM_COLL, new CollectionCreateOptions().type(CollectionType.EDGES));
            
            EdgeDefinition edgeDefinition = new EdgeDefinition()
                .collection(BOM_COLL)
                .from(PARTS_COLL)
                .to(PARTS_COLL);
                
            db.createGraph(GRAPH_NAME, Collections.singletonList(edgeDefinition), new GraphCreateOptions());
            System.out.println("Estrutura de Grafo Nativo configurada com sucesso.");

            // 4. Iniciar Ingestão Massiva (Bulk Load) medindo o tempo
            long startTime = System.currentTimeMillis();
            
            String basePath = "./dataset/SF1/"; // Caminho onde o teu gerador guardou os ficheiros
            
            ingestParts(db, basePath + "parts.csv");
            ingestBoM(db, basePath + "bom_edges.csv");
            ingestQualityKV(db, basePath + "quality_kv.csv");
            ingestTelemetry(db, basePath + "telemetry.json");
            
            long endTime = System.currentTimeMillis();
            System.out.println(">>> FASE 1: Ingestão Concluída em " + (endTime - startTime) + " ms.");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            arangoDB.shutdown();
        }
    }

    // Ingestão de Vértices: Mapeia o id para a chave primária nativa do Arango (_key)
    private static void ingestParts(ArangoDatabase db, String path) throws IOException {
        System.out.println("A carregar Parts...");
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine(); // Saltar cabeçalho
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> doc = new HashMap<>();
                doc.put("_key", tokens[0]); // O ID passa a ser a chave primária física
                doc.put("name", tokens[1]);
                doc.put("material", tokens[2]);
                batch.add(doc);
                
                if (batch.size() >= 1000) { // Inserção em lote para performance
                    db.collection(PARTS_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) db.collection(PARTS_COLL).insertDocuments(batch);
        }
    }

    // Ingestão de Arestas: Transforma o "from" e "to" no formato "Colecao/Chave" exigido pelo Arango
    private static void ingestBoM(ArangoDatabase db, String path) throws IOException {
        System.out.println("A carregar BoM Edges...");
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine(); // Saltar cabeçalho
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> edge = new HashMap<>();
                edge.put("_from", PARTS_COLL + "/" + tokens[0]); // Ex: Parts/1
                edge.put("_to", PARTS_COLL + "/" + tokens[1]);   // Ex: Parts/0
                edge.put("qty", Integer.parseInt(tokens[2]));
                batch.add(edge);
                
                if (batch.size() >= 1000) {
                    db.collection(BOM_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) db.collection(BOM_COLL).insertDocuments(batch);
        }
    }

    // Ingestão do Key-Value simulado: Guarda como documento simples indexado por chave
    private static void ingestQualityKV(ArangoDatabase db, String path) throws IOException {
        System.out.println("A carregar Quality KV...");
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine(); // Saltar cabeçalho
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                Map<String, Object> kv = new HashMap<>();
                kv.put("_key", tokens[0]); // Chave de procura rápida O(1)
                
                // Faz o parsing da string "cert:Tipo A;score:85" para atributos JSON reais
                String[] meta = tokens[1].split(";");
                kv.put("cert", meta[0].split(":")[1]);
                kv.put("score", Integer.parseInt(meta[1].split(":")[1]));
                batch.add(kv);
                
                if (batch.size() >= 1000) {
                    db.collection(QUALITY_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) db.collection(QUALITY_COLL).insertDocuments(batch);
        }
    }

    // Ingestão de Telemetria: Insere a string JSON diretamente, pois o Arango já aceita JSON puro nativamente
    private static void ingestTelemetry(ArangoDatabase db, String path) throws IOException {
        System.out.println("A carregar Telemetry...");
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            List<String> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                // Como o teu gerador já cospe JSON cru estruturado por linha, inserimos a String direta
                batch.add(line);
                
                if (batch.size() >= 1000) {
                    db.collection(TELEMETRY_COLL).insertDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) db.collection(TELEMETRY_COLL).insertDocuments(batch);
        }
    }
}
