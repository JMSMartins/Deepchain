/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package deepchainbench.core;

/**
 *
 * @author jorgemartins
 */
public interface DatabaseDriver {
    
    // Configura a ligação à BD
    void connect(String host, int port, String user, String password) throws Exception;
    
    // Configura o esquema/tabelas/coleções e limpa dados antigos se existirem
    void setupSchema(String nomeSchema) throws Exception;
    
    // Métodos de Ingestão Massiva
    void ingestParts(String csvPath) throws Exception;
    void ingestEdges(String csvPath) throws Exception;
    void ingestTelemetry(String jsonPath) throws Exception;
    void ingestQualityKV(String csvPath) throws Exception;
    
    // Métodos para a fase de Carga Mista (Workloads)
    void executeReadQuery() throws Exception;  // Tu vais definir as queries depois
    void executeWriteQuery() throws Exception; // Tu vais definir as escritas depois
    long getSchemaSizeInBytes() throws Exception;
    // Limpeza total no final do benchmark
    void dropDatabase() throws Exception;
    
    // Fecha as ligações abertas
    void close() throws Exception;
}