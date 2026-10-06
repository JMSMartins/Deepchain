O dataset do DeepChainBench não é um conjunto de dados estático
descarregado da internet, mas sim um ecossistema de dados sintéticos
gerados de forma estritamente determinística através de um algoritmo em
Java.

O seu objetivo principal é simular uma cadeia de abastecimento
industrial (_Supply Chain_) para expor os limites físicos e as falhas
estruturais ocultas nos Sistemas de Gestão de Bases de Dados Multimodelo
(MMDBMS), levando-os ao seu limite de exaustão. Ao contrário dos
benchmarks tradicionais que avaliam a escalabilidade apenas através do
volume horizontal de dados ou de taxas de transferência (_throughput_)
estáveis, esta framework foca-se na complexidade topológica, na
diversidade de estruturas em rede e na colisão semântica de modelos de
dados heterogéneos.

# Arquitetura do Dataset: Os 3 Modelos de Dados e a Colisão Semântica

A arquitetura do dataset unifica três paradigmas de bases de dados
diferentes para modelar a cadeia de abastecimento, forçando uma "colisão
semântica". Isto obriga os motores a traduzir dados entre formatos
distintos dentro da mesma transação ou fluxo de execução de consulta,
penalizando arquiteturas não nativas (a chamada _Wrapper Penalty_).

**Modelo de Dados** **Representação na Cadeia** **Estrutura Técnica**

---

**Grafo (BoM)** A Lista de Materiais (_Bill of Materials_). Mapeia a hierarquia de como as peças e os subcomponentes se ligam para formar um produto final. Vértices (nós) e Arestas (relações direcionais).
**Documento** Telemetria IoT. Os fluxos contínuos de registos capturados por sensores industriais durante o fabrico das peças. Ficheiros semiestruturados no formato JSON (sem esquema rígido).
**Chave-Valor** Metadados e Controlo de Qualidade. Armazena as pontuações e certificações de cada peça. Pares Key-Value (KV) de acesso ultrarrápido.

: Modelos de Dados no DeepChainBench

**Pergunta: O que são dados semiestruturados?**

Retirado de um email de 9 de Maio de 2026 com os professores. Esta
questão encontra-se aqui pois já surgiu dúvida e irá surgir de novo.

Em Multi-model Databases: A New Journeyto Handle the Variety of Data
indica na introdução o seguinte \"As data with different types and
formats are crucial for optimal business decisions, we observethe
substantial increase of demands to analyze and manipulate multi-model
data, including structured, semi-structured, and unstructured data. **In
particular, structured data includes relational,key/value, and graph
data. Semi-structured data commonly refer to XML and JSON
documents.Unstructured data are typically text files, containing dates,
numbers, and facts.\"**

E em seguida explica

\"2.2 Semi-structured Model for XML and JSON Documents The
semi-structured model is based on the idea of representing the data
without explicit and separate definition of its schema. Instead, the
particular pieces of information are interleaved withstructural/semantic
tags that define their structure, nesting, and so on. Such a
representation enables more flexible processing and exchanging of the
data. In the following, we introduce tworepresentative semi-structured
data types: XML and JSON.The Extensible Markup Language (XML) (W3C 2008)
is a human-readable and machine-readablemarkup language. Its format is
textual and based on the exploitation of Unicode to enable thesupport of
various languages. The data are expressed using elements delimited by
tags that cancontain simple text, subelements, or their combination.
Additional information can be stored inattributes of an element. XML is
widely used for the representation of arbitrary data structures suchas
those used in web services. The JSON (JavaScript Object Notation) (Ecma
International 2013)is a human-readable open-standard format. It is based
on the idea of an arbitrary combination ofthree basic data types used in
most programming languages key/value pairs, arrays, and objects.\"

Já no livro A. Singh, Core Concept Of Multi-Model Databases no Capitulo
a referencia a dados semi estruturados: \" Variety is perhaps the most
challenging dimension. It refers to the different forms that data can
take. In the past, data was primarily structured think of a perfectly
organized table of employee information with columns for ID, Name, and
Salary. Modern data is messy and diverse. It can be structured,
semi-structured, or completely unstructured.

Easiest and Simplest Example: Our food delivery app again provides a
perfect illustration of variety.

- Structured Data: The user's account information (User ID, Name,
  Phone Number, saved addresses) could be structured.

- Semi-Structured Data: A restaurant's menu is semi-structured. A
  pizza might have options for crust, size, and a list of toppings,
  while a thali might have a list of included dishes. This doesn't fit
  neatly into rigid columns and is better represented as a flexible
  JSON document.

