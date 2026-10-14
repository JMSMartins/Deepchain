package deepchainbench.drivers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.db.OrientDBConfig;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.ODatabaseType;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.OEdge;
import com.orientechnologies.orient.core.record.OElement;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import deepchainbench.core.DatabaseDriver;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OrientDB Driver implementation.
 */
public class OrientDbDriver implements DatabaseDriver {

    private OrientDB orientDB;
    private ODatabaseSession db;
    private String currentDbName;

    // Variáveis para guardar credenciais
    private String dbHost;
    private int dbPort;
    private String dbUser;
    private String dbPassword;

    private static final String PARTS_COLL = "Parts";
    private static final String BOM_COLL = "BoM_Edges";
    private static final String QUALITY_COLL = "Quality_KV";
    private static final String TELEMETRY_COLL = "Telemetry";
    private static final String GRAPH_NAME = "SupplyChainGraph";

    private final Map<String, ORID> partsRidMap = new HashMap<>();

    @Override
    public void connect(String host, int port, String user, String password) throws Exception {
        this.dbHost = host;
        this.dbPort = port;
        this.dbUser = user;
        this.dbPassword = password;

        System.out.println("   [OrientDB] A estabelecer ligação a " + host + ":" + port + "...");
        this.orientDB = new OrientDB("remote:" + host + ":" + port, user, password, OrientDBConfig.defaultConfig());
    }

    @Override
    public void setupSchema(String nomeSchema) throws Exception {
        this.currentDbName = nomeSchema;

        if (orientDB.exists(currentDbName)) {
            System.out.println("   [OrientDB] A remover base de dados antiga (" + currentDbName + ")...");
            if (this.db != null && !this.db.isClosed()) {
                this.db.close();
            }
            orientDB.drop(currentDbName);
        }

        orientDB.create(currentDbName, ODatabaseType.PLOCAL);
        this.db = orientDB.open(currentDbName, dbUser, dbPassword);
        System.out.println("   [OrientDB] Nova base de dados '" + currentDbName + "' criada.");

        db.createVertexClass(PARTS_COLL);
        db.createEdgeClass(BOM_COLL);
        db.createClass(QUALITY_COLL);
        db.createClass(TELEMETRY_COLL);

        partsRidMap.clear();

        System.out.println("   [OrientDB] Estrutura de Grafo Nativo configurada.");
    }

    @Override
    public void ingestParts(String csvPath) throws Exception {
        System.out.println("   [OrientDB] A carregar vértices (Parts) de: " + csvPath);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            br.readLine();
            String line;
            db.begin();
            List<OVertex> batch = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");
                OVertex vertex = db.newVertex(PARTS_COLL);
                vertex.setProperty("_key", tokens[0]);
                vertex.setProperty("name", tokens[1]);
                vertex.setProperty("material", tokens[2]);
                vertex.save();
                batch.add(vertex);

                if (batch.size() >= 1000) {
                    db.commit();
                    for (OVertex v : batch) {
                        partsRidMap.put(v.getProperty("_key"), v.getIdentity());
                    }
                    batch.clear();
                    db.begin();
                }
            }
            if (!batch.isEmpty()) {
                db.commit();
                for (OVertex v : batch) {
                    partsRidMap.put(v.getProperty("_key"), v.getIdentity());
                }
                batch.clear();
            }
        }
    }

    @Override
    public void ingestEdges(String csvPath) throws Exception {
        System.out.println("   [OrientDB] A carregar arestas (BoM Edges) de: " + csvPath);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            br.readLine();
            String line;
            db.begin();
            int count = 0;
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");

                ORID fromRid = partsRidMap.get(tokens[0]);
                ORID toRid = partsRidMap.get(tokens[1]);

                if (fromRid != null && toRid != null) {
                    OVertex fromVertex = db.load(fromRid);
                    OVertex toVertex = db.load(toRid);

                    OEdge edge = fromVertex.addEdge(toVertex, BOM_COLL);
                    edge.setProperty("qty", Integer.parseInt(tokens[2]));
                    edge.save();

                    count++;
                }

                if (count >= 1000) {
                    db.commit();
                    count = 0;
                    db.begin();
                }
            }
            if (count > 0) {
                db.commit();
            }
        }
    }

    @Override
    public void ingestQualityKV(String csvPath) throws Exception {
        System.out.println("   [OrientDB] A carregar metadados (Quality KV) de: " + csvPath);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            br.readLine();
            String line;
            db.begin();
            int count = 0;
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\|");

                OElement kv = db.newElement(QUALITY_COLL);
                kv.setProperty("_key", tokens[0]);

                String[] meta = tokens[1].split(";");
                kv.setProperty("cert", meta[0].split(":")[1]);
                kv.setProperty("score", Integer.parseInt(meta[1].split(":")[1]));
                kv.save();

                count++;

                if (count >= 1000) {
                    db.commit();
                    count = 0;
                    db.begin();
                }
            }
            if (count > 0) {
                db.commit();
            }
        }
    }

    @Override
    public void ingestTelemetry(String jsonPath) throws Exception {
        System.out.println("   [OrientDB] A carregar documentos aninhados (Telemetry) de: " + jsonPath);

        ObjectMapper mapper = new ObjectMapper();

        try (BufferedReader br = new BufferedReader(new FileReader(jsonPath))) {
            String line;
            db.begin();
            int count = 0;

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                Map<String, Object> doc = mapper.readValue(line, Map.class);

                OElement elem = db.newElement(TELEMETRY_COLL);
                for (Map.Entry<String, Object> entry : doc.entrySet()) {
                    elem.setProperty(entry.getKey(), entry.getValue());
                }
                elem.save();

                count++;

                if (count >= 1000) {
                    db.commit();
                    count = 0;
                    db.begin();
                }
            }
            if (count > 0) {
                db.commit();
            }
        }
    }

    @Override
    public void executeReadQuery() throws Exception {
        System.out.println("      [Simulação] OrientDB a executar Query de Leitura...");
    }

    @Override
    public void executeWriteQuery() throws Exception {
        System.out.println("      [Simulação] OrientDB a executar Operação de Escrita...");
    }

    @Override
    public void dropDatabase() throws Exception {
        if (db != null && !db.isClosed()) {
            db.close();
        }
        if (orientDB != null && currentDbName != null && orientDB.exists(currentDbName)) {
            orientDB.drop(currentDbName);
            System.out.println("   [OrientDB] Base de dados '" + currentDbName + "' removida para limpeza.");
        }
    }

    @Override
    public long getSchemaSizeInBytes() throws Exception {
        System.out.println("   [OrientDB] A calcular tamanho do dataset através de metadados lógicos...");

        long totalSize = 0;
        String[] collections = {PARTS_COLL, BOM_COLL, QUALITY_COLL, TELEMETRY_COLL};

        if (db == null) return 0;

        for (String coll : collections) {
            try {
                long docCount = db.countClass(coll);

                if (docCount > 0) {
                    String sampleQuery = "SELECT FROM " + coll + " LIMIT 1";
                    try (OResultSet rs = db.query(sampleQuery)) {
                        if (rs.hasNext()) {
                            String docAsString = rs.next().toElement().toJSON();
                            long avgDocSizeBytes = docAsString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

                            totalSize += (avgDocSizeBytes * docCount);
                            System.out.println("      [Info] " + coll + ": " + docCount + " documentos (Tamanho base: " + avgDocSizeBytes + " bytes/doc)");
                        }
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
        if (db != null && !db.isClosed()) {
            db.close();
        }
        if (orientDB != null) {
            orientDB.close();
            System.out.println("   [OrientDB] Ligação ao servidor encerrada.");
        }
    }
}
