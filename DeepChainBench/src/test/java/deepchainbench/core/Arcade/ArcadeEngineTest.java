package deepchainbench.core.Arcade;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.exception.ArcadeDBException;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.exception.TimeoutException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.remote.RemoteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import deepchainbench.core.AbstractBenchmarkEngine.FailureOrigin;
import deepchainbench.drivers.ArcadeDbDriver;
import junit.framework.TestCase;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Testes unitários e de integração embebida para o motor ArcadeDB (schema, Q1-Q10, W1-W10,
 * warm-up, limpeza reversível, bloqueio RFI e classificação de falhas).
 */
public class ArcadeEngineTest extends TestCase {

    private static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteDir(c);
            }
        }
        f.delete();
    }

    public void testRfiDisabledThrowsUnsupported() {
        BenchmarkEngineArcade engine = new BenchmarkEngineArcade();
        try {
            engine.runRFI(null, "Q1", 1);
            fail("Expected UnsupportedOperationException when RFI is invoked for ArcadeDB");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("ArcadeDB"));
        }
    }

    public void testFailureClassification() {
        assertEquals(FailureOrigin.TIMEOUT_CODE_QUERY,
                BenchmarkEngineArcade.classifyArcadeFailure(new SocketTimeoutException("read timed out"), 120_000, 120));
        assertEquals(FailureOrigin.TIMEOUT_CODE_QUERY,
                BenchmarkEngineArcade.classifyArcadeFailure(new TimeoutException("Command timeout"), 115_000, 120));
        assertEquals(FailureOrigin.TIMEOUT_DB,
                BenchmarkEngineArcade.classifyArcadeFailure(new TimeoutException("Command timeout"), 5_000, 120));
        assertEquals(FailureOrigin.DB_LOCK_DEADLOCK,
                BenchmarkEngineArcade.classifyArcadeFailure(new ConcurrentModificationException("MVCC conflict"), 12, 120));
        assertEquals(FailureOrigin.DB_LOCK_DEADLOCK,
                BenchmarkEngineArcade.classifyArcadeFailure(new NeedRetryException("Need retry on lock"), 15, 120));
        assertEquals(FailureOrigin.DB_RESOURCE_OR_ERROR,
                BenchmarkEngineArcade.classifyArcadeFailure(new RemoteException("Internal error"), 20, 120));
        assertEquals(FailureOrigin.DB_RESOURCE_OR_ERROR,
                BenchmarkEngineArcade.classifyArcadeFailure(new ArcadeDBException("Storage error"), 20, 120));
        assertEquals(FailureOrigin.CLIENT_OR_OTHER,
                BenchmarkEngineArcade.classifyArcadeFailure(new IllegalArgumentException("bad arg"), 5, 120));
    }

    public void testDriverLifecycleAndTelemetryInit() throws Exception {
        ArcadeDbDriver driver = new ArcadeDbDriver();
        assertNull(driver.getDatabase());
        assertNull(driver.getServer());
        assertEquals(0L, driver.getSchemaSizeInBytes());
        driver.close();

        ArcadeTelemetry telemetry = new ArcadeTelemetry("/tmp/unused_arcade_telemetry.csv", 10L);
        assertTrue(telemetry.getSystemRamUsedMB() > 0);
    }

    @SuppressWarnings("unchecked")
    public void testEmbeddedWarmUpQueriesWritesAndCleanup() throws Exception {
        String dsDir = "./dataset/SF10/";
        File dsFile = new File(dsDir + "parts.csv");
        if (!dsFile.exists()) {
            // Ambiente sem dataset gerado
            return;
        }

        File tmpRoot = Files.createTempDirectory("arcadedb_engine_test_").toFile();
        String dbPath = new File(tmpRoot, "bench_db").getAbsolutePath();
        ObjectMapper mapper = new ObjectMapper();

        try (Database db = new DatabaseFactory(dbPath).create()) {
            // 1. Setup Schema idêntico ao ArcadeDbDriver
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
            for (String s : ddl) {
                db.command("sql", s).close();
            }

            // 2. Ingestão da fatia _sf1 do dataset SF10
            List<Map<String, Object>> partsBatch = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(dsDir + "parts.csv"))) {
                br.readLine();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] t = line.split("\\|");
                    if (!t[0].endsWith("_sf1")) continue;
                    Map<String, Object> m = new HashMap<>();
                    m.put("_key", t[0]);
                    m.put("name", t[1]);
                    m.put("material", t[2]);
                    partsBatch.add(m);
                }
            }
            for (int i = 0; i < partsBatch.size(); i += 500) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(i + 500, partsBatch.size()); j++) {
                    sb.append("INSERT INTO Parts CONTENT ").append(mapper.writeValueAsString(partsBatch.get(j))).append(";\n");
                }
                final String script = sb.toString();
                db.transaction(() -> db.command("sqlscript", script).close());
            }
            assertEquals(2000L, db.countType("Parts", true));

            List<String[]> edgesBatch = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(dsDir + "bom_edges.csv"))) {
                br.readLine();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] t = line.split("\\|");
                    String from = t[0].replaceAll("^\"|\"$", "");
                    String to = t[1].replaceAll("^\"|\"$", "");
                    if (!from.endsWith("_sf1")) continue;
                    edgesBatch.add(new String[]{from, to, t[2]});
                }
            }
            for (int i = 0; i < edgesBatch.size(); i += 500) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(i + 500, edgesBatch.size()); j++) {
                    String[] e = edgesBatch.get(j);
                    sb.append("CREATE EDGE BoM_Edges FROM (SELECT FROM Parts WHERE _key = '").append(e[0])
                      .append("') TO (SELECT FROM Parts WHERE _key = '").append(e[1])
                      .append("') SET qty = ").append(e[2]).append(";\n");
                }
                final String script = sb.toString();
                db.transaction(() -> db.command("sqlscript", script).close());
            }
            assertTrue(db.countType("BoM_Edges", true) > 2000);

            List<Map<String, Object>> kvBatch = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(dsDir + "quality_kv.csv"))) {
                br.readLine();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] t = line.split("\\|");
                    if (!t[0].endsWith("_sf1")) continue;
                    String[] meta = t[1].split(";");
                    Map<String, Object> m = new HashMap<>();
                    m.put("_key", t[0]);
                    m.put("cert", meta[0].split(":")[1]);
                    m.put("score", Integer.parseInt(meta[1].split(":")[1]));
                    kvBatch.add(m);
                }
            }
            for (int i = 0; i < kvBatch.size(); i += 500) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(i + 500, kvBatch.size()); j++) {
                    sb.append("INSERT INTO Quality_KV CONTENT ").append(mapper.writeValueAsString(kvBatch.get(j))).append(";\n");
                }
                final String script = sb.toString();
                db.transaction(() -> db.command("sqlscript", script).close());
            }

            List<Map<String, Object>> telBatch = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(dsDir + "telemetry.json"))) {
                String line;
                int c = 0;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> doc = mapper.readValue(line, Map.class);
                    if (!String.valueOf(doc.get("part_id")).endsWith("_sf1")) continue;
                    doc.put("anomaly", (c % 12 == 0));
                    doc.putIfAbsent("part_name", "Comp_" + c);
                    telBatch.add(doc);
                    c++;
                }
            }
            for (int i = 0; i < telBatch.size(); i += 500) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(i + 500, telBatch.size()); j++) {
                    sb.append("INSERT INTO Telemetry CONTENT ").append(mapper.writeValueAsString(telBatch.get(j))).append(";\n");
                }
                final String script = sb.toString();
                db.transaction(() -> db.command("sqlscript", script).close());
            }

            // 3. WarmUp do BenchmarkEngineArcade
            BenchmarkEngineArcade engine = new BenchmarkEngineArcade();
            engine.warmUpAndHarvest(db);

            // 4. Verificação exaustiva das 10 queries READ (Q1..Q10) e 10 WRITE (W1..W10) do BenchmarkEngineArcade
            String[] readTargets = {
                    "15_sf1", "12_sf1", "800_sf1", "7_sf1", "500_sf1",
                    "500_sf1", "500_sf1", "500_sf1", "799_sf1", "800_sf1"
            };
            for (int q = 0; q < BenchmarkEngineArcade.QUERIES_STRESS_TEST_READ.length; q++) {
                engine.executeReadOperation(db, q, readTargets[q]);
            }

            long edgesBefore = db.countType("BoM_Edges", true);
            java.util.Random rng = new java.util.Random(42);
            String[] writeTargets = {
                    "15_sf1", "12_sf1", "16_sf1", "17_sf1", "805_sf1",
                    "805_sf1", "800_sf1", "500_sf1", "799_sf1", "800_sf1"
            };
            for (int w = 0; w < BenchmarkEngineArcade.QUERIES_WRITE.length; w++) {
                engine.executeWriteOperation(db, w, writeTargets[w], rng);
            }
            assertEquals(edgesBefore + 1, db.countType("BoM_Edges", true));

            engine.limparDadosTemporarios(db);
            assertEquals(edgesBefore, db.countType("BoM_Edges", true));
            try (ResultSet rs = db.query("sql", "SELECT count(*) AS rem FROM Quality_KV WHERE is_temp = true")) {
                Result r = rs.next();
                assertEquals(0L, ((Number) r.getProperty("rem")).longValue());
            }
        } finally {
            deleteDir(tmpRoot);
        }
    }
}
