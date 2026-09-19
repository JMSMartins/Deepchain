package deepchainbench.generator;

import com.github.javafaker.Faker;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Generator_data {

    private static final Faker faker = new Faker();
    private static final Random random = new Random();

    //Lista de nomes reais carregada do JSON
    private static List<String> componentNames = new ArrayList<>();
    private static Map<Integer, String> nameHierarchy = new HashMap<>(); // id -> nome

    // Caminho do dataset de nomes (configurável)
    private static final String COMPONENT_NAMES_JSON = "/source_deepchain/deepchain_seed.json";

    public static void execute(String[] args) {
        if (args.length < 2) {
            System.out.println("Erro interno: Faltam argumentos para o gerador.");
            return;
        }

        String mode = args[0].toLowerCase();
        String folderName = args[1];
        String targetPath = "./dataset/" + folderName + "/";

        try {
            // Carregar nomes reais antes de qualquer operação
            loadComponentNames();

            if (mode.equals("gen")) {
                System.out.println(">>> Modo: GENERATOR (DeepChain SEED)");
                new File(targetPath).mkdirs();
                generateSeed(2000, targetPath);
            } else if (mode.equals("scale")) {
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

    // ALTERADO: NOVO MÉTODO - Carregar nomes do JSON via ClassLoader
    private static void loadComponentNames() throws IOException {
        System.out.println("A carregar dataset de nomes de componentes...");
        System.out.println("📁A procurar no classpath: " + COMPONENT_NAMES_JSON);

        // ALTERADO: Usar ClassLoader para carregar do classpath
        InputStream inputStream = Generator_data.class
                .getResourceAsStream(COMPONENT_NAMES_JSON);

        if (inputStream == null) {
            System.out.println(" AVISO: Ficheiro " + COMPONENT_NAMES_JSON + " não encontrado no classpath!");
            System.out.println("   Verifica se o ficheiro está no mesmo package que Generator_data");
            System.out.println("   Usando nomes genéricos (Component_X) como fallback.");
            return;
        }

        String content = new String(inputStream.readAllBytes());
        JSONObject json = new JSONObject(content);

        // Verificar se tem a estrutura esperada
        if (!json.has("subsistemas")) {
            System.out.println("  AVISO: JSON não tem estrutura 'subsistemas' esperada!");
            System.out.println("   Usando nomes genéricos (Component_X) como fallback.");
            return;
        }

        JSONArray subsistemas = json.getJSONArray("subsistemas");
        int totalNomes = 0;

        for (int i = 0; i < subsistemas.length(); i++) {
            JSONObject sub = subsistemas.getJSONObject(i);
            String subNome = sub.getString("nome");

            JSONArray sistemas = sub.getJSONArray("sistemas");
            for (int j = 0; j < sistemas.length(); j++) {
                JSONObject sys = sistemas.getJSONObject(j);
                String sysNome = sys.getString("nome");

                JSONArray subSistemas = sys.getJSONArray("sub_sistemas");
                for (int k = 0; k < subSistemas.length(); k++) {
                    JSONObject ss = subSistemas.getJSONObject(k);
                    String ssNome = ss.getString("nome");

                    // Adicionar componentes (nível 4)
                    if (ss.has("componentes")) {
                        JSONArray comps = ss.getJSONArray("componentes");
                        for (int l = 0; l < comps.length(); l++) {
                            String nome = comps.getString(l);
                            componentNames.add(nome);
                            totalNomes++;
                            nameHierarchy.put(componentNames.size() - 1, subNome + " > " + sysNome + " > " + ssNome + " > " + nome);
                        }
                    }

                    // Adicionar peças detalhadas (nível 5)
                    if (ss.has("pecas_detalhadas")) {
                        JSONArray detalhes = ss.getJSONArray("pecas_detalhadas");
                        for (int l = 0; l < detalhes.length(); l++) {
                            String nome = detalhes.getString(l);
                            componentNames.add(nome);
                            totalNomes++;
                            nameHierarchy.put(componentNames.size() - 1, subNome + " > " + sysNome + " > " + ssNome + " > " + nome);
                        }
                    }
                }
            }
        }

        System.out.println("✅ Carregados " + totalNomes + " nomes de componentes.");
        System.out.println("   Distribuição:");
        System.out.println("      - Subsistemas: " + subsistemas.length());
        System.out.println("      - Total de nomes únicos: " + componentNames.size());
        if (componentNames.size() > 0) {
            System.out.println("     Exemplos: "
                    + componentNames.get(0) + ", "
                    + (componentNames.size() > 1 ? componentNames.get(1) : "N/A") + ", "
                    + (componentNames.size() > 2 ? componentNames.get(2) : "N/A") + "...");
        }
    }

    // MÉTODO - Obter nome real baseado no índice
    private static String getComponentName(int index) {
        switch (index) {
            case 0:
                return "Base Chassis Principal";
            case 500:
                return "Base Bloco do Motor Central";
            case 800:
                return "Base do Módulo Elétrico de Potência";
            case 1300:
                return "Base Estrutura do Habitáculo";
            case 1600:
                return "Eixo de Suspensão Base";
        }
        if (componentNames.isEmpty()) {
            // Fallback para nomes genéricos
            return "Component_" + index;
        }
        // ALTERADO: Usar módulo para distribuir nomes deterministicamente
        int nameIndex = index % componentNames.size();
        return componentNames.get(nameIndex);
    }

    // MÉTODO - Obter hierarquia completa para logging/debug
    // MÉTODO ALTERADO - Ajustar hierarquia para as raízes
    private static String getComponentHierarchy(int index) {
        switch (index) {
            case 0:
                return "Veículo > Estrutura > Chassis Principal";
            case 500:
                return "Veículo > Propulsão > Bloco do Motor Central";
            case 800:
                return "Veículo > Energia > Módulo Elétrico de Potência";
            case 1300:
                return "Veículo > Interior > Estrutura do Habitáculo";
            case 1600:
                return "Veículo > Dinâmica > Eixo de Suspensão Base";
        }

        if (nameHierarchy.isEmpty()) {
            return "N/A";
        }
        int nameIndex = index % componentNames.size();
        return nameHierarchy.getOrDefault(nameIndex, "Unknown");
    }

    /*
 * ============================================================================
 * NOTA ARQUITETURAL: DESCONEXÃO ENTRE SEMÂNTICA (Nomes) E TOPOLOGIA (Grafo)
 * ============================================================================
 * * Para garantir o stress máximo no benchmark das bases de dados, este gerador 
 * opera em duas dimensões isoladas que apenas partilham o ID da peça:
 * * 1. DIMENSÃO SEMÂNTICA (parts.csv): Os nomes reais e hierarquias textuais 
 * carregados do JSON são atribuídos de forma cíclica (usando módulo %). 
 * O objetivo é popular a BD com strings realistas em vez de dados genéricos, 
 * garantindo volume e variabilidade para testes multi-modelo.
    
 * * 2. DIMENSÃO TOPOLÓGICA (bom_edges.csv): As ligações parent-child e a divisão
 * em "Zonas" (Chassis, Motor, etc.) são construídas de forma puramente 
 * matemática com base em intervalos de IDs numéricos. Isto permite forçar 
 * estruturas de dados específicas (Árvores, Cadeias Lineares, Power-Law) 
 * que testam os limites de recursividade e agregação das bases de dados.
    
 * * CONSEQUÊNCIA: Não existe coerência mecânica propositada. O ID 500 pode ser 
 * estruturalmente o nó raiz do "Motor" no grafo, mas receber o nome semântico 
 * de "Pneu Traseiro". Para efeitos de benchmark de performance, a complexidade 
 * topológica sobrepõe-se à lógica mecânica do mundo real.
 * 
  *Update: Consegui mitigar pelo menos as raízes serem o que se espera, uma base para um certo componente =)
  *  
 * ============================================================================
     */
    public static void generateSeed(int partCount, String basePath) throws IOException {
        System.out.println("A iniciar geração de Vértices (Peças) com nomes reais...");
        System.out.println("Distribuição determinística: " + partCount + " componentes × " + componentNames.size() + " nomes disponíveis");

        // 1. GERAR VÉRTICES (Ciclo descomentado e corrigido)
        // Ele vai inserir nomes reais mas não estruturados porque o intuito nem é ter um mapa real de como se monta um motor.
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "parts.csv"))) {
            w.write("id|name|material|level|hierarchy\n");
            for (int i = 0; i < partCount; i++) {
                String realName = getComponentName(i);
                String material = (i % 15 == 0) ? "Titânio" : faker.commerce().material();
                int level = calculateLevel(i);
                String hierarchy = getComponentHierarchy(i);

                w.write("" + i + "_sf1|" + realName + "|" + material + "|" + level + "|" + hierarchy + "\n");
            }
        }

        System.out.println("A gerar Bill of Materials (ZONAS ISOLADAS)...");
        System.out.println("Estrutura Híbrida: Árvore -> Linear -> Power-Law -> Linear -> Árvore");

        // 2. GERAR ARESTAS (Topologia Híbrida Isolada)
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "bom_edges.csv"))) {
            w.write("from|to|qty|type\n");

            for (int i = 0; i < partCount; i++) {
                // EXCEÇÃO CRÍTICA: Os nós raiz de cada zona não podem ter um "pai" primário
                if (i == 0 || i == 500 || i == 800 || i == 1300 || i == 1600) {
                    continue;
                }

                int parent = 0;

                if (i < 500) {
                    // Zona 1 (Chassis): Árvore balanceada. Raiz: 0
                    parent = ((i - 1) / 4);
                } else if (i < 800) {
                    // Zona 2 (Motor): Cadeia linear pura (Stress máximo). Raiz: 500
                    parent = i - 1;
                } else if (i < 1300) {
                    // Zona 3 (Elétrica): Power-law (Mundo Pequeno). Raiz: 800
                    // Aproximação Zipf rápida: favorece os primeiros nós da zona (Hubs)
                    int diff = i - 800;
                    int offset = (int) (Math.pow(random.nextDouble(), 3) * diff);
                    parent = 800 + offset;
                } else if (i < 1600) {
                    // Zona 4 (Interior): Cadeia linear pura (Stress máximo). Raiz: 1300
                    parent = i - 1;
                } else {
                    // Zona 5 (Suspensão): Árvore balanceada. Raiz: 1600
                    parent = 1600 + ((i - 1600 - 1) / 4);
                }

                int qty = random.nextInt(5) + 1;

                w.write("" + i + "_sf1|" + parent + "_sf1|" + qty + "|primary\n");

                // REDUNDÂNCIAS COM PROTEÇÃO DE ZONA (Boundary Protection)
                if (i > 5 && random.nextDouble() > 0.7) {
                    int extraParent = i - (random.nextInt(4) + 2);

                    // Apenas desenha a redundância se o nó extra pertencer à mesma zona
                    if (extraParent >= 0 && getZoneRoot(i) == getZoneRoot(extraParent)) {

                        w.write("\"" + i + "_sf1\"|\"" + extraParent + "_sf1\"|" + (random.nextInt(3) + 1) + "|redundant\n");
                    }
                }
            }
        }

        System.out.println("A gerar Telemetria e Anomalias...");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "telemetry.json"))) {
            for (int i = 0; i < partCount; i++) {
                double temp1 = random.nextDouble() * 85;
                double temp2 = (i % 12 == 0) ? 92.0 + random.nextDouble() * 8 : random.nextDouble() * 85;
                String realName = getComponentName(i);

                w.write(String.format(Locale.US,
                        "{\"part_id\": \"%d_sf1\", \"part_name\": \"%s\", \"sensor_logs\": [{\"ts\": %d, \"v\": %.2f}, {\"ts\": %d, \"v\": %.2f}], \"status\": \"active\", \"anomaly\": %b}\n",
                        i, realName, System.currentTimeMillis(), temp1, System.currentTimeMillis() + 1000, temp2, (i % 12 == 0)
                ));
            }
        }

        System.out.println("A atribuir Certificações (Key-Value)...");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(basePath + "quality_kv.csv"))) {
            w.write("key|value\n");
            String[] certTypes = {"Tipo A", "Tipo B", "Tipo C", "Não Certificado"};
            for (int i = 0; i < partCount; i++) {
                String cert = (i % 8 == 0) ? "Tipo B" : certTypes[random.nextInt(certTypes.length)];
                int score = (i % 15 == 0) ? random.nextInt(50) + 50 : random.nextInt(100);
                String kvValue = String.format("cert:%s;score:%d", cert, score);
                w.write("" + i + "_sf1|" + kvValue + "\n");
            }
        }

        System.out.println("✅Seed de Supply Chain concluída com sucesso em: " + basePath);
    }

    // MÉTODO AUXILIAR
    private static int getZoneRoot(int i) {
        if (i < 500) {
            return 0;
        }
        if (i < 800) {
            return 500;
        }
        if (i < 1300) {
            return 800;
        }
        if (i < 1600) {
            return 1300;
        }
        return 1600;
    }

    // NOVO MÉTODO - Calcular nível baseado no índice
    private static int calculateLevel(int i) {
        //as percentagens agora estão mal porque adicionei isto para condicionar as raízes pelo menos
        if (i == 0 || i == 500 || i == 800 || i == 1300 || i == 1600) {
            return 1;
        }
        // Distribuição realista: mais componentes nos níveis mais baixos
        if (i < 10) {
            return 1;           // Subsistemas (0.5%)
        }
        if (i < 50) {
            return 2;           // Sistemas (2%)
        }
        if (i < 200) {
            return 3;          // Sub-sistemas (7.5%)
        }
        if (i < 600) {
            return 4;          // Componentes (20%)
        }
        return 5;                       // Peças detalhadas (70%)
    }

    // runEscalator mantido igual, mas com nomes reais
    private static void runEscalator(int sf, String targetPath) throws IOException {
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

        System.out.println("✅ Escalonamento para " + targetPath + " concluído.");
    }

    // scaleCSV com suporte a colunas adicionais
    private static void scaleCSV(String in, String out, int sf, String sep, boolean header, boolean isEdge) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            for (int s = 1; s <= sf; s++) {
                try (BufferedReader br = new BufferedReader(new FileReader(in))) {
                    String line;
                    if (header) {
                        String h = br.readLine();
                        if (s == 1) {
                            pw.println(h);
                        }
                    }
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.split("\\" + sep);
                        // SIMPLES: substituir _sf1 por _sfX
                        parts[0] = parts[0].replace("_sf1", "_sf" + s);
                        if (isEdge && parts.length > 1) {
                            parts[1] = parts[1].replace("_sf1", "_sf" + s);
                        }
                        pw.println(String.join(sep, parts));
                    }
                }
            }
        }
    }

    // scaleJsonFile com nomes reais mantidos
    private static void scaleJsonFile(String in, String out, int sf) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            for (int s = 1; s <= sf; s++) {
                try (BufferedReader br = new BufferedReader(new FileReader(in))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        // ALTERADO: Preservar o nome real durante o scaling
                        String mod = line.replaceAll("\"part_id\":\\s*(\\d+)", "\"part_id\": \"$1_sf" + s + "\"");
                        pw.println(mod);
                    }
                }
            }
        }
    }
}
