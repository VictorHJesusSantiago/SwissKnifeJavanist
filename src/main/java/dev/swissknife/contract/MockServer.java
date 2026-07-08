package dev.swissknife.contract;

import com.sun.net.httpserver.HttpServer;
import dev.swissknife.util.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Servidor de stubs local para testes de contrato offline: lê rotas de um JSON
 * ({"routes":[{"method":"GET","path":"/x","status":200,"body":{...}}]}) e responde
 * exatamente o que foi configurado, sem depender de nenhum serviço externo.
 */
public final class MockServer implements AutoCloseable {
    private final HttpServer server;

    private MockServer(HttpServer server) { this.server = server; }

    public static MockServer start(Path routesFile, int port) throws IOException {
        Map<String, Object> config = Json.object(Files.readString(routesFile));
        List<Route> routes = new ArrayList<>();
        if (config.get("routes") instanceof List<?> list)
            for (Object item : list) if (item instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> route = (Map<String, Object>) raw;
                routes.add(new Route(String.valueOf(route.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT),
                    String.valueOf(route.getOrDefault("path", "/")),
                    ((Number) route.getOrDefault("status", 200)).intValue(), route.get("body")));
            }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Route match = routes.stream().filter(r -> r.method.equals(method) && r.path.equals(path)).findFirst().orElse(null);
            byte[] body = match == null
                ? Json.stringify(Map.of("error", "Rota não configurada: " + method + " " + path)).getBytes(StandardCharsets.UTF_8)
                : Json.stringify(match.body == null ? Map.of() : match.body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(match == null ? 404 : match.status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        return new MockServer(server);
    }

    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }

    private record Route(String method, String path, int status, Object body) {}
}
