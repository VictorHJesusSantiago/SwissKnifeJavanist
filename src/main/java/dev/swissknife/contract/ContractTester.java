package dev.swissknife.contract;

import dev.swissknife.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

public final class ContractTester {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Object executeAny(Path contractFile) throws IOException, InterruptedException, java.util.concurrent.ExecutionException {
        var root = Json.object(Files.readString(contractFile));
        if (root.get("contracts") instanceof List<?> contracts) {
            Map<String, String> variables = new LinkedHashMap<>();
            if (root.get("variables") instanceof Map<?, ?> map)
                map.forEach((k, v) -> variables.put(String.valueOf(k), resolve(String.valueOf(v), variables)));
            boolean redact = Boolean.TRUE.equals(root.get("redactSensitive"));
            boolean parallel = Boolean.TRUE.equals(root.get("parallel"));
            runPhase(root.get("setup"), variables, redact);
            List<Result> results;
            if (parallel) {
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                List<java.util.concurrent.Future<Result>> futures = new ArrayList<>();
                for (Object item : contracts) {
                    @SuppressWarnings("unchecked") var contract = (Map<String, Object>) item;
                    Map<String, String> snapshot = new LinkedHashMap<>(variables);
                    futures.add(executor.submit(() -> execute(contract, snapshot, redact)));
                }
                results = new ArrayList<>();
                for (var future : futures) results.add(future.get());
                executor.shutdown();
            } else {
                results = new ArrayList<>();
                for (Object item : contracts) {
                    @SuppressWarnings("unchecked") var contract = (Map<String, Object>) item;
                    results.add(execute(contract, variables, redact));
                }
            }
            runPhase(root.get("teardown"), variables, redact);
            return new SuiteResult(results.stream().allMatch(Result::passed), results.size(),
                results.stream().filter(Result::passed).count(), results);
        }
        return execute(root, new LinkedHashMap<>(), false);
    }

    @SuppressWarnings("unchecked")
    private void runPhase(Object phase, Map<String, String> variables, boolean redact) throws IOException, InterruptedException {
        if (!(phase instanceof List<?> steps)) return;
        for (Object item : steps) execute((Map<String, Object>) item, variables, redact);
    }

    public Result execute(Path contractFile) throws IOException, InterruptedException {
        return execute(Json.object(Files.readString(contractFile)), new LinkedHashMap<>(), false);
    }

