package multibench;

import multibench.generator.Multibench;
import multibench.ingestor.ArangoIngestor;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("=== DeepChain Benchmark System ===");
            System.out.println("Uso: java -jar DeepChain.jar [modulo] [argumentos]");
            System.out.println("\nMódulos disponíveis:");
            System.out.println("  datagen gen             -> Gera o seed SF1");
            System.out.println("  datagen scale [SF_VAL]  -> Escala os dados para o SF especificado");
            System.out.println("  arango ingest           -> Executa o Bulk Load no ArangoDB");
            return;
        }

        String modulo = args[0].toLowerCase();

        // Criamos sub-arrays com os argumentos restantes para passar às classes certas
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);

        try {
            switch (modulo) {
                case "datagen":
                    // Chama o teu gerador original
                    Multibench.execute(subArgs); 
                    break;
                    
                case "arango":
                    // Chama o ingestor do Arango
                    ArangoIngestor.run(subArgs);
                    break;
                    
                case "orient":
                    // Próximo passo: Ingestor do OrientDB
                    // OrientIngestor.run(subArgs);
                    break;
                    
                case "postgres":
                    // Próximo passo: Ingestor do PostgreSQL
                    // PostgresIngestor.run(subArgs);
                    break;

                default:
                    System.out.println("Módulo desconhecido: " + modulo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}