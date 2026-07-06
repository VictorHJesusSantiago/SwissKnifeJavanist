package dev.swissknife.server;

import com.sun.net.httpserver.*;
import dev.swissknife.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.net.URLDecoder;

public final class HttpSupport {
    private static final int MAX_BODY = Integer.parseInt(
        System.getenv().getOrDefault("SWISSKNIFE_MAX_PAYLOAD_BYTES", "2097152"));
    private HttpSupport() {}

    public static void json(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Request-ID", requestId(exchange));
        String origin = System.getenv("SWISSKNIFE_CORS_ORIGIN");
        if (origin != null && !origin.isBlank())
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    public static Map<String, Object> body(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY)
                throw new IllegalArgumentException("Payload excede o limite de " + MAX_BODY + " bytes");
            var text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isBlank()) return new LinkedHashMap<>();
            return Json.object(text);
        }
    }

    public static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(decode(key), decode(value));
        }
        return result;
    }

    public static List<Map<String, Object>> filter(List<Map<String, Object>> values,
                                                   Map<String, String> query) {
        String q = query.getOrDefault("q", "").toLowerCase(Locale.ROOT);
        Set<String> reserved = Set.of("q", "limit", "offset", "sort", "direction");
        var stream = values.stream().filter(item -> q.isBlank() ||
            item.values().stream().anyMatch(v -> String.valueOf(v).toLowerCase(Locale.ROOT).contains(q)));
        for (var criterion : query.entrySet()) {
            if (reserved.contains(criterion.getKey()) || criterion.getValue().isBlank()) continue;
            stream = stream.filter(item -> criterion.getValue().equalsIgnoreCase(
                String.valueOf(item.get(criterion.getKey()))));
        }
        List<Map<String, Object>> filtered = new ArrayList<>(stream.toList());
        String sort = query.get("sort");
        if (sort != null) {
            Comparator<Map<String, Object>> comparator = Comparator.comparing(
                item -> String.valueOf(item.getOrDefault(sort, "")), String.CASE_INSENSITIVE_ORDER);
            if ("desc".equalsIgnoreCase(query.get("direction"))) comparator = comparator.reversed();
            filtered.sort(comparator);
        }
        int offset = bounded(query.get("offset"), 0, 0, filtered.size());
        int limit = bounded(query.get("limit"), 100, 1, 1000);
        return filtered.subList(Math.min(offset, filtered.size()), Math.min(filtered.size(), offset + limit));
    }

    public static String resourceId(HttpExchange exchange, String root) {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(root) || path.equals(root + "/")) return null;
        return path.substring(root.length()).replaceFirst("^/", "").split("/")[0];
    }

    public static HttpHandler authenticated(HttpHandler next) {
        return exchange -> {
            try {
                if (exchange.getRequestMethod().equals("OPTIONS")) {
                    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization,Content-Type,X-Request-ID");
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }
                String configured = System.getenv("SWISSKNIFE_API_TOKEN");
                if (configured != null && !configured.isBlank()) {
                    String actual = exchange.getRequestHeaders().getFirst("Authorization");
                    if (actual == null || !MessageDigest.isEqual(("Bearer " + configured).getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8))) {
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

    private static String requestId(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-Request-ID");
        return supplied != null && supplied.matches("[A-Za-z0-9_.-]{1,100}") ? supplied : UUID.randomUUID().toString();
    }
    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
    private static int bounded(String value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        try { return Math.max(minimum, Math.min(maximum, Integer.parseInt(value))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Parâmetro numérico inválido: " + value); }
    }
}
