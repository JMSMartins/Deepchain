package deepchainbench.drivers;

import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.remote.RemoteDatabase;
import com.arcadedb.remote.RemoteServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import deepchainbench.core.DatabaseDriver;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Driver do ArcadeDB (modo multi-modelo nativo via API Remota HTTP/JSON) para o DeepChainBench.
 */
public class ArcadeDbDriver implements DatabaseDriver {

    private static final int BATCH_SIZE = 500;
    private static final String[] TYPES = {"Parts", "BoM_Edges", "Quality_KV", "Telemetry"};

    private final ObjectMapper mapper = new ObjectMapper();

    private RemoteServer server;
    private RemoteDatabase db;
    private String host;
    private int port;
    private String user;
    private String password;
    private String currentDbName;

    @Override
    public void connect(String host, int port, String user, String password) throws Exception {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.server = new RemoteServer(host, port, user, password);
        // Validar conectividade com o servidor ArcadeDB
        this.server.databases();
        System.out.println("-> Ligado ao servidor ArcadeDB em " + host + ":" + port);
    }

    @Override
    public void setupSchema(String nomeSchema) throws Exception {
        if (server == null) {
            throw new IllegalStateException("Servidor ArcadeDB não ligado. Chame connect() primeiro.");
        }
        this.currentDbName = nomeSchema;

        if (this.db != null) {
            this.db.close();
            this.db = null;
        }

        if (server.exists(currentDbName)) {
            server.drop(currentDbName);
            System.out.println("Base de dados antiga removida: " + currentDbName);
        }

        server.create(currentDbName);
        this.db = new RemoteDatabase(host, port, currentDbName, user, password);
        this.db.setTimeout(125_000);

        // DDL Multi-Modelo: Vértices (Parts), Arestas (BoM_Edges) e Documentos/KV (Quality_KV, Telemetry)
        String[] ddl = {
                "CREATE VERTEX TYPE Parts IF NOT EXISTS",
                "CREATE PROPERTY Parts._key IF NOT EXISTS STRING",
                "CREATE INDEX IF NOT EXISTS ON Parts (_key) UNIQUE",
                "CREATE EDGE TYPE BoM_Edges IF NOT EXISTS",
                "CREATE DOCUMENT TYPE Quality_KV IF NOT EXISTS",
                "CREATE PROPERTY Quality_KV._key IF NOT EXISTS STRING",
                "CREATE INDEX IF NOT EXISTS ON Quality_KV (_key) UNIQUE",
                "CREATE DOCUMENT TYPE Telemetry IF NOT EXISTS",
                "CREATE PROPERTY Telemetry.part_id IF NOT EXISTS STRING",
                "CREATE INDEX IF NOT EXISTS ON Telemetry (part_id) UNIQUE"
        };

        for (String stmt : ddl) {
            try (ResultSet rs = db.command("sql", stmt)) {
                // Executar DDL sequencialmente
            }
        }
        System.out.println("-> Schema ArcadeDB configurado em '" + currentDbName + "' (Parts, BoM_Edges, Quality_KV, Telemetry).");
    }

