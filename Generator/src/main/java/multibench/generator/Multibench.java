package multibench.generator;

import com.github.javafaker.Faker;
import java.io.*;
import java.util.*;
//codigo atual
public class Multibench {
    private static final Faker faker = new Faker();
    private static final Random random = new Random();
    private static final String BASE_PATH = "./dataset/SF1/";

    public static void execute(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java -jar Unibench.jar [gen | scale] [SF_VAL]");
            return;
        }

        String mode = args[0].toLowerCase();

        try {
            if (mode.equals("gen")) {
                System.out.println(">>> Modo: GENERATOR (DeepChain SF1)");
                new File(BASE_PATH).mkdirs();
                generateSeed(2000); // 2000 peças base (Garante no mínimo ~2000 níveis de profundidade)
            }
            else if (mode.equals("scale")) {
                int sf = (args.length > 1) ? Integer.parseInt(args[1]) : 5;
                System.out.println(">>> Modo: ESCALATOR (Target SF" + sf + ")");
                String targetPath = "./dataset/SF" + sf + "/";
                new File(targetPath).mkdirs();
                runEscalator(sf, targetPath);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void generateSeed(int partCount) throws IOException {
        System.out.println("A iniciar geração de Vértices (Peças)...");
        // 1. Peças (Vértices do Grafo)
        try (BufferedWriter w = new BufferedWriter(new FileWriter(BASE_PATH + "parts.csv"))) {
            w.write("id|name|material\n");
            for (int i = 0; i < partCount; i++) {
                // Injetamos especificamente "Titânio" em algumas peças para a Query 6
                String material = (i % 15 == 0) ? "Titânio" : faker.commerce().material();
                w.write(i + "|Component_" + i + "|" + material + "\n");
            }
        }

        System.out.println("A gerar Bill of Materials (Grafo Profundo)...");
        // 2. Bill of Materials (Edges - Hierarquia Profunda Controlada)
        // O Gargalo: Criamos uma árvore linear longa com ramificações ocasionais para testar pilhas de execução
        try (BufferedWriter w = new BufferedWriter(new FileWriter(BASE_PATH + "bom_edges.csv"))) {
            w.write("from|to|qty\n");
            for (int i = 1; i < partCount; i++) {
                // Ligação de Profundidade Linear: O nó atual depende sempre do anterior (ex: 2->1, 3->2)
                int parent = i - 1; 
                int qty = random.nextInt(5) + 1;
                w.write(i + "|" + parent + "|" + qty + "\n");
                
                // Ramificação Estrutural: 30% de probabilidade de depender de um nó mais antigo para criar densidade
                if (i > 5 && random.nextDouble() > 0.7) {
                    int extraParent = i - (random.nextInt(4) + 2); // Liga a nós 2 a 5 níveis atrás
                    w.write(i + "|" + extraParent + "|" + (random.nextInt(3) + 1) + "\n");
                }
            }
        }

        System.out.println("A gerar Telemetria e Anomalias...");
        // 3. Telemetria (Documentos Aninhados)
        try (BufferedWriter w = new BufferedWriter(new FileWriter(BASE_PATH + "telemetry.json"))) {
            for (int i = 0; i < partCount; i++) {
                // Injetar anomalias (Logs >90º) para testar a Query 8 e 10
                double temp1 = random.nextDouble() * 85; // Temperatura normal
                double temp2 = (i % 12 == 0) ? 92.0 + random.nextDouble() * 8 : random.nextDouble() * 85; // Temperatura Crítica
                
                w.write(String.format(Locale.US,
                        "{\"part_id\": %d, \"sensor_logs\": [{\"ts\": %d, \"v\": %.2f}, {\"ts\": %d, \"v\": %.2f}], \"status\": \"active\"}\n",
                        i, System.currentTimeMillis(), temp1, System.currentTimeMillis() + 1000, temp2
                ));
            }
        }

        System.out.println("A atribuir Certificações (Key-Value)...");
        // 4. Quality Ratings (Key-Value Store)
        try (BufferedWriter w = new BufferedWriter(new FileWriter(BASE_PATH + "quality_kv.csv"))) {
            w.write("key|value\n");
            String[] certTypes = {"Tipo A", "Tipo B", "Tipo C", "Não Certificado"};
            for (int i = 0; i < partCount; i++) {
                // Garante variação controlada para os filtros do otimizador
                String cert = (i % 8 == 0) ? "Tipo B" : certTypes[random.nextInt(certTypes.length)];
                String kvValue = String.format("cert:%s;score:%d", cert, random.nextInt(100));
                w.write(i + "|" + kvValue + "\n");
            }
        }
        System.out.println("Seed de Supply Chain concluída com sucesso.");
    }

    private static void runEscalator(int sf, String targetPath) throws IOException {
        System.out.println("A escalar CSVs (Grafo e KV)...");
        // Escalar CSVs (Parts, Edges, KV)
        scaleCSV(BASE_PATH + "parts.csv", targetPath + "parts.csv", sf, "|", true, false);
        scaleCSV(BASE_PATH + "bom_edges.csv", targetPath + "bom_edges.csv", sf, "|", true, true);
        scaleCSV(BASE_PATH + "quality_kv.csv", targetPath + "quality_kv.csv", sf, "|", true, false);

        System.out.println("A escalar JSON (Telemetria)...");
        // Escalar JSON (Telemetry)
        scaleJsonFile(BASE_PATH + "telemetry.json", targetPath + "telemetry.json", sf);
        
        System.out.println("Escalonamento para SF" + sf + " concluído.");
    }

    private static void scaleCSV(String in, String out, int sf, String sep, boolean header, boolean isEdge) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            for (int s = 1; s <= sf; s++) {
                try (BufferedReader br = new BufferedReader(new FileReader(in))) {
                    String line;
                    if (header) {
                        String h = br.readLine();
                        if (s == 1) pw.println(h);
                    }
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.split("\\" + sep);
                        parts[0] = parts[0] + "_sf" + s;
                        if (isEdge && parts.length > 1) {
                            parts[1] = parts[1] + "_sf" + s;
                        }
                        pw.println(String.join(sep, parts));
                    }
                }
            }
        }
    }

    private static void scaleJsonFile(String in, String out, int sf) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            for (int s = 1; s <= sf; s++) {
                try (BufferedReader br = new BufferedReader(new FileReader(in))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String mod = line.replaceAll("\"part_id\":\\s*(\\d+)", "\"part_id\": \"$1_sf" + s + "\"");
                        pw.println(mod);
                    }
                }
            }
        }
    }
}