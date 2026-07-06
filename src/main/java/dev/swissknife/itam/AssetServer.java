package dev.swissknife.itam;

import com.sun.net.httpserver.*;
import dev.swissknife.server.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

public final class AssetServer {
    private final HttpServer server;
    private final JsonStore store;

    public AssetServer(int port, Path database) throws IOException {
        store = new JsonStore(database);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", e -> HttpSupport.json(e, 200, Map.of("status", "UP", "service", "itam")));
        server.createContext("/ready", e -> HttpSupport.json(e, 200, Map.of("status", "READY", "records", store.all().size())));
        server.createContext("/version", e -> HttpSupport.json(e, 200, Map.of("service", "itam", "apiVersion", "v1")));
        server.createContext("/api/v1/assets", HttpSupport.authenticated(this::handle));
        server.createContext("/api/v1/inventory", HttpSupport.authenticated(this::inventory));
    }
    public void start() { server.start(); }
    public void stop() { server.stop(1); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange e) throws IOException {
        String id = HttpSupport.resourceId(e, "/api/v1/assets");
        switch (e.getRequestMethod()) {
            case "GET" -> {
                if (id == null) HttpSupport.json(e, 200, HttpSupport.filter(store.all(), HttpSupport.query(e)));
                else store.find(id).ifPresentOrElse(v -> send(e, 200, v), () -> send(e, 404, Map.of("error", "Ativo não encontrado")));
            }
            case "POST" -> {
                var asset = HttpSupport.body(e); validate(asset);
                asset.put("status", asset.getOrDefault("status", "IN_USE"));
                asset.put("createdAt", Instant.now().toString());
                HttpSupport.json(e, 201, store.save(asset));
            }
            case "PUT" -> {
                if (id == null || store.find(id).isEmpty()) { HttpSupport.json(e, 404, Map.of("error", "Ativo não encontrado")); return; }
                var asset = HttpSupport.body(e); asset.put("id", id); validate(asset);
                asset.put("updatedAt", Instant.now().toString());
                HttpSupport.json(e, 200, store.save(asset));
            }
            case "DELETE" -> HttpSupport.json(e, store.delete(id) ? 200 : 404, Map.of("deleted", id));
            default -> HttpSupport.json(e, 405, Map.of("error", "Método não permitido"));
        }
    }
    private void inventory(HttpExchange e) throws IOException {
        Map<String, Long> types = new TreeMap<>(), statuses = new TreeMap<>();
        double value = 0;
        for (var asset : store.all()) {
            types.merge(String.valueOf(asset.getOrDefault("type", "OTHER")), 1L, Long::sum);
            statuses.merge(String.valueOf(asset.getOrDefault("status", "UNKNOWN")), 1L, Long::sum);
            if (asset.get("purchaseValue") instanceof Number n) value += n.doubleValue();
        }
        HttpSupport.json(e, 200, Map.of("total", store.all().size(), "totalPurchaseValue", value, "byType", types, "byStatus", statuses));
    }
    private void validate(Map<String, Object> asset) {
        for (var field : List.of("tag", "name", "type"))
            if (!asset.containsKey(field) || String.valueOf(asset.get(field)).isBlank()) throw new IllegalArgumentException(field + " é obrigatório");
    }
    private void send(HttpExchange e, int status, Object value) {
        try { HttpSupport.json(e, status, value); } catch (IOException ex) { throw new RuntimeException(ex); }
    }
}
