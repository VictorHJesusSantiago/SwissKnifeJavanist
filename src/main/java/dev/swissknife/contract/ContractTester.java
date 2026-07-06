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

    public Object executeAny(Path contractFile) throws IOException, InterruptedException {
        var root = Json.object(Files.readString(contractFile));
        if (root.get("contracts") instanceof List<?> contracts) {
            List<Result> results = new ArrayList<>();
            Map<String, String> variables = new LinkedHashMap<>();
            if (root.get("variables") instanceof Map<?, ?> map)
                map.forEach((k, v) -> variables.put(String.valueOf(k), resolve(String.valueOf(v), variables)));
            for (Object item : contracts) {
                @SuppressWarnings("unchecked") var contract = (Map<String, Object>) item;
                results.add(execute(contract, variables));
            }
            return new SuiteResult(results.stream().allMatch(Result::passed), results.size(),
                results.stream().filter(Result::passed).count(), results);
        }
        return execute(root, new LinkedHashMap<>());
    }

    public Result execute(Path contractFile) throws IOException, InterruptedException {
        return execute(Json.object(Files.readString(contractFile)), new LinkedHashMap<>());
    }

    private Result execute(Map<String, Object> contract, Map<String, String> variables)
        throws IOException, InterruptedException {
        String method = resolve(text(contract, "method", "GET"), variables);
        String url = resolve(text(contract, "url", null), variables);
        if (url == null) throw new IllegalArgumentException("url é obrigatória");
        url = addQuery(url, contract.get("query"), variables);
        String body = body(contract, variables);
        int timeout = number(contract, "timeoutMs", 15_000);
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeout))
            .header("Content-Type", text(contract, "contentType", "application/json"));
        headers(contract.get("headers"), variables).forEach(builder::header);
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
        validateJsonPaths(contract.get("jsonPath"), response.body(), failures, variables);
        int maximum = number(contract, "maxResponseTimeMs", 0);
        if (maximum > 0 && duration > maximum)
            failures.add("Tempo máximo " + maximum + "ms, recebido " + duration + "ms");
        extract(contract.get("extract"), response.body(), variables);
        return new Result(failures.isEmpty(), response.statusCode(), response.body(), failures,
            duration, response.headers().map(), Instant.now().toString());
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