- Unstructured Data: User reviews ("The biryani was amazing, but the
  delivery was late!") are unstructured text. The photos of food
  uploaded by users are unstructured image data.

- Geospatial Data: The real-time coordinates of the delivery partner
  form a stream of geospatial data.

A single database system that tries to force all these different data
types into one rigid format, like a traditional relational table, will
inevitably fail. It would be like trying to fit a photograph, a song,
and a map into the same small, rectangular box. This is the core
challenge that sets the stage for a new way of thinking about databases.
\"

## Estrutura Hierárquica Dinâmica (5 Níveis)

## A) O Modelo de Documentos e a Injeção de Anomalias IoT

O ficheiro `telemetry.json` armazena registos semiestruturados para cada
componente. O motor é obrigado a invocar o seu interpretador de
documentos interno para efetuar o _parsing_ textual da string JSON,
validar a estrutura do objeto, navegar no array aninhado `sensor_logs` e
extrair o valor térmico. Este processo consome ciclos intensivos de CPU
devido à desserialização em tempo de execução.

## B) O Modelo Chave-Valor (Metadados de Qualidade)

O ficheiro `quality_kv.csv` armazena strings ultracompactas onde o valor
assume um formato serializado personalizado: `cert:Tipo X;score:Y`. O
repositório Chave-Valor foi desenhado para pesquisas de altíssima
velocidade. Contudo, quando integrado num fluxo híbrido, testar
operações KV no meio de uma travessia profunda incorre em penalizações
de contexto caso a base de dados emule esta interface com abstrações
lentas.

# Topologia Híbrida: Zonas Isoladas e Stress Multidimensional

Para avaliar as capacidades holísticas de um motor multi-modelo, os
macro-benchmarks do estado da arte falham ao aplicar apenas topologias
uniformes (como o Fenómeno do Mundo Pequeno / _Small-World_ na ordem de
$\mathcal{O}(\log N)$).

<figure>

</figure>

O DeepChainBench inova ao introduzir uma topologia de **Zonas
Isoladas**. Em vez de uma rede aleatória ou de uma linha única, o Grafo
Acíclico Direcionado (DAG) é segmentado em 5 zonas com arquiteturas
internas estruturalmente divergentes. Isto força os otimizadores a
planear consultas de travessia que passam por árvores balanceadas na
memória local, e imediatamente a seguir sofrem de explosão de paginação
em cadeias lineares extremas:

1. **Zona 1 (Chassis) - Nós 0 a 499:** Árvore balanceada (_Balanced
   Tree_). Raiz no nó 0.

2. **Zona 2 (Motor) - Nós 500 a 799:** Cadeia estritamente linear (Pior
   cenário de _Stack Overflow_). Raiz no nó 500. Profundidade de 299
   níveis.

3. **Zona 3 (Sistema Elétrico) - Nós 800 a 1299:** Distribuição
   _Power-law_ baseada em aproximação Zipf, criando um ambiente de
   Mundo Pequeno e alta centralidade. Raiz no nó 800.

4. **Zona 4 (Interior) - Nós 1300 a 1599:** Segunda cadeia estritamente
   linear para replicar o esforço de memória da Zona 2. Raiz no nó 1300.

5. **Zona 5 (Suspensão) - Nós 1600 a 1999:** Árvore balanceada final.
   Raiz no nó 1600.

Nota sobre a Distribuição Zipf: A Lei de Zipf dita uma distribuição
estatística de Lei de Potência (Power-law), onde um pequeno número de
elementos concentra a grande maioria das ocorrências. No contexto de
grafos, isto traduz-se na formação de \"super-hubs\" nós centrais com um
volume desproporcionalmente elevado de arestas incidentes, gerando um
efeito de \"Mundo Pequeno\" (Small-World). Esta topologia é introduzida
deliberadamente para provocar um stress extremo no planeador de
consultas do SGBD, forçando transições abruptas de cardinalidade e
testando a gestão de memória do motor perante explosões de ramificações
(fan-out) e contenção de concorrência.

# Lógica do Grafo (Bill of Materials)

**Característica** **Valor**

---

Tipo DAG (Grafo Acíclico Direcionado) Híbrido
Nó Raízes (_Roots_) 5 (Nós 0, 500, 800, 1300, 1600)
Nós (Vértices) 2000
Arestas Primárias 1995 (Cadeias de ligação obrigatórias)
Profundidade Máxima 299 (Atingida nas Zonas 2 e 4)

: Características Globais do Grafo (SF1)

Tipo:

: DAG (Grafo Acíclico Direcionado) Híbrido\
 A natureza acíclica do grafo (DAG) é garantida de forma estrita pela
regra de atribuição de progenitores. Para qualquer nó $i$, o nó pai
(_parent_) calculado possui sempre um identificador estritamente
menor do que $i$ (por exemplo, $i - 1$ nas zonas lineares ou
$((i - 1) / 4)$ nas árvores). Como as arestas apontam sempre de um
ID maior para um ID menor, torna-se matematicamente impossível a
formação de ciclos ou caminhos fechados de retorno.

    A designação Híbrido deve-se à coexistência de três topologias
    estruturalmente divergentes mapeadas no mesmo espaço de estados:

    -   **Árvores Balanceadas (Zonas 1 e 5):** Onde cada nó pai se
        ramifica em até 4 nós filhos, minimizando a profundidade lógica.

    -   **Cadeias Lineares Extremas (Zonas 2 e 4):** Onde cada nó possui
        exatamente um único filho sequencial, maximizando a
        profundidade.

    -   **Distribuição Power-law / Zipf (Zona 3):** Onde a probabilidade
        favorece a ligação aos primeiros nós da zona, gerando
        "super-hubs" de alta conectividade.

2\. Nó Raízes (Roots):

: 5 (Nós 0, 500, 800, 1300, 1600)\
 A existência de exatamente 5 raízes decorre de uma condição de
salvaguarda explícita no algoritmo de criação de arestas:

    ``` {.java language="Java"}
    if (i == 0 || i == 500 || i == 800 || i == 1300 || i == 1600) {
        continue;
    }
    ```

    Este bloco interrompe a execução do ciclo de acoplamento para estes
    cinco identificadores específicos, impedindo-os de procurar um nó
    pai. Como nenhum outro nó do grafo pode apontar para a frente (para
    um ID maior) para se converter em pai deles, estes 5 nós permanecem
    isolados no topo. Eles funcionam como os pontos de origem absolutos
    e independentes para cada uma das 5 zonas do benchmark.

3\. Nós (Vértices):

: 2000\
 Este valor representa a cardinalidade total do conjunto de vértices
($|V|$) para a semente base (_Scale Factor_ 1). O ciclo principal do
gerador itera sequencialmente de $i = 0$ até $i < 2000$, escrevendo
uma linha correspondente no ficheiro `parts.csv` para cada passagem.
O resultado é um universo fechado de exatamente 2000 componentes
indexados de 0 a 1999.

4\. Arestas Primárias:

: 1995 (Cadeias de ligação obrigatórias)\
 O número exato de arestas primárias está diretamente correlacionado
com a quantidade total de nós e de raízes através da equação:
$$\text{Arestas Prim} \mathaccent"7013{a} \text{rias} = |V| - \text{Ra} \mathaccent"7013{\imath} \text{zes} = 2000 - 5 = 1995$$
Como cada nó que não seja uma raiz ($2000 - 5 = 1995$ nós) é
obrigado por lei algorítmica a estabelecer exatamente uma ligação
direcionada para um nó pai, o ficheiro `bom_edges.csv` regista
precisamente 1995 linhas catalogadas como _primary_. Esta métrica
contabiliza apenas a "espinha dorsal" estrutural do grafo, excluindo
as arestas do tipo _redundant_, que são opcionais e geradas de forma
estocástica baseada em probabilidade ($30\%$).

5\. Profundidade Máxima:

: 299 (Atingida nas Zonas 2 e 4)\
 A profundidade de um nó num DAG é medida pelo número de arestas no
caminho mais longo desde a sua respetiva raiz. Nas Zonas 2 e 4, a
topologia é estritamente linear, definida pela função de recorrência
_parent_ $= i - 1$.

    Na Zona 2 (Motor): O intervalo de nós compreende $[500, 799]$. A
    raiz está fixa no nó 500 (profundidade 0). O nó 501 aponta para o
    500 (profundidade 1), o nó 502 aponta para o 501 (profundidade 2), e
    assim sucessivamente até ao nó terminal da zona, o 799. A
    profundidade máxima nesta cadeia é dada por: $$799 - 500 = 299$$ Na
    Zona 4 (Interior): O cenário replica-se exatamente no intervalo
    $[1300, 1599]$, onde a raiz é o nó 1300. O nó terminal 1599 atinge
    uma profundidade linear idêntica de: $$1599 - 1300 = 299$$ Esta
    profundidade invulgarmente elevada para benchmarks tradicionais foi
    desenhada deliberadamente para forçar os motores multi-modelo a
    processar longas cadeias de junções recursivas, expondo fragilidades
    na gestão de pilhas de memória (*Stack Overflow*) e na eficiência de
    paginação de disco.

## Regras de Ligação Primária por Zona

A construção do DAG assenta num bloco lógico determinístico que
distribui os vértices consoante o intervalo do seu identificador $i$:

    if (i < 500) {
        // Zona 1 (Arvore)
        parent = ((i - 1) / 4);
    } else if (i < 800) {
        // Zona 2 (Linear Extrema)
        parent = i - 1;
    } else if (i < 1300) {
        // Zona 3 (Power-law / Small World)
        int diff = i - 800;
        int offset = (int) (Math.pow(random.nextDouble(), 3) * diff);
        parent = 800 + offset;
    } else if (i < 1600) {
        // Zona 4 (Linear Extrema)
        parent = i - 1;
    } else {
        // Zona 5 (Arvore)
        parent = 1600 + ((i - 1600 - 1) / 4);
    }

As raízes de cada zona são isoladas através da instrução:

```{.java language="Java"}
if (i == 0 || i == 500 || i == 800 || i == 1300 || i == 1600) {
    continue;
}
```

### Exemplo linear - Zona 2 e Zona 4

O diagrama foca-se num pequeno segmento da Zona 2 (IDs 552 a 555) para
demonstrar três conceitos centrais do teu benchmark:A Cadeia Linear
(Arestas Primárias): Os nós fluem numa linha reta. O nó 555 aponta para
o 554, que aponta para o 553, que aponta para o 552. Esta é a tradução
visual exata da regra parent = i - 1. Não há ramificações (fan-out);
cada componente tem apenas um dependente direto.Arestas Redundantes
(\"Diamantes de Memória\"): A seta tracejada vermelha do nó 555 para o
552 mostra a regra de redundância a atuar com um recuo de $k=3$. Isto
cria uma bifurcação estrutural. O motor da base de dados, ao atravessar
do 555 para baixo, pode chegar ao 552 por dois caminhos: o caminho
sequencial longo (passando pelo 554 e 553) ou o caminho redundante
direto. Se o planeador de consultas da base de dados não for eficiente
(sem memoização), vai acabar por ler e processar o nó 552 (e tudo o que
está abaixo dele) múltiplas vezes, desperdiçando RAM e CPU.A Colisão
Semântica (Multi-modelo): \* Nó 555 (\"Titânio\"): Como 555 é múltiplo
de 15, a regra matemática define-o como Titânio. Ele liga-se a um
registo Key-Value rápido e a um JSON normal.Nó 552 (Exceção Térmica):
Como 552 é múltiplo de 12, a matemática do gerador ativa uma anomalia
térmica no JSON (\> 92°C).Isto força o motor de base de dados a fazer
parsing de documentos JSON pesados e validar pontuações no sistema
Key-Value ao mesmo tempo que navega num grafo profundo, gerando a tal
\"Wrapper Penalty\".2. O Porquê de Usar parent = i - 1 no CódigoNo
contexto de sistemas de bases de dados e grafos, usar parent = i - 1 na
Zona 2 (e na Zona 4) é uma armadilha intencional. Ao contrário da Zona
1, onde a divisão por 4 cria uma árvore equilibrada, a subtração direta
cria uma Linear Extrema.Fazemos isto no código pelas seguintes razões
técnicas:Maximização de Profundidade vs. LarguraAo forçar cada
identificador a ligar-se apenas ao seu antecessor imediato,
transformamos um conjunto de 300 nós (de 500 a 799) numa \"linha reta\"
com exatamente 299 níveis de profundidade. Numa árvore normal, 300 nós
teriam apenas cerca de 4 ou 5 níveis de profundidade.Provocação de Stack
Overflow (Exaustão de Pilha)Muitos Sistemas de Gestão de Bases de Dados
(SGBD) resolvem junções (_joins_) recursivas ou travessias de grafos
usando recursividade na pilha de memória do sistema. Com uma
profundidade de 299 níveis, testas se o motor consegue otimizar a
consulta para um modelo iterativo ou se estoira a pilha de memória de
execução por ter funções recursivas demasiado profundas.Avaliação da
Eficiência de Paginação (I/O)Como os nós estão encadeados 1 a 1, a base
de dados é obrigada a saltar de registo em registo continuamente. Em
cenários reais, se a base de dados armazenar estes nós em páginas de
disco diferentes, saltar sequencialmente 300 vezes força a memória RAM a
carregar e descarregar páginas de disco sem parar (um fenómeno conhecido
por thrashing).Em suma, a regra parent = i - 1 não serve para simular
uma peça de carro realista, serve para ser um teste de esforço
arquitetural. Garante que, se um motor multi-modelo falhar nesta zona, a
culpa não é do volume de dados estáticos, mas sim da incapacidade do
motor de processar caminhos estruturalmente longos de complexidade
$O(N)$.

<figure>

</figure>

### Exemplo Arvore Balanceada Zona 1 e Zona 5

O diagrama foca-se na raiz absoluta do grafo (o nó 0) e na sua
ramificação imediata, demonstrando a expansão horizontal (fan-out) e o
peso das anomalias multi-modelo:Topologia de Ramificação (Árvore
Quaternária): O nó 0 atua como o vértice principal para os nós 1 e 2. O
nó 1, por sua vez, distribui-se para o nó 5 e 6. Esta é uma estrutura em
largura que reflete um processo de montagem real (ex: um chassi que
recebe portas, capô, etc.). Mantém a profundidade da rede muito
curta.Aresta Redundante (Oclusão e Atalhos): A seta tracejada vermelha
ilustra o nó 5 a contornar o seu pai direto (nó 1) e a estabelecer uma
ligação de redundância diretamente com a raiz (nó 0). Isto testa a
capacidade de memoização da base de dados: o planeador de consultas tem
de ser inteligente o suficiente para não entrar em ciclos (loops) e não
carregar o nó 0 para a memória duas vezes ao atravessar caminhos
sobrepostos.A Colisão Semântica Frontal (Nó Raiz): O nó 0 atua como um
\"Super-Nó\" onde todas as regras matemáticas se cruzam na mesma
iteração:Como $0 \pmod{15} = 0$, a propriedade material é definida como
Titânio.Como $0 \pmod{12} = 0$, o sistema injeta um alerta crítico no
telemetry.json associado, marcando \[EXCEÇÃO\] com temperatura superior
a $92^\circ\text{C}$.Como $0 \pmod{8} = 0$, é validado no sistema
Chave-Valor (KV) com a certificação Tipo B.Isto força a base de dados a
realizar joins pesados entre vértices em RAM, parsing de documentos JSON
e validação ultrarrápida no repositório KV, tudo no epicentro topológico
do grafo.2. A Matemática do Código AlgorítmicoPara garantir que a árvore
cresce de forma perfeitamente balanceada e previsível, o gerador utiliza
o mecanismo de truncatura da divisão inteira da linguagem Java.A Lógica
da Zona 1 (A Raiz Inicial):Javaparent = ((i - 1) / 4); Matematicamente,
a atribuição do progenitor é expressa
por:$$P(i) = \left\lfloor \frac{i - 1}{4} \right\rfloor$$Como a divisão
de inteiros em Java descarta a parte decimal, o código agrupa os nós em
conjuntos (blocos) de quatro filhos para cada pai (fan-out = 4):Para os
nós $i \in \{1, 2, 3, 4\}$, o cálculo $\frac{i-1}{4}$ resulta em 0.
Todos apontam para a raiz 0.Para os nós $i \in \{5, 6, 7, 8\}$, o
cálculo resulta em 1. Todos apontam para o nó 1.Isto achata o grafo
garantindo um tempo de travessia logarítmico rápido.A Lógica da Zona 5
(A Raiz Final):Javaparent = 1600 + ((i - 1600 - 1) / 4); O princípio é
rigorosamente o mesmo, mas a matemática é deslocada para o referencial
(offset) da Zona 5, cuja raiz local é o nó
1600:$$P(i) = 1600 + \left\lfloor \frac{i - 1601}{4} \right\rfloor$$Para
calcular o pai de um componente na Zona 5 (por exemplo, o nó 1601), o
algoritmo primeiro subtrai a base 1600 para \"resetar\" a matemática a
zero. Aplica a divisão por 4 para determinar o índice do progenitor no
subgrafo, e depois soma 1600 ao resultado final para repor o
identificador no espaço correto do sistema global.3. O Propósito
Arquitetural (Engenharia de Teste)No âmbito do DeepChainBench, a
existência de Árvores Balanceadas nas pontas do dataset (Zona 1 e Zona 5) cumpre uma função científica crucial: elas atuam como o grupo de
controlo.Linha de Base Inicial: A Zona 1 permite medir a velocidade
máxima de um SGBD multi-modelo quando a estrutura é amigável e otimizada
(como seria de esperar num teste tradicional).Resiliência e Recuperação:
A Zona 5 está localizada no final do grafo propositadamente. O seu
objetivo é testar se, depois de o motor da base de dados ter sido
esgotado pela paginação agressiva das Zonas 2 e 4 e pelo Small-World da
Zona 3, os seus processos internos (RAM libertada e planeador de
consultas) conseguem recuperar a estabilidade e varrer eficientemente
uma árvore saudável.

<figure>

</figure>

### Exemplo Power-law (Zipf)

O diagrama ilustra o epicentro da Zona 3, destacando a atração gravítica
do nó raiz e a forma como a topologia afeta a distribuição das anomalias
multi-modelo:O \"Super-Hub\" Central (Nó 800): Visualmente destacado, o
nó 800 atua como o buraco negro desta zona. Ao contrário da árvore
balanceada (onde o nó 0 tem no máximo 4 filhos), o nó 800 concentra um
volume desproporcional de arestas primárias diretas (como as
provenientes dos nós 801 e 825).Atração Periférica e Redundância (Mundo
Pequeno): O nó 816 tem como progenitor primário o nó 801. Contudo,
devido à altíssima densidade da rede, a regra de redundância
probabilística (aresta tracejada) atira-o de volta para o Hub Central
(nó 800). Esta hiperconectividade reduz drasticamente o número médio de
saltos entre quaisquer dois vértices, caracterizando o fenómeno de Mundo
Pequeno (Small-World).A Colisão Semântica Simultânea: O diagrama
demonstra como a probabilidade matemática aglomera características
pesadas nos nós periféricos atraídos pelo centro:O nó 825 é validado
algoritmicamente como material especial ($825 \pmod{15} = 0$, resultando
em Titânio).O nó 816 sofre um pico de sobrecarga cumulativa: é múltiplo
de 12 ($816 \pmod{12} = 0$), disparando a anomalia térmica no
telemetry.json ($> 92^\circ C$), e é simultaneamente múltiplo de 8
($816 \pmod{8} = 0$), forçando o cruzamento com o metadado ultrarrápido
Tipo B no repositório Chave-Valor.2. A Matemática do Código
AlgorítmicoPara evitar a sobrecarga computacional de geradores
estocásticos puros para grafos scale-free (como o modelo
Barabási-Albert), o algoritmo induz a Lei de Potência (Power-law)
recorrendo a um filtro probabilístico célere ancorado na exponenciação
cúbica.A lógica desdobra-se em três etapas:1. Cálculo da Janela de
Proximidade:Javaint diff = i - 800; A variável diff define o espaço de
estados admissível. Um nó (por exemplo, 1200) tem um diff de 400,
significando que o seu progenitor terá de estar algures entre 800 e
1199.2. Compressão Cúbica de Probabilidade:Javaint offset = (int)
(Math.pow(random.nextDouble(), 3) \* diff); Esta é a fórmula central da
zona. A função random.nextDouble() devolve uma variável contínua
pseudoaleatória uniforme $U \sim \mathcal{U}(0,1)$. Como $U$ é sempre um
valor decimal entre 0 e 1, a sua elevação à terceira potência ($U^3$)
esmaga a distribuição probabilística.Por exemplo, para que o $U^3$ seja
superior a 0.5, o valor sorteado $U$ teria de ser superior a
$\sim 0.79$. A esmagadora maioria dos resultados do bloco Math.pow
tenderá fortemente para um valor muito próximo de zero.3. Atribuição do
Progenitor Centralizado:Javaparent = 800 + offset; Ao multiplicar o
fator esmagado ($U^3$) pelo espaço disponível (diff) e somar a base da
zona (800), o gerador força deterministicamente com que a grande maioria
dos nós eleja como parent os identificadores 800, 801, 802, etc. Quanto
maior o identificador do nó dentro da zona, menor a probabilidade
estatística de ele atrair filhos.3. O Propósito Arquitetural (Engenharia
de Teste)A injeção deliberada da aproximação Zipf na Zona 3 tem como
objetivo testar vetores de falha sistémica completamente distintos das
Zonas 1 e 2:Explosão de Cardinalidade e Fan-out: Enquanto a Zona 2 testa
a profundidade máxima, a Zona 3 testa a largura extrema não balanceada.
O motor do SGBD é forçado a gerir um único nó (o 800) que possui
centenas de filhos simultâneos. Se o motor carregar todos os vértices
filhos para a memória de forma sequencial num array interno não
otimizado, sofrerá uma degradação drástica de tempo de
resposta.Contenção de Concorrência (Lock Contention): Num cenário de
isolamento topológico, se o otimizador tentar resolver os caminhos e
anomalias térmicas de nós como o 816, 825 e os seus vizinhos em
paralelo, todas as _threads_ de execução irão convergir simultaneamente
para o super-hub 800. Isto provoca graves engarrafamentos de leitura na
memória RAM (hotspots), avaliando se a base de dados multi-modelo gere
eficientemente os bloqueios de contenção (_locks_) em áreas altamente
disputadas do grafo.

<figure>

</figure>

## Redundância Ancestral e Boundary Protection

Para simular uma BoM real (onde um componente standardizado aparece em
múltiplos lugares), cerca de 30% das peças originam arestas redundantes.
No entanto, o sistema garante o isolamento topológico através de uma
regra de _Boundary Protection_ -- uma peça só se pode ligar de forma
redundante a um nó ascendente pertencente à mesma raiz topológica:

    if (i > 5 && random.nextDouble() > 0.7) {
        int extraParent = i - (random.nextInt(4) + 2);
        // Boundary Protection: Garantir que ambos pertencem a mesma zona
        if (extraParent >= 0 && getZoneRoot(i) == getZoneRoot(extraParent)) {
            w.write(i + "|" + extraParent + "|" + ... + "|redundant\n");
        }
    }

A existência de **micro-bifurcações sobrepostas** (_diamantes de
memória_) dentro das zonas lineares força os motores a processar o mesmo
subgrafo múltiplas vezes. Sem um planeamento _memoizado_ eficiente,
ocorre uma explosão combinatorial de caminhos.

Este bloco de código é responsável por introduzir complexidade
topológica controlada no _DeepChainBench_, mimetizando o comportamento
de cadeias de abastecimento reais onde componentes padronizados (como
parafusos, cablagens ou conectores) são reutilizados em múltiplos
subsubsistemas. O funcionamento pormenorizado do algoritmo e as suas
implicações na computação de grafos são descritos de seguida:

- **Controlo de Densidade Estocástica
  (`random.nextDouble() > 0.7`):**\
  A expressão avalia uma variável aleatória uniforme contínua no
  intervalo $[0, 1[$. Ao impor um limiar de $0.7$, o algoritmo
  estabelece uma probabilidade fixa de exatamente $30\%$ para a
  tentativa de geração de uma aresta secundária (redundante) para cada
  nó $i$. O requisito $i > 5$ serve como uma salvaguarda para evitar
  exceções de índice negativo durante os primeiros passos do gerador.

- **Janela de Retrocesso Local e Manutenção do DAG
  (`int extraParent = ...`):**\
  A determinação do progenitor redundante baseia-se numa janela de
  recuo estritamente delimitada:
  $$\text{extraParent} = i - k, \quad k \in [2, 5]$$ O parâmetro $k$ é
  obtido dinamicamente através de `random.nextInt(4) + 2`. Dado que
  $k \ge 2$, o identificador do `extraParent` será sempre estritamente
  menor do que o identificador do nó corrente $i$. Esta propriedade
  matemática assegura que a aresta redundante aponta sempre para trás
  na ordem topológica, preservando a natureza acíclica do grafo (DAG)
  e impedindo a introdução de caminhos circulares infinitos.

- **Mecanismo de Proteção de Fronteira (_Boundary Protection_):**\
  A condição fundamental `getZoneRoot(i) == getZoneRoot(extraParent)`
  atua como o garante do isolamento topológico do benchmark. Sem esta
  validação, uma peça pertencente ao Sistema Motor (Zona 2) poderia
  conectar-se de forma redundante a um nó do Chassis (Zona 1). Ao
  obrigar a que ambos os nós partilhem a mesma raiz macro, o algoritmo
  confina as micro-bifurcações ao espaço interno de cada zona,
  permitindo medir o impacto isolado de cada topologia sem
  contaminação cruzada.

- **Indução de Diamantes de Memória e Explosão Combinatorial:**\
  Nas zonas lineares (Zonas 2 e 4), onde a topologia base dita que o
  pai primário é $i - 1$, a inserção de uma aresta redundante para
  $i - k$ (onde $k \ge 2$) cria estruturas geométricas conhecidas como
  **diamantes de memória**. Quando um motor Multi-Modelo (MMDBMS)
  tenta resolver uma consulta de travessia profunda (_deep graph
  traversal_) de forma recursiva descendente, ele é confrontado com
  múltiplos caminhos válidos que convergem exatamente no mesmo
  subgrafo.

   Se o motor de otimização de consultas efetuar uma avaliação ingénua
   sem estratégias de _memoization_ ou _caching_ de caminhos, o sistema
   reavaliará os mesmos nós e os seus respetivos documentos JSON anexos
   de forma repetida. Isto transforma uma complexidade linear numa
   explosão combinatorial de caminhos de ordem exponencial: $$O(2^n)$$
   Esmagando a pilha de execução e forçando o esgotamento dos recursos
   de CPU e paginação de memória RAM.

# Formalização Matemática e Injeção Determinística

O dataset é 100% reprodutível (_seed_ aleatória fixa). A arquitetura
utiliza regras matemáticas puras para injeção de stress.

## Filtros de pruning e Densidade Aritmética (`parts.csv`)

A injeção do atributo material ocorre através de uma operação de módulo:
$$\text{Material} = \begin{cases} \text{``Titânio''} & \text{se } i \equiv 0 \pmod{15} \\ \text{Aleatório} & \text{caso contrário} \end{cases}$$
Com exatamente $\approx 6,67\%$ de peças em Titânio, o otimizador é
testado na sua capacidade de executar _pruning_ precoce perante
consultas com filtros condicionais aplicados no meio de travessias
profundas.

A escolha do módulo 15 não é arbitrária:

- Garante que exatamente 1 em cada 15 peças seja de Titânio, criando
  um _outlier_ estatístico significativo.

- Testa a capacidade do otimizador em lidar com distribuições
  assimétricas onde a cardinalidade estimada difere significativamente
  da realidade.

- O valor 15 foi selecionado empiricamente para equilibrar raridade
  (força o uso de índices) e frequência (evita _full table scans_
  triviais).

Para as restantes peças, o material é gerado pseudoaleatoriamente
através de: $$f_{\text{rand}}(i) = \text{Faker.commerce().material()}$$
Esta função, combinada com a _seed_ fixa do gerador, assegura que:

1. A sequência de materiais é determinística e repetível.

2. A distribuição segue padrões realistas do mundo comercial (ex:
   \"Aço\", \"Alumínio\", \"Plástico\", \"Cobre\").

3. Cada execução produz exatamente o mesmo conjunto de materiais para
   os mesmos índices.

O verdadeiro desafio para o otimizador surge quando este filtro de
material é combinado com:

- A topologia híbrida do grafo (árvore → linear → power-law → linear →
  árvore), que força diferentes estratégias de travessia.

- A estrutura hierárquica (níveis 1-5), onde o filtro pode ser
  aplicado em diferentes profundidades.

- A presença de redundâncias (arestas do tipo `redundant`), que
  complicam a análise de caminhos.

Neste contexto, o _pruning_ precoce torna-se crítico: aplicando o filtro
'material='Titânio'' antes das junções, o otimizador reduz drasticamente
o espaço de busca, mas precisa antecipar corretamente a seletividade de
6,67% para escolher a estratégia ótima.

A implementação em Java reflete esta lógica de forma direta:

    String material = (i % 15 == 0) ? "Titânio" : faker.commerce().material();

Esta linha, executada durante a geração do `parts.csv`, garante que:

- A condição é avaliada no momento da criação do dataset, não durante
  a consulta.

- O atributo é persistido, permitindo que o otimizador trabalhe com
  dados estáticos.

- A reprodutibilidade é mantida através da _seed_ fixa do `Faker`.

Esta interação múltipla cria um cenário realista onde o otimizador
precisa considerar várias dimensões simultaneamente.

## Anomalias Térmicas (`telemetry.json`) e Marcação de Exceções

O gerador injeta ativamente picos térmicos para forçar alertas críticos
e testar a capacidade do sistema em processar dados semiestruturados com
padrões de anomalia bem definidos.

### Formalização Matemática da Anomalia

Para cada peça $i \in \mathbb{N}_0$, o sistema gera dois registos de
temperatura:

$$\mathcal{T}(i) = \{(t_1, v_1), (t_2, v_2)\}$$

onde: $$v_1 = \text{Uniform}(0, 85) \quad \text{(temperatura base)}$$

$$
v_2 = \begin{cases}
\text{Uniform}(92, 100) & \text{se } i \equiv 0 \pmod{12} \\
\text{Uniform}(0, 85) & \text{caso contrário}
\end{cases}
$$

A flag de anomalia é definida como: $$\text{anomaly}(i) = \begin{cases}
\text{true} & \text{se } i \equiv 0 \pmod{12} \\
\text{false} & \text{caso contrário}
\end{cases}$$

### Justificação da Densidade de 8,33%

A escolha do módulo 12 para injeção de anomalias não é casual:

- **Raridade estatística**: $\frac{1}{12} \approx 8,33\%$ é
  suficientemente raro para forçar o sistema a tratar exceções como
  _outliers_, mas frequente o suficiente para evitar que sejam
  ignoradas como _noise_ estatístico.

- **Independência do filtro de material**: O módulo 12 é coprimo com o
  módulo 15 usado para o material ($\text{mdc}(12,15) = 3$), criando
  uma correlação parcial que testa a capacidade do otimizador em lidar
  com múltiplos padrões sobrepostos.

- **Alinhamento temporal**: O valor 12 foi selecionado por analogia a
  ciclos temporais (ex: meses do ano), conferindo uma semântica
  realista à anomalia.

### Desafio ao Processamento de Dados

Esta estratégia de injeção cria dois desafios fundamentais para o
sistema:

1. **Interseção Documento _vs_. Estrutura em Memória:**
   - O `telemetry.json` é armazenado como documento JSON em disco,
     enquanto as peças (`parts.csv`) são carregadas em estruturas em
     RAM.

   - Consultas que cruzam informação térmica com metadados (ex:
     _"peças de Titânio com anomalia térmica"_) forçam o sistema a
     decidir entre:
      1. Carregar todos os documentos JSON para memória (_full
         scan_).

      2. Usar índices parciais sobre os campos de temperatura.

      3. Aplicar _predicate pushdown_ ao nível do sistema de
         ficheiros.

2. **Processamento de Dados Semi-estruturados:**
   - Cada registo JSON contém um _array_ aninhado de sensores,
     forçando o otimizador a lidar com estruturas de dados complexas.

   - A combinação de `part_id` numérico com `part_name` textual cria
     desafios de _type mixing_.

   - O campo `anomaly` booleano permite testes de _predicate
     evaluation_ eficiente.

### Detalhe da Implementação

A lógica de injeção é implementada no código Java como:

    double temp1 = random.nextDouble() * 85;
    double temp2 = (i % 12 == 0) ? 92.0 + random.nextDouble() * 8
                                 : random.nextDouble() * 85;
    // ...
    "anomaly": %b, (i % 12 == 0)

Note-se que:

- A _seed_ fixa do `Random` garante reprodutibilidade.

- O primeiro registo de temperatura nunca contém anomalia, criando um
  _before/after_ que permite testes de deteção de variação.

- O intervalo $[92.0, 100.0]$ foi escolhido por ultrapassar limiares
  típicos de alerta (ex: 90°C), forçando a geração de notificações.

# Mecanismo de Escalonamento do Escalador (runEscalator)

A métrica **RFI (Índice de Consumo de Recursos)** exige a separação
entre esforço volumétrico e algorítmico. O gerador garante isto através
do método `runEscalator`. Quando passamos de _Scale Factor_ 1 (SF1) para
SF10, a profundidade máxima das zonas lineares não passa para 2990. Em
vez disso, o volume cresce replicando estruturas de dimensão idêntica em
universos paralelos de IDs:

$$G_{\text{total}} = G_{\text{sf1}} \cup G_{\text{sf2}} \cup \dots \cup G_{\text{sf10}}$$

Isto permite provar que o colapso de RAM de um SGBD multi-modelo durante
a avaliação é causado exclusivamente pelas incapacidades do motor
transversal (arquitetura e paginação), e não por um esgotamento brutal
do disco.

# Carga de Trabalho de Stress Estruturada (Banco de Consultas)

O fluxo de trabalho analítico do DeepChainBench está estruturado
hierarquicamente em cinco níveis progressivos de complexidade
tecnológica. Esta conceção permite o isolamento de operadores lógicos
primitivos, funcionando como uma ferramenta de diagnóstico para
determinar com precisão o ponto de rutura (_breakdown point_) de cada
motor multi-modelo.

Ao contrário dos _benchmarks_ tradicionais que selecionam nós
aleatórios, este banco de consultas visa matematicamente zonas
topológicas específicas (por exemplo, as cadeias lineares estritas das
Zonas 2 e 4, ou o polo de distribuição Zipf da Zona 3) e apoia-se nas
regras de injeção determinística (como a distribuição de materiais e
anomalias baseada no operador módulo). As 10 consultas planeadas (Q1 a
Q10) são executadas apenas após a ingestão massiva completa e a
estabilização dos índices.

## Nível 1: Acesso Atómico e Integração Simples

Este nível estabelece a linha de base operacional (_baseline_) de
latência estável e o consumo mínimo de _hardware_ do sistema através de
acessos isolados.

### **Consulta 1 (Localização e Certificação de Peças - KV)**

Consulta diretamente um vértice específico que se sabe matematicamente
ser um _outlier_ (por exemplo, $i \pmod{15} = 0$ para "Titânio") e,
simultaneamente, recupera a sua pontuação de qualidade residente no
motor Chave-Valor. Esta consulta testa a eficiência pura do acesso
direto por chave primária com complexidade $O(1)$ e isola a sobrecarga
de contexto base gerada pela transição entre o motor de grafos e o
repositório KV.

Query para AQL (ARANGO DB)

> Problema no escalonamento, ver em codigo como fazer, provavelmente
> detetar o sf e escalonar para todos os sf

    FOR p IN Parts
        FILTER p._key == "15_sf1"
        LET kv = DOCUMENT("Quality_KV", p._key)
        RETURN {
            peca: p._key,
            material: p.material,
            certificacao: kv.cert
        }

Qual é a ação da Query?

Localiza diretamente o nó 15 e vai buscar a sua certificação ao modelo
de chave-valor.

O que o FOR e LET fazem?

Em AQL, o FOR não é um loop de programação normal, mas sim um iterador
de coleções. O LET usa a função nativa DOCUMENT() para fazer um acesso
direto de memória $O(1)$ à coleção quality_kv, sem precisar de percorrer
índices. Equivalente em um simples SQL:

            SELECT p.id, p.material, kv.cert
            FROM PROD.parts p
            JOIN PROD.quality_kv kv ON p.id = kv.id
            WHERE p.id = '15_sf1';

### **Consulta 2 (Histórico de Telemetria - Documento):**

Tem como alvo componentes específicos programados deterministicamente
para conter exceções térmicas severas ($i \pmod{12} = 0$). Recupera e
ordena cronologicamente os registos de temperatura. O objetivo é testar
o desempenho de varrimento (_scanning_) de documentos semiestruturados,
a manipulação de matrizes JSON de grande escala e a eficácia do
analisador (_parser_) de documentos antes de o integrar em travessias de
grafos.

> Problema no escalonamento, ver em codigo como fazer, provavelmente
> detetar o sf e escalonar para todos os sf

        FOR t IN Telemetry
        FILTER t.part_id == "12_sf1"
        FOR log IN t.sensor_logs
            SORT log.ts ASC
            RETURN {
                key: t._key,
                nome: t.part_name,
                tempo: log.ts,
                temp: log.v,
                anomalia: t.anomaly
        }

Ação da Query?

Procura o documento JSON do nó 12, entra no array interno de sensores e
devolve os registos ordenados por tempo.

O que o FOR aninhado faz?

O segundo FOR log IN t.sensor_logs atua como um mecanismo de unwind
(desdobramento). Ele pega num único documento JSON e transforma o seu
array interno em múltiplas linhas de saída.

Equivalente em SQL? Equivale mais ou menos a:

        SELECT t.part_id, t.part_name, log.ts AS tempo, log.v AS temp, t.anomaly
        FROM PROD.telemetry t
        CROSS JOIN UNNEST(t.sensor_logs) AS log
        WHERE t.part_id = '12_sf1'
        ORDER BY log.ts ASC;

## Nível 2: Travessia de Grafos e Filtros Cruzados

Este nível introduz a primeira variação topológica com lógica híbrida,
avaliando travessias de salto único (_1-hop_) em zonas estruturalmente
divergentes.

### **Consulta 3 (Stress de Ramificação / _Fan-out_ - Grafo)**

> Problema no escalonamento, ver em codigo como fazer, provavelmente
> detetar o sf e escalonar para todos os sf

        FOR root IN ["0_sf1", "800_sf1"]
        FOR v IN 1..1 INBOUND CONCAT("Parts/", root) BoM_Edges
            RETURN {
                raiz: root,
                filho: v._key
            }

Lista todos os nós filhos imediatos (_1-hop_), comparando dois cenários
de execução distintos: uma travessia com início no Nó 0 (Raiz da Zona 1,
uma árvore balanceada com um _fan-out_ máximo de 4) _versus_ o Nó 800
(Raiz da Zona 3, um polo _Power-law_ de Zipf com conectividade massiva e
centralizada). Mede a suscetibilidade do motor à contenção de bloqueios
(_lock contention_) e à alocação massiva de memória em matrizes durante
travessias de arestas de saída de alta densidade. Ação da Query?

Compara a árvore balanceada (raiz 0) com o polo Zipf/Power-law (raiz
800), saltando apenas um nível de profundidade (1..1).

Qual é a ação da Query?

Compara a árvore balanceada (raiz 0) com o polo Zipf/Power-law (raiz
800), saltando apenas um nível de profundidade (`1..1`).

O que o FOR v IN 1..1 INBOUND faz?

Inicia a travessia nativa de grafos do ArangoDB. Lê o nó inicial e usa o
índice de arestas para encontrar todos os nós que apontam para ele (daí
o `INBOUND`).

Equivalente em um simples SQL:

    SELECT root.id AS raiz, v.id AS filho
    FROM PROD.parts root
    JOIN PROD.bom_edges e ON root.id = e.to_id
    JOIN PROD.parts v ON e.from_id = v.id
    WHERE root.id IN ('0_sf1', '800_sf1');

### **Consulta 4 (Filtro de Qualidade em Subcomponentes - Grafo + KV)**

Identifica os componentes diretos (_1-hop_) de uma dada raiz que possuem
simultaneamente uma certificação Chave-Valor abaixo de um limiar
predefinido. Avalia a inteligência do otimizador integrado ao aplicar
filtros analíticos baseados em chaves externas: determinando se opta por
resolver primeiro o grafo e filtrar via KV, ou se consulta primeiro as
chaves e deduz a conectividade dos nós a partir daí.

Query para AQL (ARANGO DB)

> Problema no escalonamento, ver em codigo como fazer, provavelmente
> detetar o sf e escalonar para todos os sf

    FOR root IN ["0_sf1", "800_sf1"]
        FOR v IN 1..1 INBOUND CONCAT("Parts/", root) BoM_Edges
            LET kv = DOCUMENT("Quality_KV", v._key)
            FILTER kv.cert == "Tipo B"
            RETURN {
                raiz: root,
                filho: v._key,
                material: v.material,
                certificacao: kv.cert
            }

Qual é a ação da Query?

Navega um nível de profundidade a partir da raiz, extrai o identificador
do filho e faz uma pesquisa de tempo constante $O(1)$ no modelo
Chave-Valor para validar a certificação antes de devolver o resultado.

O que o LET kv = DOCUMENT faz neste contexto?

Atua como o ponto de colisão multi-modelo, forçando a ponte em tempo
real entre a memória de grafos (onde estão os vértices) e o
armazenamento ultrarrápido de chaves (onde estão os metadados de
qualidade).

Equivalente em um simples SQL:

    SELECT v.id, v.material, kv.cert
    FROM PROD.parts root
    JOIN PROD.bom_edges e ON root.id = e.to_id
    JOIN PROD.parts v ON e.from_id = v.id
    JOIN PROD.quality_kv kv ON v.id = kv.id
    WHERE root.id IN ('0_sf1', '800_sf1') AND kv.cert = 'Tipo B';

## Nível 3: Recursividade Profunda (O Grande Gargalo)

Este nível foi especificamente concebido para atacar a pilha de
execução, acionar vulnerabilidades de esgotamento de memória (_Stack
Overflow_) e gerar picos significativos na latência de cauda (P99).

### **Consulta 5 (Travessia Linear Extrema - Grafo Profundo)**

Tem como alvo exclusivo o Nó 500 (Raiz da Zona 2) e o Nó 1300 (Raiz da
Zona 4). Força o motor a descer sem restrições através das cadeias
lineares controladas (regidas pela regra $\text{parent} = i - 1$),
navegando efetivamente por 299 níveis de profundidade sequenciais. Isto
avalia estritamente o comportamento da recursividade pura, a eficiência
da travessia em profundidade $O(N)$ e a suscetibilidade do motor a
paginação de disco severa (_thrashing_).

Query para AQL (ARANGO DB)

> Problema no escalonamento, ver em codigo como fazer, provavelmente
> detetar o sf e escalonar para todos os sf

    FOR v IN 1..299 INBOUND "Parts/500_sf1" BoM_Edges
        RETURN v._key

Qual é a ação da Query?

Inicia no nó 500 e segue o rasto de arestas ininterruptamente durante
299 iterações consecutivas para testar os limites do limite da pilha de
chamadas (_Call Stack_).

O que o 1..299 faz?

Define de forma estrita o intervalo de profundidade (mínima de 1, máxima
de 299). Se o motor for incapaz de converter a travessia numa iteração
otimizada, esta instrução causará falhas de memória por excesso de
chamadas recursivas.

Equivalente em um simples SQL (Common Table Expressions - CTE):

    WITH RECURSIVE deep_traverse AS (
        SELECT from_id, to_id, 1 as depth
        FROM PROD.bom_edges
        WHERE to_id = '500_sf1'
        UNION ALL
        SELECT e.from_id, e.to_id, dt.depth + 1
        FROM PROD.bom_edges e
        JOIN deep_traverse dt ON e.to_id = dt.from_id
        WHERE dt.depth < 299
    )
    SELECT from_id FROM deep_traverse;

### **Consulta 6 (_Pruning_ Precoce na Linhagem - Grafo + Atributos)**

Percorre as cadeias estruturais profundas das Zonas 2 e 4, extraindo
apenas os subcomponentes cujos atributos internos correspondam
estritamente à _string_ "Titânio" ($i \pmod{15} = 0$). Testa se o
otimizador do sistema antecipa corretamente a seletividade de 6,67% e
executa a filtragem lógica _durante_ o processo de travessia (_predicate
pushdown_), ou se comete o erro fatal de carregar todos os 299 nós para
a memória RAM antes de aplicar a restrição.

Query para AQL (ARANGO DB)

    FOR v IN 1..299 INBOUND "Parts/500_sf1" BoM_Edges
        FILTER v.material == "Titânio"
        RETURN {
            peca: v._key,
            material: v.material
        }

Qual é a ação da Query?

Navega em profundidade, mas impõe uma condição estrita nos atributos
internos do nó, testando se o planeador é capaz de descartar caminhos
mortos cedo no processo de travessia.

O que o FILTER faz no contexto de grafos?

Atua como o teste decisivo de otimização de consulta. Motores
ineficientes executam o `FOR` completo e filtram no final. Motores
otimizados embutem o `FILTER` no iterador da aresta, poupando I/O.

Equivalente em um simples SQL:

    WITH RECURSIVE deep_traverse AS (
        SELECT from_id, to_id
        FROM PROD.bom_edges
        WHERE to_id = '500_sf1'
        UNION ALL
        SELECT e.from_id, e.to_id
        FROM PROD.bom_edges e
        JOIN deep_traverse dt ON e.to_id = dt.from_id
    )
    SELECT dt.from_id, p.material
    FROM deep_traverse dt
    JOIN PROD.parts p ON dt.from_id = p.id
    WHERE p.material = 'Titânio';

## Nível 4: Analítica Multi-modelo Complexa

O Nível 4 representa o ponto crítico em que os três modelos de dados
convergem num fluxo único e unificado, forçando colisões semânticas e a
saturação máxima da CPU.

### **Consulta 7 (Média de Telemetria Recursiva - Grafo + Documento)**

Calcula a média aritmética das temperaturas registadas nos documentos
JSON de todos os componentes que integram recursivamente a estrutura
linear extrema da Zona 2 (Nó 500). Avalia a junção (_join_) multi-modelo
mais dispendiosa do _benchmark_: exige uma navegação algorítmica
profunda enquanto força simultaneamente a desserialização de JSON em
tempo de execução a cada um dos 299 saltos.

Query para AQL (ARANGO DB)

    LET total_temps = (
        FOR v IN 1..299 INBOUND "Parts/500_sf1" BoM_Edges
            FOR t IN Telemetry
                FILTER t.part_id == v._key
                FOR log IN t.sensor_logs
                    RETURN log.v
    )
    RETURN AVERAGE(total_temps)

Qual é a ação da Query?

Executa uma travessia intensiva combinada com descompressão de
estruturas complexas para computação matemática de agregação global.

O que a estrutura aninhada de FOR e LET faz?

A _subquery_ interior (alocada à variável `total_temps`) constrói um
_array_ gigantesco em memória que cruza a travessia do grafo com o
desdobramento da matriz JSON (`sensor_logs`), forçando um _overhead_
brutal no _Garbage Collector_.

Equivalente em um simples SQL:

    WITH RECURSIVE deep_traverse AS (
        SELECT from_id, to_id
        FROM PROD.bom_edges
        WHERE to_id = '500_sf1'
        UNION ALL
        SELECT e.from_id, e.to_id
        FROM PROD.bom_edges e
        JOIN deep_traverse dt ON e.to_id = dt.from_id
    )
    SELECT AVG(log.v) as media_temperatura
    FROM deep_traverse dt
    JOIN PROD.telemetry t ON dt.from_id = t.part_id
    CROSS JOIN UNNEST(t.sensor_logs) AS log;

### **Consulta 8 (A Colisão Semântica - Grafo + KV + Documento)**

Efetua um varrimento da topologia para localizar sobreposições
estruturais onde todas as injeções sintéticas colidem: componentes que
são feitos de "Titânio", apresentam registos de temperatura perigosos
($> 92.0^\circ\text{C}$) no modelo de documentos, e possuem uma _string_
de certificação específica no motor Chave-Valor. Esta consulta avalia a
_Wrapper Penalty_, expondo a degradação arquitetural quando um sistema
executa pesadas trocas de contexto cruzado entre modelos a elevadas
profundidades recursivas.

Query para AQL (ARANGO DB)

    FOR root IN ["500_sf1", "1300_sf1"]
        FOR p IN 1..299 INBOUND CONCAT("Parts/", root) BoM_Edges
            FILTER p.material == "Titânio"
            LET kv = DOCUMENT("Quality_KV", p._key)
            FILTER kv.cert == "Tipo B"
            FOR t IN Telemetry
                FILTER t.part_id == p._key
                FILTER t.anomaly == true
                FOR log IN t.sensor_logs
                    FILTER log.v > 92.0
                    RETURN {
                        raiz: root,
                        peca: p._key,
                        certificacao: kv.cert,
                        temperatura: log.v
                    }

Qual é a ação da Query?

Identifica os casos precisos e escassos onde as três restrições
matemáticas (Módulo 15, Módulo 12 e Módulo 8) intersetam, avaliando qual
é o custo de penalização ao saltar ininterruptamente entre partições de
dados que possuem linguagens e motores de armazenamento internos
distintos.

O que a cadeia de FILTER e LET sucessiva faz?

Força um \"funil\" analítico extremamente pesado que cruza vértices
(Parts), dicionários (KV) e documentos com _arrays_ (Telemetry).

Equivalente em um simples SQL:

    SELECT p.id, kv.cert, log.v
    FROM PROD.parts p
    JOIN PROD.quality_kv kv ON p.id = kv.id
    JOIN PROD.telemetry t ON p.id = t.part_id
    CROSS JOIN UNNEST(t.sensor_logs) AS log
    WHERE p.material = 'Titânio'
      AND kv.cert = 'Tipo B'
      AND t.anomaly = true
      AND log.v > 92.0;

## Nível 5: Inteligência de Rede e Impacto

O Nível 5 desafia as capacidades de agregação analítica e a retenção de
caminhos em memória (_memoization_) ao explorar arestas redundantes e ao
inverter a navegação topológica.

### **Consulta 9 (Análise de Impacto de Falhas - Grafo Inverso)**

Inicia-se nos nós folha absolutos das cadeias extremas (por exemplo, o
Nó 799 na Zona 2) e traça o caminho de forma ascendente para identificar
as raízes dos produtos finais. Testa a eficiência da travessia
estrutural na direção inversa (_bottom-up_), avaliando se a arquitetura
de indexação física das arestas possui capacidades bidirecionais nativas
sem incorrer em pesadas penalizações de desempenho.

Query para AQL (ARANGO DB)

> Problema no escalonamento, ver em codigo como fazer, provavelmente
> detetar o sf e escalonar para todos os sf

    FOR v IN 1..299 OUTBOUND "Parts/799_sf1" BoM_Edges
        RETURN v._key

Qual é a ação da Query?

Efetua a navegação contrária ao fluxo estabelecido (subindo da peça
folha de volta à montagem mestre).

O que o OUTBOUND faz neste cenário?

Força o motor a usar o seu índice reverso. Em grafos direcionais
nativos, navegar do pai para o filho (`INBOUND`) costuma estar
otimizado, mas navegar na direção inversa costuma estar sub-dimensionado
se o modelo de persistência não duplicar a indexação das arestas.

Equivalente em um simples SQL:

    WITH RECURSIVE bottom_up AS (
        SELECT from_id, to_id
        FROM PROD.bom_edges
        WHERE from_id = '799_sf1'
        UNION ALL
        SELECT e.from_id, e.to_id
        FROM PROD.bom_edges e
        JOIN bottom_up bu ON e.from_id = bu.to_id
    )
    SELECT to_id FROM bottom_up;

### **Consulta 10 (Agregação de Caminhos Redundantes - Grafo + Analítica)**

Navega pela Zona 3 (onde as arestas ancestrais redundantes a 30% criam
"diamantes de memória" sobrepostos) para calcular os erros térmicos
cumulativos de ramos específicos. Testa as capacidades de _memoization_
do motor: se o planeador de consultas não conseguir colocar em cache, de
forma inteligente, os caminhos sobrepostos visitados anteriormente, a
redundância causará uma explosão combinatória exponencial $O(2^n)$,
provocando o colapso da pilha de execução.

Query para AQL (ARANGO DB)

    FOR v, e, p IN 1..10 INBOUND "Parts/800_sf1" BoM_Edges
        OPTIONS { uniqueVertices: "none", bfs: false }
        FOR t IN Telemetry
        FILTER t.part_id == v._key
        FILTER t.anomaly == true
        RETURN {
            caminho: p.vertices[*]._key,
            anomalia: t.anomaly
    }

Qual é a ação da Query?

Atravessa as regiões mais densas de _Small-World_, garantindo
matematicamente a colisão com dezenas de referências redundantes para as
mesmas peças, unificando a saída num registo fotográfico do rasto
efetuado (`p.vertices[*]._key`).

O que OPTIONS { uniqueVertices: \"none\" } faz?

Isto retira os estabilizadores da bicicleta. Normalmente, os motores de
grafos previnem ciclos e explosões recusando-se a visitar o mesmo
vértice duas vezes na mesma consulta. Ao definir isto como 'none',
obrigamos o motor a reprocessar cada caminho bifurcado, garantindo que o
_hardware_ tem de resolver os diamantes de memória de forma bruta se não
tiver cache interna.

Equivalente em um simples SQL:

    WITH RECURSIVE redundance_traverse AS (
        SELECT from_id, to_id, CAST(from_id AS VARCHAR) as path
        FROM PROD.bom_edges
        WHERE to_id = '800_sf1'
        UNION ALL
        SELECT e.from_id, e.to_id, CONCAT(rt.path, '->', CAST(e.from_id AS VARCHAR))
        FROM PROD.bom_edges e
        JOIN redundance_traverse rt ON e.to_id = rt.from_id
    )
    SELECT rt.path, t.anomaly
    FROM redundance_traverse rt
    JOIN PROD.telemetry t ON rt.from_id = t.part_id
    WHERE t.anomaly = true;

# Anexos

## Notas e Provas

Fundamento do Uso do Power-law e de onde foi visto
https://ar5iv.labs.arxiv.org/html/2602.07612

Porque 2000 vertices? https://pacechallenge.org/2023/properties/

Porquê 299 em profundidade? Além de ser apenas 15% do grafo no geral não
dominam todo a base de dados. Depois em bases de dados, existem limites
explícitos de recursão (ex: 256 chamadas em alguns motores). Isto
justifica a escolha de um número que ultrapassa esses limites para
forçar otimizações (como tail recursion ou abordagens iterativas) e
testar a robustez do sistema.
https://github.com/389ds/389-ds-base/pull/7019

Porquê o titanio ser mod(15) com 6.67%? Não tenho prova em si que isto é
o que constitui a industria a logica é que é um rácio que torna o
material \"Titânio\" suficientemente raro (cerca de 1 em cada 15 peças)
para ser considerado um outlier que força o motor a usar índices e a
fazer pruning precoce, mas não tão raro que seja um evento estatístico
irrelevante.

O que é Pruning Precoce? O pruning precoce, também conhecido na
literatura como predicate pushdown, é uma técnica fundamental de
otimização de consultas em bases de dados que consiste em aplicar os
filtros e condições de uma consulta o mais cedo possível no plano de
execução, em vez de os aplicar apenas no final do processamento. Esta
abordagem permite reduzir drasticamente o volume de dados que precisa de
ser processado nas fases seguintes da consulta, como junções ou
travessias de grafos. Por exemplo, numa consulta que procura peças de
Titânio num grafo com 2000 nós, o pruning precoce aplica primeiro o
filtro do material, reduzindo o conjunto de dados para aproximadamente
133 nós (apenas 6,67% do total), e só depois executa a travessia do
grafo sobre esse subconjunto muito mais pequeno. Sem esta otimização, o
sistema seria forçado a navegar pelo grafo inteiro e só no final
descartaria 93,33% dos dados, resultando num processamento extremamente
ineficiente. No contexto do DeepChainBench, esta técnica é
particularmente relevante porque testa se o otimizador do sistema
multi-modelo consegue identificar corretamente a seletividade dos
filtros e decidir quando aplicar o pruning precoce, especialmente quando
combinado com topologias complexas como cadeias lineares de 299 níveis
de profundidade ou arestas redundantes que criam explosão combinatória
de caminhos.

Porque RFI? Justifica?

-\> Porque é que escolheste Memória (RAM) e não CPU?

Em bases de dados relacionais normais, o CPU é o rei. Mas em bases de
dados de grafos e modelos recursivos, a memória esgota-se muito antes do
CPU.A Justificação: Quando o motor da base de dados desce na tua Zona 2
(Cadeia Linear de 299 níveis), ele usa algoritmos de Depth-First Search
(DFS) ou Breadth-First Search (BFS). O DFS obriga o motor a guardar o
estado de cada nó na pilha de execução (Call Stack). O BFS obriga o
motor a criar filas (Queues) gigantes na memória Heap. Além disso, as
tuas \"arestas redundantes\" (os diamantes de memória) criam caminhos
sobrepostos. Se o motor não tiver cache inteligente (memoization), ele
vai duplicar e fragmentar a memória até estoirar (Stack Overflow),
enquanto o CPU até pode estar relaxado à espera da RAM. Logo, a Variação
de RAM ($\Delta RAM$) é o indicador mais precoce de falha estrutural.

-\> Porque é que escolheste Profundidade ($\Delta d$) e não Volume
(GBs/Linhas)?Nos benchmarks tradicionais (como TPC-H), mede-se o consumo
face aos Gigabytes ingeridos (Volume). Mas o teu benchmark quer combater
o \"Small-world phenomenon\" (redes rasas).A Justificação: No
DeepChainBench, o problema não é o tamanho da base de dados (afinal, a
tua seed tem apenas 2000 nós no SF1, o que é minúsculo em volume
físico). O problema é a forma dos dados. O que penaliza o motor é descer
299 níveis lógicos. Normalizar a fórmula pela profundidade ($\Delta d$)
em vez de volume prova que é a complexidade topológica que está a matar
a base de dados, e não o simples facto de a tabela ser grande.

-\> Como é que se interpreta o RFI?O revisor pediu para explicares como
é que os cientistas devem ler este valor.RFI Constante/Linear: Significa
que por cada nível que o grafo desce, o motor gasta a mesma quantidade
de RAM. Isto é o \"Santo Graal\". Prova que o motor otimizou a query,
que libertou memória a cada passo ou usou iteradores eficientes.RFI
Exponencial/Derivada Crescente: Significa que ao nível 10 gasta \"X\"
RAM, mas ao nível 50 gasta \"100X\". Isto alerta imediatamente para
Wrapper Penalty (acumulação de objetos JSON pesados e traduções
Key-Value não limpas pelo Garbage Collector) ou para a tal explosão
combinatória dos caminhos redundantes.

## Prompt para criar deepchain seed.json

O prompt usado numa LLM para a criação de uma seed para a industria
automovel, usado como base para depois os elementos do dataset terem
nomes e ligações sem ser apenas componente 1 mas sim com um nome.

    Preciso que geres um ficheiro JSON detalhado com uma Bill of Materials (BoM) para a indústria automóvel. Este JSON será usado como dataset de nomes de componentes para um benchmark de bases de dados multi-modelo chamado DeepChainBench.

    Estrutura do JSON

    O JSON deve ter a seguinte estrutura:

    ```json
    {
      "metadata": {
        "nome": "Dataset de Componentes Automóveis para DeepChainBench",
        "versao": "1.0",
        "total_componentes": 2800,
        "total_por_nivel": {
          "nivel_1_subsistemas": 6,
          "nivel_2_sistemas": 18,
          "nivel_3_sub_sistemas": 42,
          "nivel_4_componentes": 421,
          "nivel_5_pecas_detalhadas": 2360
        }
      },
      "subsistemas": [
        {
          "id": "PT",
          "nome": "Powertrain",
          "sistemas": [
            {
              "id": "ENG",
              "nome": "Engine",
              "sub_sistemas": [
                {
                  "id": "CYL_HEAD",
                  "nome": "Cylinder Head",
                  "componentes": [
                    "Intake Valve",
                    "Exhaust Valve",
                    "Valve Guide",
                    "Valve Seat"
                  ],
                  "pecas_detalhadas": [
                    "Valve Spring",
                    "Valve Retainer",
                    "Valve Stem Seal",
                    "Valve Keeper"
                  ]
                }
              ]
            }
          ]
        }
      ]
    }

    Regras para geração

    1. Subsistemas (Nível 1 - 6 itens)

    Cria 6 subsistemas principais de um automóvel:

    Powertrain (PT)

    Chassis (CH)

    Interior (IN)

    Electrical System (EL)

    Body (BO)

    Suspension & Steering (SS) [ou combina com Chassis]

    2. Sistemas (Nível 2 - ~18 itens)

    Para cada subsistema, cria 2-4 sistemas. Exemplos:

    Powertrain: Engine, Transmission, Fuel System, Exhaust

    Chassis: Frame, Suspension, Steering, Brakes

    Interior: Dashboard, Seats, HVAC, Airbags

    Electrical: Battery, Lighting, ECU/Sensors, Wiring

    Body: Panels, Glass, Trim, Doors

    3. Sub-sistemas (Nível 3 - ~42 itens)

    Para cada sistema, cria 2-5 sub-sistemas. Exemplos para Engine:

    Cylinder Head, Cylinder Block, Pistons, Connecting Rods, Crankshaft, Camshaft, Timing System, Valve Train, Lubrication System, Cooling System

    4. Componentes (Nível 4 - ~420 itens)

    Para cada sub-sistema, cria 3-10 componentes. Exemplos para Cylinder Head:

    Intake Valve, Exhaust Valve, Valve Guide, Valve Seat, Camshaft Bearing, Spark Plug Hole, Coolant Jacket, Oil Drain Passage

    5. Peças Detalhadas (Nível 5 - ~2360 itens)

    Para cada sub-sistema, cria 5-20 peças detalhadas. Exemplos para Cylinder Head:

    Valve Spring, Valve Retainer, Valve Stem Seal, Valve Keeper, Valve Lash Adjuster, Valve Spring Seat, Valve Rotator, Valve Collet, Valve Lock, Valve Stem Cap

    Requisitos Específicos

    IDs únicos: Cada subsistema, sistema e sub-sistema deve ter um ID único (ex: PT, ENG, CYL_HEAD)

    Nomes realistas: Usa terminologia da indústria automóvel, nomes em inglês (pois são mais universais)

    Hierarquia clara: Mantém a relação pai-filho consistente

    Quantidades: Deve ter aproximadamente:

    6 subsistemas (N1)

    18 sistemas (N2)

    42 sub-sistemas (N3)

    420 componentes (N4)

    2360 peças detalhadas (N5)

    Total: ~2847 itens

    Variedade: Inclui diferentes tipos de componentes:

    Mecânicos (parafusos, molas, engrenagens)

    Elétricos (sensores, atuadores, cabos)

    Eletrónicos (ECUs, módulos, PCBs)

    Hidráulicos (bombas, válvulas, tubos)

    Térmicos (radiadores, permutadores)

    Estruturais (painéis, chassis, suportes)

    Níveis de detalhe: Os níveis mais baixos (N4 e N5) devem ter nomes mais específicos e detalhados

    Exemplo de um Subsistema Completo

    json

    {
      "id": "PT",
      "nome": "Powertrain",
      "sistemas": [
        {
          "id": "ENG",
          "nome": "Engine",
          "sub_sistemas": [
            {
              "id": "CYL_HEAD",
              "nome": "Cylinder Head",
              "componentes": [
                "Intake Valve",
                "Exhaust Valve",
                "Valve Guide",
                "Valve Seat",
                "Camshaft Bearing",
                "Spark Plug Hole",
                "Coolant Jacket",
                "Oil Drain Passage"
              ],
              "pecas_detalhadas": [
                "Valve Spring",
                "Valve Retainer",
                "Valve Stem Seal",
                "Valve Keeper",
                "Valve Lash Adjuster",
                "Valve Spring Seat",
                "Valve Rotator",
                "Valve Collet",
                "Valve Lock",
                "Valve Stem Cap"
              ]
            }
          ]
        }
      ]
    }

    Lista de Subsistemas e Sistemas (Obrigatorio)

    Subsistema ID

    Subsistema Nome

    Sistemas

    PT

    Powertrain

    Engine, Transmission, Fuel System, Exhaust System

    CH

    Chassis

    Frame, Suspension, Steering, Brake System, Wheels

    IN

    Interior

    Dashboard, Seats, HVAC, Airbags, Interior Trim

    EL

    Electrical

    Battery & Power, Lighting, ECU & Sensors, Wiring Harness, Infotainment

    BO

    Body

    Body Panels, Glass, Exterior Trim, Mirrors & Wipers, Doors

    Formato de Saida

    Gera apenas o JSON valido, sem texto adicional. O JSON deve ser completo e poder ser validado em jsonlint.com.

    Nota: Este dataset sera usado para gerar 2000+ vertices num grafo, por isso a variedade e quantidade de nomes e critica.

## Exemplo do resultado, apenas um exerto do que foi criado.

        {
      "metadata": {
        "nome": "Dataset de Componentes Automoveis para DeepChainBench",
        "versao": "1.0",
        "total_componentes": 2847,
        "total_por_nivel": {
          "nivel_1_subsistemas": 6,
          "nivel_2_sistemas": 18,
          "nivel_3_sub_sistemas": 42,
          "nivel_4_componentes": 421,
          "nivel_5_pecas_detalhadas": 2360
        },
        "matematica_benchmark": {
          "titanium_mod": 15,
          "temperature_mod": 12,
          "parent_rule": "i - 1",
          "redundant_edges": 0.30,
          "extra_parent_range": "k ∈ [2,5]"
        },
        "instrucoes": "Este JSON contem apenas a hierarquia de nomes. A matematica (Titanium, temperatura, parent, arestas) deve ser aplicada pelo gerador Java separadamente."
      },
      "subsistemas": [
        {
          "id": "PT",
          "nome": "Powertrain",
          "sistemas": [
            {
              "id": "ENG",
              "nome": "Engine",
              "sub_sistemas": [
                {
                  "id": "CYL_HEAD",
                  "nome": "Cylinder Head",
                  "componentes": [
                    "Intake Valve",
                    "Exhaust Valve",
                    "Valve Guide",
                    "Valve Seat",
                    "Camshaft Bearing",
                    "Spark Plug Hole",
                    "Coolant Jacket",
                    "Oil Drain Passage"
                  ],
                  "pecas_detalhadas": [
                    "Valve Spring",
                    "Valve Retainer",
                    "Valve Stem Seal",
                    "Valve Keeper",
                    "Valve Lash Adjuster",
                    "Valve Spring Seat",
                    "Valve Rotator",
                    "Valve Collet",
                    "Valve Lock",
                    "Valve Stem Cap"
                  ]
                },
                {
                  "id": "CYL_BLOCK",
                  "nome": "Cylinder Block",
                  "componentes": [
                    "Cylinder Bore",
                    "Main Bearing Cap",
                    "Oil Gallery",
                    "Water Jacket",
                    "Freeze Plug",
                    "Deck Surface",
                    "Crankcase",
                    "Core Plug",
                    "Oil Squirt Hole",
                    "Head Bolt Hole"
                  ],
                  "pecas_detalhadas": [
                    "Main Bearing Bolt",
                    "Cylinder Sleeve",
                    "Oil Control Ring",
                    "Piston Cooling Nozzle",
                    "Head Bolt Set",
                    "Cylinder Block Dowel Pin",
                    "Oil Gallery Plug",
                    "Water Jacket Core Plug"
                  ]
                },
                {
                  "id": "PISTONS",
                  "nome": "Pistons",
                  "componentes": [
                    "Piston Crown",
                    "Piston Skirt",
                    "Piston Ring Groove",
                    "Piston Pin Bore",
                    "Piston Dome",
                    "Piston Land"
                  ],
                  "pecas_detalhadas": [
                    "Compression Ring - Top",
                    "Compression Ring - Middle",
                    "Oil Scraper Ring",
                    "Piston Pin",
                    "Piston Pin Retaining Clip",
                    "Piston Ring Set",
                    "Piston Pin Bushing",
                    "Piston Cooling Gallery"
                  ]
                },
                {
                  "id": "CONNECTING_RODS",
                  "nome": "Connecting Rods",
                  "componentes": [
                    "Big End",
                    "Small End",
                    "Rod Shank",
                    "Rod Bearing Insert",
                    "Rod Bolt Hole",
                    "Rod Cap"
                  ],
                  "pecas_detalhadas": [
                    "Big End Bolt",
                    "Big End Nut",
                    "Rod Bearing Shell",
                    "Rod Bearing Lock Tab",
                    "Rod Bolt Washer",
                    "Rod Bearing Set",
                    "Rod Cap Bolt"
                  ]
                },

<figure>

</figure>

teste para nova figura

<figure>

<figcaption>Representação estrutural da Zone 2: Arestas redundantes
geram uma ramificação massiva de contextos de execução que colidem no nó
555, prosseguindo numa cadeia extrema até à anomalia térmica no nó
552.</figcaption>
</figure>

novo teste

<figure>

<figcaption>Representação topológica fiel da Zona 2 (IDs 565 a 552).
Cerca de 30% dos nós geram arestas redundantes com <span
class="math inline"><em>k</em> ∈ [2, 5]</span>, criando "Memory
Diamonds" por sobreposição de caminhos. A colisão multi-modelo e o
alerta severo ocorrem nos nós alvo das regras de módulo.</figcaption>
</figure>

teste 30000

<figure>

<figcaption>Representação topológica da Zona 2. Os nós seguem uma cadeia
linear com arestas redundantes que geram diamantes de memória ao
contornar a cadeia principal (<span
class="math inline"><em>k</em> ∈ [2, 5]</span>).</figcaption>
</figure>

# Modo de funcionamento do Benchmark

# Arquitetura da Ferramenta de Execução do DeepChainBench

Para sustentar a complexidade topológica do _DeepChainBench_ e extrair
métricas irrepreensíveis para avaliação científica, foi desenvolvida uma
ferramenta de execução dedicada em Java. Ao contrário de ferramentas
genéricas de _benchmarking_ (como o YCSB), esta aplicação exige um
desacoplamento rigoroso entre a orquestração da carga sintética e a
medição do impacto físico no _hardware_.

O objetivo principal desta separação é garantir que as falhas
estruturais expostas nos Sistemas de Gestão de Bases de Dados
Multimodelo (MMDBMS) --- como o esgotamento da pilha (_Stack Overflow_)
nas cadeias lineares profundas --- são atribuídas exclusivamente à
ineficiência do motor da base de dados, e não a constrangimentos da
Máquina Virtual Java (JVM) que orquestra o teste.

## Visão Global dos Módulos

A arquitetura da aplicação divide-se em quatro motores funcionais
isolados, coordenados por um orquestrador central (_Core Orchestrator_),
conforme ilustrado na Figura [1](#fig:arch_app){reference-type="ref"
reference="fig:arch_app"}:

1. **_Generator Engine_:** Responsável por gerar o _dataset_
   determinístico (ficheiros CSV e JSON) baseado numa _seed_ matemática
   fixa (garantindo o _Scale Factor_ exato).

2. **_Ingestion Engine_:** Adapta o modelo físico e injeta os dados de
   forma nativa para o motor alvo (ex: mapeando vértices, documentos e
   pares Chave-Valor para o ArangoDB ou OrientDB).

3. **_Execution Engine_:** O núcleo de _stress_ computacional,
   subdividido em dois modos de operação distintos (Modo RFI e Modo
   Tradicional), que controlam as _threads_ através de uma _Worker
   Pool_.

4. **_Telemetry & Reporter Engine_:** Uma sonda externa que recolhe
   métricas de _hardware_ diretamente do sistema operativo ou da API do
   contentor (Docker), exportando os resultados formatados (CSV) para
   análise.

<figure id="fig:arch_app">

<figcaption>Arquitetura modular da aplicação DeepChainBench. O sistema
isola a orquestração do benchmark da monitorização do
hardware.</figcaption>
</figure>

## Modos de Execução do Benchmark

A aplicação foi desenhada para fornecer duas lentes analíticas distintas
sobre o SGBD em teste. Esta bifurcação impede que as métricas de
diagnóstico estrutural sejam contaminadas pela flutuação natural de um
ambiente de concorrência massiva.

### Modo RFI (Resource Consumption Index)

O Modo RFI atua como um bisturi de diagnóstico algorítmico. O seu
propósito exclusivo não é medir o _throughput_ (Consultas por Segundo),
mas sim isolar o **ponto de rutura arquitetural** do otimizador de
consultas perante as topologias anómalas do grafo.

- **Isolamento de Execução:** Dispara apenas uma _query_ de cada vez
  (por exemplo, a Consulta 5, que atravessa 299 níveis de profundidade
  na Cadeia Linear), limitando a execução a um único utilizador ativo
  (1 _Thread_).

- **Telemetria Direta (Sem _Overhead_ JVM):** Para garantir que a
  medição de RAM reflete estritamente a paginação do motor
  multi-modelo e não as flutuações do _Garbage Collector_ do Java, o
  _Telemetry Engine_ acede diretamente à API do Docker (através de
  bibliotecas baseadas em Unix _sockets_). Ele sonda os _cgroups_ do
  contentor a intervalos de frações de segundo, capturando o
  `memory.usage_in_bytes` bruto à medida que a profundidade da
  travessia ($\Delta d$) aumenta.

- **Avaliação Científica:** Permite plotar gráficos de Consumo de RAM
  vs. Profundidade. Se a variação de memória ($\Delta RAM$) se
  comportar de forma exponencial ao entrar em zonas ricas em
  redundâncias (como a Zona 2), fica provada a incapacidade do motor
  em efetuar _memoization_, originando explosões combinatórias
  $\mathcal{O}(2^n)$.

### Modo Tradicional (Benchmark de Concorrência)

O Modo Tradicional simula o ambiente de produção real de uma cadeia de
abastecimento IoT, injetando uma elevada carga de concorrência (ex: 100
utilizadores em simultâneo) utilizando uma _Worker Pool_ assíncrona.

Em vez de focar no perfil de memória intrínseco de uma única consulta,
este modo avalia a saturação do CPU, a contenção de bloqueios (_Lock
Contention_) e a latência de cauda (_Tail Latency_) através de três
misturas de carga de trabalho (_Workloads_):

1. **_Read Heavy_ (95% / 5%):** 95% das operações são travessias
   logarítmicas rápidas e verificações de Chave-Valor (Consultas de
   Nível 1 e 2), com 5% de injeção de novas leituras térmicas. Testa a
   eficiência de leitura em cache.

2. **_Balanced_ (50% / 50%):** Carga equilibrada entre travessias
   híbridas e processos de atualização na telemetria semiestruturada.
   Ocorre uma disputa massiva de recursos no Nó 800 (o Super-Hub da
   topologia _Power-law_).

3. **_Analytical Stress_ (5% / 95%):** Injeção brutal de concorrência
   nas Consultas de Nível 4 e 5. Força os processadores lógicos do SGBD
   a resolver _Wrapper Penalties_ contínuas, traduzindo modelos
   gráficos, documentais e dicionários simultaneamente na mesma
   transação.

Para este modo, o _Reporter Engine_ extrai o Percentil 95 (P95) e
Percentil 99 (P99), comprovando se o SGBD consegue manter a
previsibilidade de latência sob _stress_ multidimensional.

## Máquina de Estados e Fluxo de Interação

A interação do utilizador decorre através de uma Interface de Linha de
Comando (CLI) que rege um fluxo processual restrito, visível na Figura
[2](#fig:fluxo_cli){reference-type="ref" reference="fig:fluxo_cli"}. A
máquina de estados garante que a fase de testes (Passo 3) nunca ocorre
sem que as fases preliminares de Geração (Passo 1) e Ingestão (Passo 2)
tenham validado a integridade física dos dados no motor.

<figure id="fig:fluxo_cli">

<figcaption>Máquina de estados da aplicação. O fluxo garante a
integridade processual, bifurcando a execução analítica (RFI) do teste
de esforço concorrente (Tradicional).</figcaption>
</figure>

# Expansão do Modo RFI: Avaliação de Memoization (Cold vs. Warm Runs)

O Modo RFI testa estritamente 1 utilizador (thread única) para garantir
que o consumo de memória medido ($\Delta$ RAM) deriva exclusivamente da
complexidade topológica do grafo (os \"diamantes de memória\" e
recursividade) e não de bloqueios de concorrência ou gestão de threads.
Contudo, executar a mesma consulta múltiplas vezes com o mesmo
utilizador introduz uma nova dimensão científica:a avaliação da
inteligência do otimizador do SGBD.

**Mecânica de Execução (Ciclo de Repetição RFI):**

- **_Run 1 (Cold Cache / Estado Frio):_** O motor depara-se com a
  query pela primeira vez. É forçado a ler do disco (I/O), o planeador
  tem de calcular o plano de execução do zero e navegar a topologia à
  força bruta. Regista-se o timestamp e o pico de RAM.

- **_Runs 2 a N (Warm Cache / Estado Quente):_** A mesma consulta é
  repetida em sequência imediata. Mede-se a capacidade da base de
  dados reter os caminhos sobrepostos e os planos de execução em
  memória RAM.

**Interpretação dos Resultados (Otimização):**

- **_Otimizador Inteligente (Memoization):_** Se o RFI (consumo de
  RAM) e o tempo de execução caírem drasticamente nas iterações
  quentes, prova-se que o SGBD guarda planos e subcaminhos em cache. O
  motor evitou recalcular a explosão combinatória $\mathcal{O}(2^n)$.

- **_Amnésia Arquitetural:_** Se o gráfico de consumo de RAM for
  idêntico em todas as passagens, o motor é ineficiente na retenção de
  contexto multi-modelo. Desperdiça recursos a recalcular ligações
  redundantes que já tinha resolvido milissegundos antes.

<figure id="fig:rfi_cold_warm">

<figcaption>Fluxograma do Modo RFI de repetição. A medição comparativa
entre o <em>Cold Run</em> e os <em>Warm Runs</em> permite isolar e
diagnosticar a capacidade de <em>memoization</em> e retenção de planos
de execução do SGBD face à explosão combinatória.</figcaption>
</figure>

A seleção das Consultas 5, 8 e 10 para o Modo RFI (_Resource Footprint
Index_) obedece a um critério de exclusão rigoroso: o objetivo do RFI
não é testar o rendimento transacional de leituras simples, mas sim
isolar vetores de falha sistémica e forçar o colapso arquitetural dos
motores multi-modelo. As consultas de nível 1 a 4 são demasiado leves
para gerar uma degradação mensurável de RAM. Em contraste, a "artilharia
pesada" destas três consultas ataca os três principais estrangulamentos
computacionais dos SGBDs.

Consulta 5: Isolamento da Recursividade e Esgotamento da Pilha

: \
 **Propósito Científico:** Avaliar a imunidade do motor ao limite de
recursividade (ex: _Stack Overflow_) e a eficiência de paginação de
disco.\
 **Mecânica:** Força uma travessia linear extrema de 299 níveis de
profundidade, focando exclusivamente na rede de grafos, sem a
interferência de documentos JSON ou metadados.\
 **Justificação:** Atua como o grupo de controlo para o limite de
memória em profundidade. Se a RAM esgotar ou o sistema colapsar
nesta consulta, comprova-se empiricamente que o limite do SGBD
reside na fraca gestão de recursividade $\mathcal{O}(N)$,
independentemente da complexidade dos modelos de dados.

Consulta 8: Medição da _Wrapper Penalty_ e Colisão Semântica

: \
 **Propósito Científico:** Expor a degradação arquitetural (_Wrapper
Penalty_) gerada pelas trocas contínuas de contexto entre motores de
armazenamento internos.\
 **Mecânica:** Combina uma travessia profunda com a validação
síncrona de dicionários Chave-Valor e a desserialização pesada de
matrizes JSON a cada nó visitado.\
 **Justificação:** Constitui o teste supremo de carga transversal. A
curva de consumo de memória quantifica o custo exato da alocação de
objetos temporários e a carga sobre o _Garbage Collector_ quando o
motor é obrigado a traduzir formatos heterogéneos em tempo real no
meio de uma travessia.

Consulta 10: Avaliação de _Memoization_ e Explosão Combinatória

: \
 **Propósito Científico:** Avaliar a inteligência do otimizador de
consultas e as suas capacidades de _caching_ perante anomalias
topológicas (_Memory Diamonds_).\
 **Mecânica:** Remove os mecanismos de salvaguarda de _loops_ nativos
(ex: `OPTIONS { uniqueVertices: "none" }`) e navega através de zonas
de alta redundância ($30\%$ de arestas ancestrais), forçando
sobreposições estruturais.\
 **Justificação:** É a consulta definidora para a mecânica de _Cold
vs. Warm Runs_. Sem estratégias de _memoization_ eficazes para reter
os subcaminhos já visitados, a redundância forçará o SGBD a
recalcular rotas intersecionadas, gerando uma explosão combinatória
$\mathcal{O}(2^n)$ que resulta num pico exponencial e fatal no
consumo de RAM.

# Carga de Escrita

## Carga de Trabalho de Escrita (Mutações Transacionais e Concorrência HTAP (Hybrid Transactional/Analytical Processing))

A avaliação do processamento HTAP (_Hybrid Transactional/Analytical
Processing_) obriga à introdução de operações de mutação que perturbam
ativamente as consultas analíticas simultâneas. Este subconjunto de 10
consultas de escrita (W1 a W10) visa criar contenção de bloqueios (_Lock
Contention_) a diferentes níveis de granularidade.

O motor de orquestração injeta variáveis de ligação (_bind variables_)
de forma determinística em cada _thread_ de execução, garantindo que as
operações atingem alvos com perfis de dispersão ou centralidade
conhecidos (por exemplo, os super-hubs da Zona 3).

### Nível 1: Modificações Atómicas e Atualizações Focadas

O primeiro nível de mutações atua sobre documentos individuais sem
explorar o grafo. Serve para medir a rapidez com que a base de dados
adquire e liberta bloqueios (_locks_) de baixo nível e resolve
invalidações de cache simples.

#### **Escrita W1 (Atualização Atómica de KV)**

Altera aleatoriamente o atributo de qualidade e pontuação de uma peça na
coleção de Chave-Valor. Sendo a operação com menor latência teórica
($\mathcal{O}(1)$), atua como o marcador de base (_baseline_) para as
taxas de transferência (_throughput_) do motor.

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf
    LET target = CONCAT(@key_base, '_sf', i)
    UPDATE target WITH { cert: @nova_cert, score: @novo_score, is_temp: true } IN Quality_KV
```

**Qual é a ação da Query?**\
Localiza diretamente o documento KV associado à peça alvo e atualiza o
seu certificado e a sua pontuação, introduzindo uma _flag_ temporária
para posterior limpeza.

**O que o UPDATE\... IN faz neste contexto?**\
Em AQL, o `UPDATE` modifica parcialmente um documento existente. Ao
contrário de um `REPLACE` que reescreve todo o objeto, o `UPDATE` apenas
altera ou adiciona as chaves especificadas, forçando a base de dados a
gerir a fragmentação do documento subjacente.

**Equivalente em um simples SQL:**

    UPDATE PROD.quality_kv
    SET cert = @nova_cert, score = @novo_score, is_temp = true
    WHERE id = @key_base;

#### **Escrita W2 (Injeção de Telemetria IoT - _Array Append_)**

Anexa um novo registo térmico simulado a um documento existente. Este
teste é crítico para motores baseados em _B-Trees_ e _LSM-Trees_,
forçando a desserialização do JSON e a reescrita física do documento.

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf
    LET target = CONCAT(@key_base, '_sf', i)
    FOR t IN Telemetry FILTER t.part_id == target
    UPDATE t WITH { sensor_logs: PUSH(t.sensor_logs, {ts: @ts, v: @temp, is_temp: true}) } IN Telemetry
```

**Qual é a ação da Query?**\
Encontra o documento de telemetria da peça e anexa (via `PUSH`) um novo
objeto (com _timestamp_ e valor de temperatura) ao _array_ JSON aninhado
`sensor_logs`.

**O que a função PUSH faz?**\
Manipula a matriz JSON internamente. O SGBD é obrigado a carregar o
_array_ atual para a memória, adicionar o novo elemento, recalcular os
índices de tamanho do documento e gravar a nova estrutura no disco,
expondo a base de dados a custos de amplificação de escrita (_Write
Amplification_).

**Equivalente em um simples SQL:**

    UPDATE PROD.telemetry
    SET sensor_logs = array_append(sensor_logs, CAST('{"ts":' || @ts || ', "v":' || @temp || ', "is_temp":true}' AS JSON))
    WHERE part_id = @key_base;

### Nível 2: Invalidação de Cache e Colisões Síncronas

Eleva o desafio ao induzir invalidações forçadas do planeador de
consultas e a gerir modificações cruzadas em coleções heterogéneas.

#### **Escrita W3 (Mutação Semântica do Grafo)**

Altera diretamente o atributo material de uma peça estrutural.

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf
    LET target = CONCAT(@key_base, '_sf', i)
    UPDATE target WITH { material: @novo_material, is_temp: true } IN Parts
```

**Qual é a ação da Query?**\
Modifica um metadado crítico (`material`) do vértice do grafo. O
objetivo é causar perturbação noutras consultas (como a Q6) que dependem
ativamente deste atributo para aplicar _Early Pruning_ durante
travessias recursivas.

**O que o motor enfrenta durante esta atualização?**\
O SGBD tem de lidar com a invalidação da _cache_. Se uma _thread_
analítica estava a assumir o material como \"Titânio\" a meio de um
_join_, esta consulta de escrita força o motor a garantir o isolamento
transacional (_MVCC_), servindo a versão antiga ou bloqueando até a
atualização terminar.

**Equivalente em um simples SQL:**

    UPDATE PROD.parts
    SET material = @novo_material, is_temp = true
    WHERE id = @key_base;

#### **Escrita W4 (Colisão de Escrita Multi-Modelo Síncrona)**

Marca uma anomalia na telemetria (Documento) e revoga o certificado no
dicionário (Quality_KV) numa única transação garantida.

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf
    LET target = CONCAT(@key_base, '_sf', i)
    FOR t IN Telemetry FILTER t.part_id == target
    UPDATE t WITH { anomaly: true, is_temp: true } IN Telemetry
    UPDATE target WITH { cert: 'Revogado', score: 0, is_temp: true } IN Quality_KV
```

**Qual é a ação da Query?**\
Realiza duas atualizações atómicas em duas coleções estruturalmente
diferentes dentro do mesmo fluxo processual.

**O que as múltiplas instruções UPDATE\... IN forçam o motor a fazer?**\
Para garantir as propriedades ACID, o gestor de transações tem de
adquirir, em simultâneo, os bloqueios de escrita exclusivos num
documento da coleção `Telemetry` e noutro documento da coleção
`Quality_KV`. Em cenários de HTAP, isto aumenta drasticamente a
probabilidade de ocorrência de _Deadlocks_ clássicos (_deadly embrace_).

**Equivalente em um simples SQL (Transação):**

    BEGIN TRANSACTION;
    UPDATE PROD.telemetry SET anomaly = true, is_temp = true WHERE part_id = @key_base;
    UPDATE PROD.quality_kv SET cert = 'Revogado', score = 0, is_temp = true WHERE id = @key_base;
    COMMIT;

### Nível 3: Mutabilidade Topológica Dinâmica

Introduz mutações estruturais que alteram as relações do próprio grafo,
forçando o recálculo dos caminhos de vizinhança.

#### **Escrita W5 (Inserção Topológica Dinâmica - _Insert Edge_)**

Altera a conectividade da rede em tempo real, inserindo uma aresta
redundante, tipicamente em zonas de alta densidade (como o _Super-Hub_
na Zona 3).

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf
    LET from_node = CONCAT('Parts/', @key_base, '_sf', i)
    LET to_node = CONCAT('Parts/', @target_child, '_sf', i)
    INSERT { _from: from_node, _to: to_node, qty: 1, type: 'redundant', is_temp: true } INTO BoM_Edges
```

**Qual é a ação da Query?**\
Cria uma nova ligação pai-filho no grafo, que passa a influenciar
imediatamente o cálculo das árvores e dos caminhos redundantes.

**O que o INSERT\... INTO acarreta num motor de grafos?**\
O SGBD não pode limitar-se a adicionar um registo no disco. Ele é
obrigado a sincronizar e atualizar ativamente as listas de adjacência
(índices de arestas) tanto do `from_node` como do `to_node`. Esta
re-indexação pode causar pesados _Write Locks_ se os vértices alvo forem
_Hubs_ com elevado _fan-out_.

**Equivalente em um simples SQL:**

    INSERT INTO PROD.bom_edges (from_id, to_id, qty, type, is_temp)
    VALUES (@key_base, @target_child, 1, 'redundant', true);

#### **Escrita W6 (Bloqueio de _Fan-out_ em Lote)**

Aumenta a quantidade (`qty`) de todas as arestas que dependem
diretamente de uma raiz (como o Nó 800 Zipfiano).

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf
    LET root = CONCAT('Parts/', @key_base, '_sf', i)
    FOR e IN BoM_Edges FILTER e._from == root
    UPDATE e WITH { qty: e.qty + 1, is_temp: true } IN BoM_Edges
```

**Qual é a ação da Query?**\
Localiza todas as arestas onde a peça atual figura como progenitor
(`_from`) e aplica uma atualização massiva (+1 na quantidade).

**O que as operações em lote (Batch Updates) testam?**\
Se aplicada a um nó com escasso _fan-out_, é inofensiva. Mas quando
direcionada a um nó da Zona 3 (_Power-law_) que possui centenas de
ligações, força o motor a obter bloqueios sobre centenas de documentos
em simultâneo. Avalia se o sistema possui mecanismos maduros de
escalonamento de bloqueios (_Lock Escalation_).

**Equivalente em um simples SQL:**

    UPDATE PROD.bom_edges
    SET qty = qty + 1, is_temp = true
    WHERE from_id = @key_base;

### Nível 4: Cascatas Condicionais e Varreduras Profundas

Funde recursividade profunda com execuções condicionais severas, expondo
os limites dos gestores transacionais sob stress cruzado.

#### **Escrita W7 (Atualização em Cascata - _1-hop Cascade_)**

Desce um nível a partir de uma raiz e altera os certificados de todos os
subcomponentes diretos para \"Em Revisão\".

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i)
    FOR v IN 1..1 INBOUND root BoM_Edges
    UPDATE v._key WITH { cert: 'Em Revisão', is_temp: true } IN Quality_KV
```

**Qual é a ação da Query?**\
Mistura a resolução estrutural do grafo com um _loop_ de atualização
numa terceira coleção (`Quality_KV`), gerando uma cascata analítica
transacional restrita.

**O que a combinação FOR\... INBOUND com UPDATE faz?**\
É o primeiro grande desafio transacional dependente de topologia. O
motor precisa primeiro de garantir as leituras dos vértices e depois
aplicar múltiplos bloqueios exclusivos num bloco (_batch_) à coleção KV,
mantendo o _snapshot_ transacional válido.

**Equivalente em um simples SQL:**

    UPDATE PROD.quality_kv
    SET cert = 'Em Revisão', is_temp = true
    WHERE id IN (
        SELECT e.from_id
        FROM PROD.bom_edges e
        WHERE e.to_id = @key_base
    );

#### **Escrita W8 (O "Carrasco de Escrita" / _Deep Traverse Condicional_)**

Navega os 299 níveis de profundidade nas cadeias lineares. Nos nós em
"Titânio", injeta uma anomalia térmica nos _logs_ do Documento.

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i)
    FOR v IN 1..299 INBOUND root BoM_Edges FILTER v.material == 'Titânio'
    FOR t IN Telemetry FILTER t.part_id == v._key
    UPDATE t WITH { anomaly: true, is_temp: true, sensor_logs: PUSH(t.sensor_logs, {ts: @ts, v: 99.9, is_temp: true}) } IN Telemetry
```

**Qual é a ação da Query?**\
Constitui o teste supremo de carga transversal. Varre até 299 nós,
filtra o material, extrai o JSON associado a esse nó, e modifica a
matriz desse documento específico.

**O que este aninhamento severo causa no motor?**\
Obriga o SGBD a alocar recursos e a manter o isolamento de longo curso
(_Long-running Transactions_) enquanto o algoritmo de Graph Traversal
($\mathcal{O}(N)$) processa cada passo de forma iterativa. Expõe graves
falhas caso os bloqueios não sejam libertados precocemente ou ocorra
_Out-Of-Memory_ da pilha de contexto.

**Equivalente em um simples SQL (Simplificado usando CTE):**

    WITH RECURSIVE deep_traverse AS (
        ... -- (Omite-se a CTE recursiva semelhante a Q6)
    )
    UPDATE PROD.telemetry
    SET anomaly = true, is_temp = true, sensor_logs = array_append(sensor_logs, '{"v":99.9}')
    WHERE part_id IN (
        SELECT dt.from_id FROM deep_traverse dt
        JOIN PROD.parts p ON dt.from_id = p.id WHERE p.material = 'Titânio'
    );

### Nível 5: O Clímax da Contenção Zipfiana

Estas consultas utilizam mecanismos que contrariam os fluxos nativos e
desativam proteções estruturais do motor de base de dados.

#### **Escrita W9 (Atualização Bidirecional / _Bottom-Up_)**

Inicia-se num nó folha (por exemplo, na Zona 2) e ascende até 299 níveis
no sentido oposto (em direção à raiz), alterando a data de inspeção dos
vértices pai.

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf LET leaf = CONCAT('Parts/', @key_base, '_sf', i)
    FOR v IN 1..299 OUTBOUND leaf BoM_Edges
    UPDATE v._key WITH { last_inspected: @ts, is_temp: true } IN Parts
```

**Qual é a ação da Query?**\
Faz uso do índice inverso das arestas para percorrer a árvore \"de baixo
para cima\", inserindo atributos (`last_inspected`) e marcadores
transitórios pelo caminho.

**O que o OUTBOUND associado a UPDATE testa?**\
Ao forçar varrimentos ascendentes concorrentes com travessias
descendentes na mesma cadeia (como a W8), gera colisões geométricas
intersecionais garantidas. Esta dinâmica é desenhada de forma explícita
para maximizar a criação de _Deadlocks_ e testar o tempo de resposta na
rejeição de transações do SGBD.

**Equivalente em um simples SQL (Simplificado):**

    WITH RECURSIVE bottom_up AS (
        ... -- (Omite-se a CTE ascendente de Q9)
    )
    UPDATE PROD.parts SET last_inspected = @ts, is_temp = true
    WHERE id IN (SELECT to_id FROM bottom_up);

#### **Escrita W10 (Diamantes de Memória - O Poço de Bloqueios)**

Atinge as topologias da Zona 3 (_Small-World_). Força a reavaliação
matemática em caminhos redundantes iterando 10 níveis, atualizando
repetidamente contadores _hit_ KV de todos os nós intersectados.

**Query para AQL (ARANGO DB)**

```{.java language="Java"}
FOR i IN 1..@sf LET root = CONCAT('Parts/', @key_base, '_sf', i)
    FOR v IN 1..10 INBOUND root BoM_Edges OPTIONS { uniqueVertices: 'none', bfs: false }
    LET kv = DOCUMENT(CONCAT('Quality_KV/', v._key))
    UPDATE v._key WITH { diamond_hits: (kv.diamond_hits || 0) + 1, is_temp: true } IN Quality_KV
```

**Qual é a ação da Query?**\
Navega os múltiplos ramais e atalhos redundantes criados em torno dos
\"Super-Hubs\" sem excluir caminhos visitados. A cada nó que repete,
incrementa artificialmente um valor contido num documento no
_Quality_KV_.

**O que `uniqueVertices: ’none’` associado à disputa de recursos
provoca?**\
Se aplicada no epicentro das arestas redundantes (k $\in$ \[2,5\]), esta
consulta gera uma explosão combinatória massiva $\mathcal{O}(2^n)$. Isto
obriga os clientes concorrentes na _Worker Pool_ a estagnar numa espera
mútua para conseguir exclusividade de modificação sob o _Document-Level
Lock_ dos mesmos componentes atratores. A taxa de _Timeout/Lock
Contention_ desta consulta mede diretamente a tolerância à saturação
(Starvation) e a capacidade da base de dados multi-modelo sobreviver
perante falhas algorítmicas por parte do utilizador.

**Equivalente em um simples SQL (Simplificado):**

    WITH RECURSIVE redundance_traverse AS (
        ... -- (Omite-se a CTE recursiva O(2^n) de Q10)
    )
    UPDATE PROD.quality_kv
    SET diamond_hits = COALESCE(diamond_hits, 0) + 1, is_temp = true
    WHERE id IN (SELECT from_id FROM redundance_traverse);

# Protocolo Experimental e Orquestração do Teste de Stress (HTAP)

Para validar a resiliência arquitetural dos Motores de Bases de Dados
Multimodelo (MMDBMS) sob condições de saturação, o _DeepChainBench_
implementa um motor de execução concorrente rigoroso. Para clarificar o
processo científico perante auditoria e garantir a reprodutibilidade dos
resultados, o fluxo lógico que rege cada uma das _threads_ (_Worker
Pool_) é decomposto em quatro etapas cronológicas fundamentais:

## Fluxo Cronológico de Execução e Algoritmo de Decisão

1. **O Cenário de Concorrência (A Barreira de Sincronização):**\
   A injeção de carga num SGBD requer que as operações ocorram numa
   janela temporal sobreposta para induzir verdadeira contenção de
   bloqueios (_Lock Contention_). O orquestrador instancia os
   utilizadores virtuais (ex: 100 _threads_) em memória, mas bloqueia a
   sua execução utilizando o primitivo `CountDownLatch` da JVM. Quando
   o sinal de partida é emitido, a barreira é levantada e todas as
   _threads_ disparam os seus pacotes HTTP/TCP em uníssono. Isto impede
   que a base de dados processe os pedidos de forma desfasada e esvazie
   as filas de escalonamento (_Schedulers_) confortavelmente.

2. **O Motor Determinístico (A Semente Pseudoaleatória):**\
   Em ciência computacional, um _benchmark_ tem de ser reprodutível. Se
   as _threads_ escolhessem operações de forma verdadeiramente
   aleatória, os resultados variariam a cada execução, invalidando a
   análise comparativa entre motores (ex: ArangoDB vs OrientDB). Para
   garantir determinismo, cada _thread_ recebe um Gerador de Números
   Pseudoaleatórios (PRNG) isolado:

   ```{.java language="Java"}
   Random deterministico = new Random(42000 + streamId);
   ```

   O valor `42000` assegura o deslocamento base, e o `streamId` isola a
   entropia. Isto garante que a _Thread 0_ executará **sempre** a mesma
   sequência exata de operações lógicas e atingirá as mesmas chaves de
   destino (_target keys_) no ano de 2026 ou numa reavaliação futura,
   provando que a variação de desempenho é responsabilidade exclusiva
   do motor da base de dados e não do acaso da carga submetida.

3. **A Macro-Decisão (Ler vs. Escrever):**\
   Em cada iteração do ciclo de vida da _thread_, o gerador
   pseudoaleatório sorteia um número contínuo
   $U \sim \mathcal{U}(0,100)$. Este valor é comparado com o **Perfil
   de Carga** selecionado pelo investigador na CLI:
   - _Read-Heavy_: 95% Leituras / 5% Escritas (Cria a _Baseline_).

   - _Balanced_: 50% Leituras / 50% Escritas (Força colisões
     síncronas).

   - _Analytical Stress_: 5% Leituras / 95% Escritas (Provoca colapso
     e _Breakdown_).

   Se o perfil ativo for _Analytical Stress_ e o valor $U$ sorteado for
   82, a máquina de estados decide categoricamente que a operação a ser
   orquestrada será uma **Escrita**, avançando para o passo seguinte.

4. **A Micro-Decisão (A Distribuição Zipfiana):**\
   Uma vez decidida a operação (Escrita), a arquitetura tem de
   selecionar uma das 10 consultas disponíveis (W1 a W10). Num ambiente
   industrial real, a esmagadora maioria das operações diárias
   ($\approx 90\%$) são transações atómicas triviais (Nível 1), ao
   passo que as auditorias complexas e recalibrações topológicas
   (Nível 5) são raras.

   Para modelar matematicamente esta assimetria, a seleção da consulta
   é filtrada por um vetor de probabilidade baseado numa aproximação da
   **Lei de Zipf** ($s \approx 1.0$), onde a frequência de invocação
   cai drasticamente à medida que a complexidade algorítmica aumenta:

   ```{.java language="Java"}
   // O array representa percentagens agregadas por Nível Topológico (Soma = 100%)
   int[] probsEscrita = {
       22, 22, // W1, W2  (Nível 1 - 44%): Atualizações KV e Telemetria (O(1))
       11, 11, // W3, W4  (Nível 2 - 22%): Mutações Grafo e Colisões Síncronas
       7,  7,  // W5, W6  (Nível 3 - 14%): Inserção de Arestas e Batch Updates
       6,  5,  // W7, W8  (Nível 4 - 11%): Cascatas e Escritas de Alta Profundidade
       5,  4   // W9, W10 (Nível 5 -  9%): Inversão e Diamantes de Memória (O(2^n))
   };
   ```

   Deste modo, garante-se que os testes destrutivos arquiteturais (como
   os Diamantes de Memória) perfazem apenas $\approx 9\%$ do tráfego
   transacional. Esta raridade atua como um \"espião de otimização\"
   (_Optimization Probe_): se o _Throughput_ global da base de dados
   desabar neste perfil, prova-se empiricamente que o estrangulamento
   não derivou do volume bruto de transações, mas sim da incapacidade
   fulminante do gestor de bloqueios (_Lock Manager_) em resolver
   operações transversais com complexidade exponencial isolada.

## Mecanismos de Defesa Transacional e Reversibilidade HTAP

Testar a resiliência de um SGBD sob cargas agressivas exige não apenas
inundar o motor com operações, mas também monitorizar como o sistema se
defende perante estrangulamentos computacionais (_Bottlenecks_). O
_DeepChainBench_ foi estruturado para extrair e identificar padrões de
colapso através do recurso a limites rígidos (_Timeouts_) e para
garantir o regresso do sistema a um estado seguro e testável
(_Reversibilidade_).

### Limites de Execução Rigorosos (_Hard Timeouts_)

Sem mecanismos de salvaguarda impulsionados pelo cliente, consultas
analíticas com recursividade em profundidade profunda (ex:
$\mathcal{O}(2^n)$ nos diamantes de memória da Zona 3) podem engarrafar
o SGBD indefinidamente, originando tempos de teste infinitos e
inutilizando a capacidade de diagnóstico.

Para forçar um cenário realista onde o processamento transacional não
pode suportar esperas ilimitadas, o orquestrador do _DeepChainBench_
instaura um limite severo no motor (via _driver_ AQL ou correspondente):

```{.java language="Java"}
AqlQueryOptions options = new AqlQueryOptions().maxRuntime(60.0);
ArangoCursor<Map> cursor = db.query(queryExecutar, Map.class, bindVars, options);
```

**Propósito Científico:** O limite imposto de 60 segundos assegura que o
gráfico cronológico da latência (_Latency Timeline_) adquire um teto
visual rigoroso (60 000 ms). Se o SGBD falhar em entregar um resultado
neste intervalo de tempo, a _thread_ aborta a transação e assinala-a
como uma _Falha de Contenção_ (_Timeout/Lock_). Isto expõe
matematicamente a taxa de exaustão do motor, quantificando o volume
exato de consultas abandonadas para garantir a sobrevivência sistémica.

### Isolamento de Erros e Deteção Precoce de Saturação

O colapso de um motor raramente resulta no fecho repentino do serviço
(_Crash_), manifestando-se antes através de negação ativa de serviço a
_threads_ concorrentes para evitar abraços mortais (_Deadlocks_).

O gestor de exceções no bloco de execução captura ativamente os códigos
nativos devolvidos pelo SGBD:

```{.java language="Java"}
} catch (com.arangodb.ArangoDBException e) {
    sucesso = false;
    if (e.getErrorNum() != null && (e.getErrorNum() == 1200 || e.getErrorNum() == 1500)) {
        erroDetalhe = "Erro " + e.getErrorNum() + ": " + e.getErrorMessage();
        falhasContencao.add("Timeout/Lock");
    }
}
```

**O Erro 1200 (_Lock Contention_):** Quando ocorre a rejeição na casa
dos $\approx 100$ ms a $8.5$ segundos (muito abaixo do _maxRuntime_ de
60s), regista-se a ativação do detetor interno do motor de armazenamento
(ex: RocksDB). Ocorre frequentemente em consultas como a W10, onde o
SGBD antecipa o ciclo de bloqueio fatal (_Deadlock_) e opta por
\"assassinar\" de imediato a transação secundária (_timeout waiting to
lock key_). O rastreio detalhado desta métrica evidencia a incapacidade
de escala das coleções altamente centralizadas (Super-Hubs Zipfianos).

### Limpeza Automática de Dados Transitórios (_Reversibility Protocol_)

Para garantir a higienização dos dados e evitar o \"envenenamento\" do
dataset---onde anomalias topológicas (W5) ou semânticas (W3) inseridas
durante um teste poderiam facilitar ou penalizar testes analíticos nas
execuções subsequentes---implementou-se uma rotina de recolha e reversão
(_Garbage Collection_):

```{.java language="Java"}
"FOR e IN BoM_Edges FILTER e.is_temp == true REMOVE e IN BoM_Edges"
"FOR t IN Telemetry FILTER t.is_temp == true LET clean_logs = (FOR log IN t.sensor_logs FILTER log.is_temp != true RETURN log) UPDATE t WITH { anomaly: false, is_temp: null, sensor_logs: clean_logs } IN Telemetry OPTIONS { keepNull: false }"
```

**Mecânica de Aplicação:** Durante os testes de _stress_, qualquer
consulta mutável (W1 a W10) obriga à inserção síncrona de uma marcação
efémera explícita (`is_temp: true`). Quando a barreira de _threads_ se
dissolve e o orquestrador sinaliza a conclusão da métrica, uma sequência
isolada executa imediatamente uma varredura ao SGBD. Ao procurar pelos
objetos transitórios indexados, a rotina esvazia anomalias térmicas,
repõe o estado Chave-Valor e remove pontes de hiperconectividade
indesejadas introduzidas na rede estrutural. O _benchmark_ termina com
os dados integramente restaurados ao seu estado basal de avaliação,
garantindo consistência sem recorrer à eliminação massiva (Drop) das
coleções e re-ingestão total (_Full Re-ingestion_).

## Observabilidade: Telemetria de Hardware e Percentis de Latência

A avaliação arquitetural de um SGBD multi-modelo carece de validade
científica se for sustentada unicamente por métricas superficiais de
velocidade global (_Throughput_). O _DeepChainBench_ implementa uma
sonda de observabilidade (_Telemetry Engine_) que opera
independentemente do orquestrador de carga, isolando as métricas do
motor e registando o colapso estrutural através da degradação das
latências de cauda (_Tail Latency_).

### Extração Independente de Recursos (_Observer Pattern_)

Para evitar o _Observer Effect_ --- um fenómeno onde a própria
ferramenta de monitorização esgota os recursos (CPU/RAM) da máquina e
vicia os resultados do _benchmark_ --- a recolha de métricas de hardware
ocorre de forma assimétrica.

A _Telemetry Engine_ foi arquitetada para operar numa _Thread_ dedicada
(_Background Daemon_) que é lançada antes da abertura da barreira de
concorrência. Esta rotina executa de forma desobstrutiva através de um
intervalo fixo (_Polling Interval_):

```{.java language="Java"}
while (running) {
    Thread.sleep(250); // Intervalo de amostragem fixo
    // Extrai CPU e RAM nativa do contentor (cgroups) e Metrics API
}
```

**Isolamento do Motor de Armazenamento:** Em vez de monitorizar a
memória genérica do sistema operativo, a qual flutua caoticamente devido
a _buffers_ do _kernel_ ou atividades do disco, o módulo de telemetria
consulta diretamente o terminal Prometheus exportado pelo SGBD alvo. No
caso do ArangoDB, a sonda efetua chamadas HTTP cirúrgicas a
`_admin/metrics`, extraindo o indicador de baixo nível
`rocksdb_block_cache_usage`.

Este valor reflete unicamente a porção de RAM que o motor _RocksDB_ está
ativamente a utilizar para manter vértices de grafos, documentos JSON e
registos KV em cache durante a execução. O desfasamento temporal entre o
crescimento desta variável e o aborto de consultas evidencia
perfeitamente o _Cache Thrashing_ (Colapso de Cache), onde os diferentes
modelos de dados digladiam-se pelo espaço restrito de memória primária.

### Cálculo Estatístico: _Tail Latency_ (P50, P95, P99)

Cada _Thread_ na _Worker Pool_ grava o tempo absoluto (em milissegundos)
que medeia a emissão da _query_ e a receção do código de sucesso ou
rejeição. Em vez de calcular uma média aritmética enganadora --- que
dilui picos de estrangulamento pontuais na massa global de consultas
rápidas (Nível 1) ---, o _Reporter Engine_ consolida todas as latências
num conjunto ordenado (_Array_ cronológico).

A análise recai no cálculo formal de **Percentis**:

- **Mediana (P50):** Representa o tempo máximo registado para as 50%
  consultas mais rápidas. Estabelece a latência nominal do SGBD sob
  carga.

- **Latência de Cauda (P95 e P99):** A extração do 95º e 99º percentil
  foca-se cirurgicamente na extremidade mais degradada da curva de
  execução. Representa o tempo que as transações mais azaradas
  demoraram a concluir ou a sofrer _Timeout_.

```{.java language="Java"}
List<Long> ordenadas = new ArrayList<>(latencies);
Collections.sort(ordenadas);
int size = ordenadas.size();
long p50 = ordenadas.get((int) (size * 0.50));
long p95 = ordenadas.get((int) (size * 0.95));
long p99 = ordenadas.get((int) (size * 0.99));
```

**A Relevância Científica da Latência de Cauda:** Num ambiente
_Analytical Stress_, os bloqueios de contenção (_Lock Contention_)
manifestam-se no disparo drástico do P95 e P99. Se uma execução
demonstrar um P50 estável e um P99 na ordem dos milhares de
milissegundos, conclui-se formalmente que o motor gere mal as filas de
concorrência massiva em nós centralizados (_Super-Hubs Zipfianos_),
sacrificando uma percentagem reduzida de clientes com esperas
catastróficas para manter a estabilidade global.

O somatório desta arquitetura permite cruzar a cronologia exata dos
milissegundos (_Timestamp_) do colapso no P99 com os picos de
`rocksdb_block_cache_usage`, gerando um relatório em formato CSV
incontestável sobre a integridade arquitetural do motor Multi-Modelo em
avaliação.
