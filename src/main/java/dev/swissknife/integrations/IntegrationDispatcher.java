package dev.swissknife.integrations;

import dev.swissknife.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Adaptador configurável para webhooks, chats, trackers e plataformas de segurança. */
public final class IntegrationDispatcher {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Envia vários findings (JSON array) para o mesmo destino, um dispatch por item. */
    @SuppressWarnings("unchecked")
    public List<DispatchResult> dispatchBatch(Path configuration, Path payloadsFile, boolean dryRun)
        throws IOException, InterruptedException {
        Object parsed = Json.parse(Files.readString(payloadsFile));
        if (!(parsed instanceof List<?> items)) throw new IllegalArgumentException("dispatchBatch exige um array JSON de findings");
        List<DispatchResult> results = new ArrayList<>();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(configuration)) { properties.load(reader); }
        String provider = properties.getProperty("provider", "webhook").toLowerCase(Locale.ROOT);
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> payload = (Map<String, Object>) map;
            Request request = request(provider, properties, payload, dryRun);
            results.add(execute(properties, provider, request, dryRun));
            if (!dryRun && items.size() > 1) Thread.sleep(200);
        }
        return results;
    }

    public DispatchResult dispatch(Path configuration, Path payloadFile, boolean dryRun)
        throws IOException, InterruptedException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(configuration)) { properties.load(reader); }
        String provider = properties.getProperty("provider", "webhook").toLowerCase(Locale.ROOT);
        Map<String, Object> payload = Json.object(Files.readString(payloadFile));
        Request request = request(provider, properties, payload, dryRun);
        return execute(properties, provider, request, dryRun);
    }

    private DispatchResult execute(Properties properties, String provider, Request request, boolean dryRun)
        throws IOException, InterruptedException {
        if (dryRun) return new DispatchResult(provider, request.url(), request.method(),
            redact(request.headers()), redactBody(request.body()), 0, "", true, Instant.now().toString());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
            .timeout(Duration.ofSeconds(integer(properties, "timeoutSeconds", 30)));
        request.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.method(request.method(),
            request.body().isBlank() ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body())).build();
        int attempts = Math.max(1, integer(properties, "attempts", 3));
        HttpResponse<String> response = null;
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 500 && response.statusCode() != 429) break;
            } catch (IOException e) { last = e; }
            if (attempt < attempts) Thread.sleep(Math.min(5_000, 250L << attempt));
        }
        if (response == null && last != null) throw last;
        boolean success = response != null && response.statusCode() >= 200 && response.statusCode() < 300;
        return new DispatchResult(provider, request.url(), request.method(), redact(request.headers()),
            redactBody(request.body()), response == null ? 0 : response.statusCode(),
            response == null ? "" : truncate(response.body()), success, Instant.now().toString());
    }

    private Request request(String provider, Properties p, Map<String, Object> payload, boolean dryRun) {
        String url = required(p, "url");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        String tokenEnv = p.getProperty("tokenEnv");
        if (tokenEnv != null && !tokenEnv.isBlank()) {
            String token = System.getenv(tokenEnv);
            if (token == null && !dryRun) throw new IllegalArgumentException("Variável de token ausente: " + tokenEnv);
            if (token == null) token = "<" + tokenEnv + ">";
            headers.put(p.getProperty("authHeader", "Authorization"),
                p.getProperty("authPrefix", "Bearer ") + token);
        }
        p.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (name.startsWith("header.")) headers.put(name.substring(7), resolve(String.valueOf(value), payload));
        });
        Object body = switch (provider) {
            case "slack" -> Map.of("text", message(payload));
            case "teams" -> Map.of("type", "message", "attachments", List.of(Map.of(
                "contentType", "application/vnd.microsoft.card.adaptive",
                "content", Map.of("type", "AdaptiveCard", "version", "1.4",
                    "body", List.of(Map.of("type", "TextBlock", "wrap", true, "text", message(payload)))))));
            case "github" -> Map.of("title", title(payload), "body", message(payload),
                "labels", labels(payload));
            case "gitlab" -> Map.of("title", title(payload), "description", message(payload),
                "labels", String.join(",", labels(payload)));
            case "jira" -> Map.of("fields", Map.of("project", Map.of("key", p.getProperty("project", "PROJ")),
                "summary", title(payload), "description", message(payload),
                "issuetype", Map.of("name", p.getProperty("issueType", "Task"))));
            case "azure-boards" -> List.of(
                Map.of("op", "add", "path", "/fields/System.Title", "value", title(payload)),
                Map.of("op", "add", "path", "/fields/System.Description", "value", message(payload)));
            case "sentry" -> Map.of("message", message(payload), "level", severity(payload).toLowerCase(Locale.ROOT),
                "extra", payload);
            case "defectdojo" -> Map.of("title", title(payload), "description", message(payload),
                "severity", severity(payload), "active", true, "verified", false);
            case "backstage", "servicenow", "webhook" -> payload;
            default -> throw new IllegalArgumentException("Provider não suportado: " + provider);
        };
        String bodyText = Json.stringify(body);
        if (provider.equals("azure-boards")) headers.put("Content-Type", "application/json-patch+json");
        String signingSecretEnv = p.getProperty("signingSecretEnv");
        if (signingSecretEnv != null && !signingSecretEnv.isBlank()) {
            String secret = System.getenv(signingSecretEnv);
            if (secret == null && !dryRun) throw new IllegalArgumentException("Variável de assinatura ausente: " + signingSecretEnv);
            headers.put("X-Signature-256", "sha256=" + (secret == null ? "<sem-assinatura-em-dry-run>" : hmac(secret, bodyText)));
        }
        return new Request(url, p.getProperty("method", "POST").toUpperCase(Locale.ROOT), headers, bodyText);
    }
    private String hmac(String key, String body) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String message(Map<String, Object> payload) {
        Object value = first(payload, "message", "description", "summary", "title");
        return value == null ? Json.stringify(payload) : String.valueOf(value);
    }
    private String title(Map<String, Object> payload) {
        Object value = first(payload, "title", "summary", "kind");
        return value == null ? "Finding SwissKnife" : String.valueOf(value);
    }
    private String severity(Map<String, Object> payload) {
        return String.valueOf(payload.getOrDefault("severity", "MEDIUM")).toUpperCase(Locale.ROOT);
    }
    private List<String> labels(Map<String, Object> payload) {
        List<String> labels = new ArrayList<>(List.of("swissknife", severity(payload).toLowerCase(Locale.ROOT)));
        if (payload.get("kind") != null) labels.add(String.valueOf(payload.get("kind")).toLowerCase(Locale.ROOT));
        return labels;
    }
    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key); return null;
    }
    private String resolve(String template, Map<String, Object> payload) {
        String result = template;
        for (var entry : payload.entrySet())
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        return result;
    }
    private Map<String, String> redact(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>(headers);
        result.replaceAll((key, value) -> key.toLowerCase(Locale.ROOT).matches(".*(authorization|token|key).*")
            ? "***" : value);
        return result;
    }
    private String redactBody(String body) {
        return body.replaceAll("(?i)(\"(?:password|secret|token|apiKey)\"\\s*:\\s*\")[^\"]+", "$1***");
    }
    private String truncate(String value) { return value.length() > 2_000 ? value.substring(0, 2_000) + "…" : value; }
    private String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " é obrigatório");
        return value;
    }
    private int integer(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(fallback))); }
        catch (Exception e) { return fallback; }
    }
    private record Request(String url, String method, Map<String, String> headers, String body) {}
    public record DispatchResult(String provider, String url, String method,
                                 Map<String, String> headers, String requestPreview,
                                 int status, String response, boolean success,
                                 String executedAt) {}
}
