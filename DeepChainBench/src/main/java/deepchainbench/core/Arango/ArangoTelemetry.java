/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package deepchainbench.core.Arango;

import deepchainbench.core.TelemetryEngine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public class ArangoTelemetry extends TelemetryEngine {

    private final HttpClient httpClient;
    private final HttpRequest request;

    public ArangoTelemetry(String outputFile, String host, int port, String dbName, String user, String password) {
        super(outputFile);

        // Endpoint que contém as métricas (Prometheus format)
        String url = String.format("http://%s:%d/_db/%s/_admin/metrics", host, port, dbName);
        System.err.println(url);

        String auth = user + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        this.request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Basic " + encodedAuth)
                .GET()
                .build();
    }

    @Override
    public double getSystemRamUsedMB() {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String[] lines = response.body().split("\n");
            for (String line : lines) {
                if (line.startsWith("rocksdb_block_cache_usage")) {
                    String[] parts = line.split("\\s+");
                    double bytes = Double.parseDouble(parts[1]);
                    return bytes / (1024.0 * 1024.0); // Retorna double (ex: 2.14 MB)
                }
            }
        } catch (Exception e) {
            return -1.0;
        }
        return -1.0;
    }

}
