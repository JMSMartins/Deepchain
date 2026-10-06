# AGENTS.md - Instruções para Agentes Autónomos (Jules)

Este repositório contém o **DeepChainBench**, uma framework de benchmarking HTAP para Bases de Dados Multimodelo.
Para compreenderes a matemática da geração do dataset (mod 15, mod 12, Small-World, Memory Diamonds) e a topologia do grafo, **lê o ficheiro `METHODOLOGY.md` (ou `DOMAIN_LOGIC.md`)**.

Este documento define as regras operacionais para navegares, alterares e testares a codebase em segurança.

## 1. Arquitetura de Software e Packages

O projeto está modularizado para suportar múltiplos SGBDs através de injeção e abstração:

- `deepchainbench.core`: Contém os motores abstratos (`AbstractBenchmarkEngine`), telemetria (`TelemetryEngine`, `LinuxProcTelemetry`) e exportação (`MetricsExporter`).
- `deepchainbench.core.Arango`: Motores e sondas específicas para ArangoDB (Prometheus API).
- `deepchainbench.drivers`: Adaptadores JDBC/Nativos. Ponto de expansão para novos motores (ex: `ArangoDriver`, `PostgresAgeDriver`).
- `deepchainbench.generator`: Onde a magia matemática acontece. Gera CSVs/JSON estocásticos e escalonáveis (`Generator_data`, `GraphGenerator`).
- `deepchainbench.ingestor`: Classes que leem o I/O local e mapeiam o modelo multimodelo físico (ex: `ArangoIngestor`, `PostgresAgeIngestor`).

## 2. Requisitos de Ambiente (Serviços)

Para executares testes de integração na tua sandbox, tens de garantir que os serviços alvo estão a correr e acessíveis em `localhost`:

- **ArangoDB**: Porta `8529` (Credenciais default: `root` / `password`).
- **PostgreSQL (com extensão Apache AGE)**: Porta `5432` (Credenciais default: `postgres` / `password`).

## 3. Comandos de Build e Execução (CRÍTICO: Interface Interativa)

A classe principal `deepchainbench.Main` é conduzida por um menu CLI iterativo através de `Scanner(System.in)`. **Nunca executes a classe `Main` diretamente sem passar os inputs padrão via `echo` ou _piping_**, caso contrário o teu terminal ficará bloqueado indefinidamente.

**Build e Validação Sintática:**
\`\`\`bash
mvn clean compile
\`\`\`

**Estratégia de Teste Autónoma:**
Para testares as tuas alterações, tens duas opções:

1. **Piping para o Main:** Passar as opções do menu por stream. Exemplo para criar Dataset SF1 (Opção 1) e sair (Opção 0):
   \`\`\`bash
   echo -e "1\n1\nTesteAgent\n0\n" | mvn exec:java -Dexec.mainClass="deepchainbench.Main"
   \`\`\`
2. **Scripts Headless (Recomendado):** Sempre que fizeres refactoring a um motor (ex: `PostgresAgeIngestor`), escreve uma classe temporária (ex: `AgentTest.java` nos `Test Packages`) que instancie a classe diretamente sem menus, compilando e correndo com `mvn test` ou invocando diretamente o método pretendido.

## 4. Input e Output (Gestão de Sistema de Ficheiros)

Não assumas caminhos absolutos nas tuas alterações. O DeepChainBench está fortemente acoplado ao diretório local da execução:

- **Inputs/Datasets:** São sempre gerados e lidos a partir de `./dataset/sfX_nome/` (ex: `parts.csv`, `bom_edges.csv`, `telemetry.json`, `quality_kv.csv`).
- **Outputs/Métricas:** Todos os ficheiros CSV de telemetria, logs de execução RFI/HTAP e ficheiros PNG do JFreeChart são exportados para a diretoria `./metrics/`.
- **Regra de Exportação:** Não elimines nem alteres as formatações (Locale.US) no `MetricsExporter`. O separador de casas decimais tem de ser sempre o ponto (`.`) e não a vírgula, para garantir que as pipelines de dados conseguem ler os RFI e latências corretamente.
