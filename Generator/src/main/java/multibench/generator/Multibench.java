package multibench.generator;

import com.github.javafaker.Faker;
import java.io.*;
import java.util.*;

public class Multibench {
    private static final Faker faker = new Faker();
    private static final Random random = new Random();
    
    // Removida a constante fixa BASE_PATH. Agora o caminho é passado por argumento.

    public static void execute(String[] args) {
        if (args.length < 2) {
            System.out.println("Erro interno: Faltam argumentos para o gerador.");
            return;
        }

        String mode = args[0].toLowerCase();
        String folderName = args[1]; // Ex: "sf1_meuteste"
        String targetPath = "./dataset/" + folderName + "/";

        try {
            if (mode.equals("gen")) {
                System.out.println(">>> Modo: GENERATOR (DeepChain SEED)");
                new File(targetPath).mkdirs();
                generateSeed(2000, targetPath); 
            }
            else if (mode.equals("scale")) {
                // Descobrir o fator de escala com base no prefixo (ex: "sf5_teste" -> sf = 5)
                int sf = 1;
                try {
                    String sfPart = folderName.split("_")[0].replace("sf", "");
                    sf = Integer.parseInt(sfPart);
                } catch (Exception e) {
                    System.out.println("-> Aviso: Não foi possível determinar o SF pelo nome da pasta. Usando SF=1 por padrão.");
                }

                System.out.println(">>> Modo: ESCALATOR (Target " + folderName.toUpperCase() + " com SF " + sf + ")");
                new File(targetPath).mkdirs();
                runEscalator(sf, targetPath);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Adicionado o parâmetro basePath aos métodos de escrita
    public static void generateSeed(int partCount, String basePath) throws IOException {
        System.out.println("A iniciar geração de Vértices (Peças)...");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "parts.csv"))) {
            w.write("id|name|material\n");
            for (int i = 0; i < partCount; i++) {
                String material = (i % 15 == 0) ? "Titânio" : faker.commerce().material();
                w.write(i + "|Component_" + i + "|" + material + "\n");
            }
        }

        System.out.println("A gerar Bill of Materials (Grafo Profundo)...");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "bom_edges.csv"))) {
            w.write("from|to|qty\n");
            for (int i = 1; i < partCount; i++) {
                int parent = i - 1; 
                int qty = random.nextInt(5) + 1;
                w.write(i + "|" + parent + "|" + qty + "\n");
                
                if (i > 5 && random.nextDouble() > 0.7) {
                    int extraParent = i - (random.nextInt(4) + 2);
                    w.write(i + "|" + extraParent + "|" + (random.nextInt(3) + 1) + "\n");
                }
            }
        }

        System.out.println("A gerar Telemetria e Anomalias...");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "telemetry.json"))) {
            for (int i = 0; i < partCount; i++) {
                double temp1 = random.nextDouble() * 85;
                double temp2 = (i % 12 == 0) ? 92.0 + random.nextDouble() * 8 : random.nextDouble() * 85;
                
                w.write(String.format(Locale.US,
                        "{\"part_id\": %d, \"sensor_logs\": [{\"ts\": %d, \"v\": %.2f}, {\"ts\": %d, \"v\": %.2f}], \"status\": \"active\"}\n",
                        i, System.currentTimeMillis(), temp1, System.currentTimeMillis() + 1000, temp2
                ));
            }
        }

        System.out.println("A atribuir Certificações (Key-Value)...");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "quality_kv.csv"))) {
            w.write("key|value\n");
            String[] certTypes = {"Tipo A", "Tipo B", "Tipo C", "Não Certificado"};
            for (int i = 0; i < partCount; i++) {
                String cert = (i % 8 == 0) ? "Tipo B" : certTypes[random.nextInt(certTypes.length)];
                String kvValue = String.format("cert:%s;score:%d", cert, random.nextInt(100));
                w.write(i + "|" + kvValue + "\n");
            }
        }
        System.out.println("Seed de Supply Chain concluída com sucesso em: " + basePath);
    }

    private static void runEscalator(int sf, String targetPath) throws IOException {
        // Assume que a seed base está sempre no dataset/sf1_seed/ para poder escalar.
        // Se preferires, podemos simplificar e fazer o escalator gerar os dados de raiz proporcionalmente.
        String seedPath = "./dataset/sf1_seed/";
        File seedDir = new File(seedPath);
        if (!seedDir.exists() || seedDir.list().length == 0) {
            System.out.println("-> Criando a seed base em " + seedPath + " para poder escalar...");
            seedDir.mkdirs();
            generateSeed(2000, seedPath);
        }

        System.out.println("A escalar CSVs (Grafo e KV)...");
        scaleCSV(seedPath + "parts.csv", targetPath + "parts.csv", sf, "|", true, false);
        scaleCSV(seedPath + "bom_edges.csv", targetPath + "bom_edges.csv", sf, "|", true, true);
        scaleCSV(seedPath + "quality_kv.csv", targetPath + "quality_kv.csv", sf, "|", true, false);

        System.out.println("A escalar JSON (Telemetria)...");
        scaleJsonFile(seedPath + "telemetry.json", targetPath + "telemetry.json", sf);
        
        System.out.println("Escalonamento para " + targetPath + " concluído.");
    }

    // (Os teus métodos privados scaleCSV e scaleJsonFile continuam exatamente iguais abaixo...)
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