    @Override
    public void ingestParts(String csvPath) throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath, StandardCharsets.UTF_8))) {
            br.readLine(); // Cabeçalho
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] tokens = line.split("\\|");
                if (tokens.length < 3) continue;

                Map<String, Object> doc = new HashMap<>();
                doc.put("_key", cleanQuotes(tokens[0]));
                doc.put("name", cleanQuotes(tokens[1]));
                doc.put("material", cleanQuotes(tokens[2]));
                batch.add(doc);

                if (batch.size() >= BATCH_SIZE) {
                    flushInsertBatch("Parts", batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            flushInsertBatch("Parts", batch);
            batch.clear();
        }
    }

    @Override
    public void ingestEdges(String csvPath) throws Exception {
        List<String[]> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath, StandardCharsets.UTF_8))) {
            br.readLine(); // Cabeçalho
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] tokens = line.split("\\|");
                if (tokens.length < 3) continue;

                String fromKey = cleanQuotes(tokens[0]);
                String toKey = cleanQuotes(tokens[1]);
                String qtyStr = tokens[2].trim();
                batch.add(new String[]{fromKey, toKey, qtyStr});

                if (batch.size() >= BATCH_SIZE) {
                    flushEdgeBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            flushEdgeBatch(batch);
            batch.clear();
        }
    }

    @Override
    public void ingestTelemetry(String jsonPath) throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader br = new BufferedReader(new FileReader(jsonPath, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = mapper.readValue(line, Map.class);
                if (doc.containsKey("part_id")) {
                    doc.put("part_id", String.valueOf(doc.get("part_id")));
                }
                doc.putIfAbsent("anomaly", false);
                batch.add(doc);

                if (batch.size() >= BATCH_SIZE) {
                    flushInsertBatch("Telemetry", batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            flushInsertBatch("Telemetry", batch);
            batch.clear();
        }
    }

    @Override
    public void ingestQualityKV(String csvPath) throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath, StandardCharsets.UTF_8))) {
            br.readLine(); // Cabeçalho
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] tokens = line.split("\\|");
                if (tokens.length < 2) continue;

                String key = cleanQuotes(tokens[0]);
                String[] meta = tokens[1].split(";");
                String cert = meta[0].split(":")[1].trim();
                int score = Integer.parseInt(meta[1].split(":")[1].trim());

                Map<String, Object> doc = new HashMap<>();
                doc.put("_key", key);
                doc.put("cert", cert);
                doc.put("score", score);
                batch.add(doc);

                if (batch.size() >= BATCH_SIZE) {
                    flushInsertBatch("Quality_KV", batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            flushInsertBatch("Quality_KV", batch);
            batch.clear();
        }
    }

    private void flushInsertBatch(String typeName, List<Map<String, Object>> batch) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> doc : batch) {
            sb.append("INSERT INTO ").append(typeName)
              .append(" CONTENT ").append(mapper.writeValueAsString(doc)).append(";\n");
        }
        final String script = sb.toString();
        db.transaction(() -> {
            try (ResultSet rs = db.command("sqlscript", script)) {
                // Batch confirmado na transação
            }
        });
    }

    private void flushEdgeBatch(List<String[]> batch) {
        StringBuilder sb = new StringBuilder();
        for (String[] edge : batch) {
            int qty = Integer.parseInt(edge[2]);
            sb.append("CREATE EDGE BoM_Edges FROM (SELECT FROM Parts WHERE _key = '")
              .append(escapeSqlLiteral(edge[0]))
              .append("') TO (SELECT FROM Parts WHERE _key = '")
              .append(escapeSqlLiteral(edge[1]))
              .append("') SET qty = ").append(qty).append(";\n");
        }
        final String script = sb.toString();
        db.transaction(() -> {
            try (ResultSet rs = db.command("sqlscript", script)) {
                // Batch de arestas confirmado na transação
            }
        });
    }

    private static String cleanQuotes(String val) {
        if (val == null) return "";
        return val.replaceAll("^\"|\"$", "").trim();
    }

    private static String escapeSqlLiteral(String val) {
        return val.replace("\\", "\\\\").replace("'", "''");
    }

    @Override
    public void executeReadQuery() throws Exception {
        try (ResultSet rs = db.query("sql", "SELECT _key, part_name, material FROM Parts LIMIT 10")) {
            while (rs.hasNext()) {
                rs.next();
            }
        }
    }

    @Override
    public void executeWriteQuery() throws Exception {
        db.transaction(() -> {
            try (ResultSet rs = db.command("sql", "UPDATE Parts SET is_temp = true WHERE _key = '0_sf1'")) {
                // Teste de escrita unitário
            }
            try (ResultSet rs = db.command("sql", "UPDATE Parts REMOVE is_temp WHERE is_temp = true")) {
                // Reversão imediata
            }
        });
    }

    @Override
    public long getSchemaSizeInBytes() throws Exception {
        if (db == null) return 0L;
        long totalBytes = 0L;
        for (String typeName : TYPES) {
            long count = db.countType(typeName, true);
            if (count > 0) {
                try (ResultSet rs = db.query("sql", "SELECT FROM " + typeName + " LIMIT 1")) {
                    if (rs.hasNext()) {
                        Result sample = rs.next();
                        long docBytes = sample.toJSON().toString().getBytes(StandardCharsets.UTF_8).length;
                        totalBytes += docBytes * count;
                    }
                }
            }
        }
        return totalBytes;
    }

    @Override
    public void dropDatabase() throws Exception {
        if (db != null) {
            db.close();
            db = null;
        }
        if (server != null && currentDbName != null && server.exists(currentDbName)) {
            server.drop(currentDbName);
        }
    }

    @Override
    public void close() throws Exception {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    public RemoteServer getServer() {
        return server;
    }

    public RemoteDatabase getDatabase() {
        return db;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }
}
