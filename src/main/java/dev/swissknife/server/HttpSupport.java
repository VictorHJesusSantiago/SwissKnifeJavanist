package dev.swissknife.server;

import com.sun.net.httpserver.*;
import dev.swissknife.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HttpSupport {
    private HttpSupport() {}

    public static void json(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    public static Map<String, Object> body(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            var text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) return new LinkedHashMap<>();
            return Json.object(text);
        }
    }

    public static String resourceId(HttpExchange exchange, String root) {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(root) || path.equals(root + "/")) return null;
        return path.substring(root.length()).replaceFirst("^/", "").split("/")[0];
    }

    public static HttpHandler authenticated(HttpHandler next) {
        return exchange -> {
            try {
                String configured = System.getenv("SWISSKNIFE_API_TOKEN");
                if (configured != null && !configured.isBlank()) {
                    String actual = exchange.getRequestHeaders().getFirst("Authorization");
                    if (!("Bearer " + configured).equals(actual)) {
                        json(exchange, 401, Map.of("error", "Não autorizado"));
                        return;
                    }
                }
                next.handle(exchange);
            } catch (IllegalArgumentException e) {
                json(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace(System.err);
                json(exchange, 500, Map.of("error", "Erro interno"));
            }
        };
    }
}