    private Result execute(Map<String, Object> contract, Map<String, String> variables, boolean redact)
        throws IOException, InterruptedException {
        String method = resolve(text(contract, "method", "GET"), variables);
        String url = resolve(text(contract, "url", null), variables);
        if (url == null) throw new IllegalArgumentException("url é obrigatória");
        url = addQuery(url, contract.get("query"), variables);
        String body = body(contract, variables);
        String contentType = contract.get("form") != null ? "application/x-www-form-urlencoded"
            : text(contract, "contentType", "application/json");
        int timeout = number(contract, "timeoutMs", 15_000);
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeout))
            .header("Content-Type", contentType);
        headers(contract.get("headers"), variables).forEach(builder::header);
        applyCookies(builder, contract.get("cookies"), variables);
        applyAuth(builder, contract, variables);
        var request = builder.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build();
        int retries = number(contract, "retries", 0);
        HttpResponse<String> response = null;
        IOException last = null;
        long started = System.nanoTime();
        for (int attempt = 0; attempt <= retries; attempt++) {
            try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); last = null; break; }
            catch (IOException e) { last = e; if (attempt < retries) Thread.sleep(Math.min(1_000, 100L << attempt)); }
        }
        if (last != null) throw last;
        long duration = (System.nanoTime() - started) / 1_000_000;
        int expected = ((Number) contract.getOrDefault("expectedStatus", 200L)).intValue();
        List<String> failures = new ArrayList<>();
        if (response.statusCode() != expected) failures.add("Status esperado " + expected + ", recebido " + response.statusCode());
        Object contains = contract.get("bodyContains");
        if (contains instanceof List<?> list) {
            for (var value : list) if (!response.body().contains(String.valueOf(value)))
                failures.add("Corpo não contém: " + value);
        }
        Object notContains = contract.get("bodyNotContains");
        if (notContains instanceof List<?> list)
            for (var value : list) if (response.body().contains(String.valueOf(value)))
                failures.add("Corpo contém valor proibido: " + value);
        if (contract.get("bodyMatches") != null &&
            !Pattern.compile(resolve(String.valueOf(contract.get("bodyMatches")), variables), Pattern.DOTALL)
                .matcher(response.body()).find())
            failures.add("Corpo não corresponde à expressão regular esperada");
        validateHeaders(contract.get("expectedHeaders"), response, failures, variables);
        validateCookies(contract.get("expectedCookies"), response, failures, variables);
        validateJsonPaths(contract.get("jsonPath"), response.body(), failures, variables);
        if (contract.get("jsonSchema") instanceof Map<?, ?> schema) validateJsonSchema(schema, response.body(), failures);
        int maximum = number(contract, "maxResponseTimeMs", 0);
        if (maximum > 0 && duration > maximum)
            failures.add("Tempo máximo " + maximum + "ms, recebido " + duration + "ms");
        if (contract.get("snapshot") != null) validateSnapshot(contract, response.body(), failures);
        extract(contract.get("extract"), response.body(), variables);
        Map<String, List<String>> responseHeaders = redact ? redactHeaders(response.headers().map()) : response.headers().map();
        return new Result(failures.isEmpty(), response.statusCode(), response.body(), failures,
            duration, responseHeaders, Instant.now().toString());
    }

    /**
     * Validador mínimo de JSON Schema (subconjunto: type, required, properties, items, enum,
     * minimum/maximum, minLength/maxLength) — suficiente para contratos de API sem depender de bibliotecas externas.
     */
    private void validateJsonSchema(Map<?, ?> schema, String body, List<String> failures) {
        Object parsed;
        try { parsed = Json.parse(body); } catch (Exception e) { failures.add("Resposta não é JSON válido para validação de schema"); return; }
        validateSchemaNode(schema, parsed, "$", failures);
    }
    @SuppressWarnings("unchecked")
    private void validateSchemaNode(Map<?, ?> schema, Object value, String path, List<String> failures) {
        Object typeDeclared = schema.get("type");
        if (typeDeclared != null && !matchesType(String.valueOf(typeDeclared), value))
            failures.add("Schema " + path + ": esperado tipo " + typeDeclared + ", obtido " + jsonTypeName(value));
        if (schema.get("enum") instanceof List<?> allowed && allowed.stream().noneMatch(v -> Objects.equals(v, value)))
            failures.add("Schema " + path + ": valor " + value + " não está no enum permitido");
        if (value instanceof Number number) {
            if (schema.get("minimum") instanceof Number min && number.doubleValue() < min.doubleValue())
                failures.add("Schema " + path + ": " + number + " abaixo do mínimo " + min);
            if (schema.get("maximum") instanceof Number max && number.doubleValue() > max.doubleValue())
                failures.add("Schema " + path + ": " + number + " acima do máximo " + max);
        }
        if (value instanceof String text) {
            if (schema.get("minLength") instanceof Number min && text.length() < min.intValue())
                failures.add("Schema " + path + ": comprimento " + text.length() + " abaixo do mínimo " + min);
            if (schema.get("maxLength") instanceof Number max && text.length() > max.intValue())
                failures.add("Schema " + path + ": comprimento " + text.length() + " acima do máximo " + max);
        }
        if (value instanceof Map<?, ?> object) {
            if (schema.get("required") instanceof List<?> required)
                for (Object key : required) if (!object.containsKey(String.valueOf(key)))
                    failures.add("Schema " + path + ": propriedade obrigatória ausente: " + key);
            if (schema.get("properties") instanceof Map<?, ?> properties)
                properties.forEach((key, subSchema) -> {
                    if (object.containsKey(key) && subSchema instanceof Map<?, ?> sub)
                        validateSchemaNode(sub, object.get(key), path + "." + key, failures);
                });
        }
        if (value instanceof List<?> list && schema.get("items") instanceof Map<?, ?> itemSchema)
            for (int i = 0; i < list.size(); i++) validateSchemaNode(itemSchema, list.get(i), path + "[" + i + "]", failures);
    }
    private boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue());
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "null" -> value == null;
            default -> true;
        };
    }
    private String jsonTypeName(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Map) return "object";
        if (value instanceof List) return "array";
        return value.getClass().getSimpleName();
    }

    private void applyCookies(HttpRequest.Builder builder, Object value, Map<String, String> variables) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return;
        String cookieHeader = map.entrySet().stream()
            .map(e -> e.getKey() + "=" + resolve(String.valueOf(e.getValue()), variables))
            .reduce((a, b) -> a + "; " + b).orElse("");
        builder.header("Cookie", cookieHeader);
    }

    private void validateCookies(Object value, HttpResponse<String> response, List<String> failures,
                                 Map<String, String> variables) {
        if (!(value instanceof Map<?, ?> map)) return;
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        map.forEach((name, expected) -> {
            String wanted = resolve(String.valueOf(expected), variables);
            boolean found = setCookies.stream().anyMatch(c -> c.startsWith(name + "=" + wanted));
            if (!found) failures.add("Cookie " + name + " esperado com valor " + wanted + " ausente na resposta");
        });
    }

    private void validateSnapshot(Map<String, Object> contract, String body, List<String> failures) {
        try {
            Path snapshotPath = Path.of(String.valueOf(contract.get("snapshot")));
            if (!Files.exists(snapshotPath)) {
                Path parent = snapshotPath.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(snapshotPath, body);
                return;
            }
            String expected = Files.readString(snapshotPath);
            if (!expected.equals(body)) failures.add("Resposta diverge do snapshot em " + snapshotPath);
        } catch (IOException e) { failures.add("Falha ao ler/gravar snapshot: " + e.getMessage()); }
    }

    private Map<String, List<String>> redactHeaders(Map<String, List<String>> headers) {
        Set<String> sensitive = Set.of("authorization", "set-cookie", "cookie", "x-api-key");
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((key, values) -> result.put(key,
            sensitive.contains(key.toLowerCase(Locale.ROOT)) ? List.of("[REDACTED]") : values));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> headers(Object value, Map<String, String> variables) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map)
            map.forEach((k, v) -> result.put(String.valueOf(k), resolve(String.valueOf(v), variables)));
        return result;
    }
    private void applyAuth(HttpRequest.Builder builder, Map<String, Object> contract, Map<String, String> variables) {
        if (!(contract.get("auth") instanceof Map<?, ?> auth)) return;
        String type = String.valueOf(auth.get("type")).toLowerCase(Locale.ROOT);
        if (type.equals("bearer")) builder.header("Authorization", "Bearer " + resolve(String.valueOf(auth.get("token")), variables));
        else if (type.equals("basic")) {
            String credentials = resolve(String.valueOf(auth.get("username")), variables) + ":" +
                resolve(String.valueOf(auth.get("password")), variables);
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes()));
        } else if (type.equals("apikey")) builder.header(String.valueOf(auth.get("header")),
            resolve(String.valueOf(auth.get("value")), variables));
    }
    private String addQuery(String url, Object value, Map<String, String> variables) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return url;
        String query = map.entrySet().stream().map(e -> encode(String.valueOf(e.getKey())) + "=" +
            encode(resolve(String.valueOf(e.getValue()), variables))).reduce((a,b) -> a + "&" + b).orElse("");
        return url + (url.contains("?") ? "&" : "?") + query;
    }
    private String body(Map<String, Object> contract, Map<String, String> variables) {
        if (contract.get("form") instanceof Map<?, ?> form) {
            return form.entrySet().stream().map(e -> encode(String.valueOf(e.getKey())) + "=" +
                encode(resolve(String.valueOf(e.getValue()), variables))).reduce((a, b) -> a + "&" + b).orElse("");
        }
        Object body = contract.get("body");
        if (body == null) return "";
        return resolve(body instanceof String ? (String) body : Json.stringify(body), variables);
    }
    private void validateHeaders(Object value, HttpResponse<String> response, List<String> failures,
                                 Map<String, String> variables) {
        if (!(value instanceof Map<?, ?> map)) return;
        map.forEach((k, v) -> {
            String actual = response.headers().firstValue(String.valueOf(k)).orElse(null);
            String expected = resolve(String.valueOf(v), variables);
            if (!Objects.equals(expected, actual))
                failures.add("Header " + k + " esperado " + expected + ", recebido " + actual);
        });
    }
    private void validateJsonPaths(Object value, String body, List<String> failures, Map<String, String> variables) {
        if (!(value instanceof Map<?, ?> expectations)) return;
        Object parsed;
        try { parsed = Json.parse(body); } catch (Exception e) {
            failures.add("Resposta não é JSON válido para validação JSONPath"); return;
        }
        expectations.forEach((path, expected) -> {
            Object actual = jsonPath(parsed, String.valueOf(path));
            String wanted = resolve(String.valueOf(expected), variables);
            if (actual == null || !String.valueOf(actual).equals(wanted))
                failures.add("JSONPath " + path + " esperado " + wanted + ", recebido " + actual);
        });
    }
    private void extract(Object value, String body, Map<String, String> variables) {
        if (!(value instanceof Map<?, ?> extractions)) return;
        Object parsed;
        try { parsed = Json.parse(body); } catch (Exception ignored) { return; }
        extractions.forEach((name, path) -> {
            Object found = jsonPath(parsed, String.valueOf(path));
            if (found != null) variables.put(String.valueOf(name), String.valueOf(found));
        });
    }
    private Object jsonPath(Object value, String path) {
        String normalized = path.replaceFirst("^\\$\\.?", "");
        Object current = value;
        for (String part : normalized.split("\\.")) {
            Matcher segment = Pattern.compile("([^\\[]+)(?:\\[(\\d+)])?").matcher(part);
            if (!segment.matches() || !(current instanceof Map<?, ?> map)) return null;
            current = map.get(segment.group(1));
            if (segment.group(2) != null) {
                if (!(current instanceof List<?> list)) return null;
                int index = Integer.parseInt(segment.group(2));
                current = index < list.size() ? list.get(index) : null;
            }
        }
        return current;
    }
    private String resolve(String value, Map<String, String> variables) {
        if (value == null) return null;
        Matcher matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = variables.getOrDefault(key, System.getenv(key));
            if (replacement == null) throw new IllegalArgumentException("Variável ausente no contrato: " + key);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(out).toString();
    }
    private int number(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number n ? n.intValue() : fallback;
    }
    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
    private String text(Map<String, Object> map, String key, String defaultValue) {
        var value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
    public record Result(boolean passed, int actualStatus, String responseBody, List<String> failures,
                         long durationMs, Map<String, List<String>> responseHeaders, String executedAt) {}
    public record SuiteResult(boolean passed, int total, long passedCount, List<Result> results) {}
}
