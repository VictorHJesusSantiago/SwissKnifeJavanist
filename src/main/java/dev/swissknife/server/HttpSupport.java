package dev.swissknife.server;

import com.sun.net.httpserver.*;
import dev.swissknife.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.net.URLDecoder;

public final class HttpSupport {
    private static final int MAX_BODY = Integer.parseInt(
        System.getenv().getOrDefault("SWISSKNIFE_MAX_PAYLOAD_BYTES", "2097152"));
    private static final int MAX_ATTEMPTS = Integer.parseInt(
        System.getenv().getOrDefault("SWISSKNIFE_MAX_AUTH_ATTEMPTS", "10"));
    private static final long LOCKOUT_MILLIS = Long.parseLong(
        System.getenv().getOrDefault("SWISSKNIFE_LOCKOUT_MINUTES", "5")) * 60_000;
    private static final int RATE_LIMIT_PER_MINUTE = Integer.parseInt(
        System.getenv().getOrDefault("SWISSKNIFE_RATE_LIMIT_PER_MINUTE", "120"));
    private static final Map<String, FailureWindow> FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, RateWindow> RATE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Path AUTH_LOG = Path.of(System.getenv().getOrDefault("SWISSKNIFE_AUTH_LOG", ".swissknife/auth.jsonl"));
    private static final String SCOPE_ATTRIBUTE = "swissknife.scope";
    private HttpSupport() {}

    /**
     * Origem das variáveis de ambiente consultadas por requisição (tokens, MFA, CORS).
     *
     * Existe como costura de teste: a JVM não permite alterar System.getenv no próprio processo, e
     * sem esta indireção o caminho AUTENTICADO ficava sem nenhum teste — foi exatamente por ali que
     * passou a regressão de escopo do SWISSKNIFE_API_TOKEN. Só o pacote consegue trocar a origem.
     */
    private static volatile java.util.function.UnaryOperator<String> environment = System::getenv;
    public static void environmentForTesting(Map<String, String> values) {
        environment = values == null ? System::getenv : values::get;
    }
    /** Zera janelas de rate limit/lockout e réplicas idempotentes entre casos de teste. */
    public static void resetSecurityStateForTesting() {
        FAILURES.clear(); RATE.clear(); IDEMPOTENCY.clear();
    }
    private static String env(String name) { return environment.apply(name); }

    private static final class FailureWindow { int count; long lockedUntil; }
    private static final class RateWindow { int count; long windowStart; }

    /** Lançada por requireScope/requireRole; authenticated() a converte em HTTP 403 Problem Details. */
    public static final class ForbiddenException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ForbiddenException(String message) { super(message); }
    }

    /**
     * RBAC simples baseado no escopo do token autenticado (ver SWISSKNIFE_API_TOKENS). Handlers
     * chamam isto no início de operações sensíveis (ex.: só "admin"/"write" podem escrever).
     * Quando nenhum token está configurado, o escopo efetivo é sempre "admin" (modo aberto local).
     */
    public static void requireScope(HttpExchange exchange, String... allowedScopes) {
        String scope = String.valueOf(exchange.getAttribute(SCOPE_ATTRIBUTE));
        if (Arrays.asList(allowedScopes).contains(scope) || scope.equals("admin")) return;
        throw new ForbiddenException("Escopo '" + scope + "' não tem permissão; requer um de " + Arrays.toString(allowedScopes));
    }

    public static void json(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Request-ID", requestId(exchange));
        String origin = env("SWISSKNIFE_CORS_ORIGIN");
        if (origin != null && !origin.isBlank())
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        if (status == 200 && exchange.getRequestMethod().equals("GET")) {
            String etag = "\"" + sha256(new String(body, StandardCharsets.UTF_8)).substring(0, 32) + "\"";
            exchange.getResponseHeaders().set("ETag", etag);
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (etag.equals(ifNoneMatch)) { exchange.sendResponseHeaders(304, -1); exchange.close(); return; }
        }
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    /** Resposta de erro no formato RFC 9457 Problem Details. */
    public static void problem(HttpExchange exchange, int status, String title, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", exchange.getRequestURI().getPath());
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json; charset=utf-8");
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("X-Request-ID", requestId(exchange));
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    /**
     * Suporte simples a chave de idempotência para POST: se a mesma Idempotency-Key com o
     * mesmo corpo já foi processada, repete a resposta anterior em vez de reexecutar o handler.
     */
    private static final int MAX_IDEMPOTENCY_ENTRIES = Integer.parseInt(
        System.getenv().getOrDefault("SWISSKNIFE_MAX_IDEMPOTENCY_ENTRIES", "10000"));
    private static final Map<String, IdempotentEntry> IDEMPOTENCY = new java.util.concurrent.ConcurrentHashMap<>();
    private record IdempotentEntry(String bodyHash, int status, byte[] response, long expiresAt) {}
    public static boolean replayIfIdempotent(HttpExchange exchange, String requestBody) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.isBlank()) return false;
        IdempotentEntry entry = IDEMPOTENCY.get(key);
        String hash = sha256(requestBody);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis() || !entry.bodyHash().equals(hash)) return false;
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Idempotent-Replay", "true");
        exchange.sendResponseHeaders(entry.status(), entry.response().length);
        try (var output = exchange.getResponseBody()) { output.write(entry.response()); }
        return true;
    }
    public static void rememberIdempotent(HttpExchange exchange, String requestBody, int status, Object payload) {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.isBlank()) return;
        byte[] response = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        expireIdempotency();
        IDEMPOTENCY.put(key, new IdempotentEntry(sha256(requestBody), status, response,
            System.currentTimeMillis() + Duration.ofHours(24).toMillis()));
    }
    /**
     * O mapa de idempotência é alimentado por um header controlado pelo cliente: sem expurgo ele
     * cresce sem limite (cada chave retém o corpo da resposta por 24h) e é um vetor trivial de
     * exaustão de memória. Remove entradas vencidas e, se ainda estiver acima do teto, as mais antigas.
     */
    private static void expireIdempotency() {
        long now = System.currentTimeMillis();
        IDEMPOTENCY.values().removeIf(entry -> entry.expiresAt() < now);
        if (IDEMPOTENCY.size() < MAX_IDEMPOTENCY_ENTRIES) return;
        IDEMPOTENCY.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> e.getValue().expiresAt()))
            .limit(Math.max(1, IDEMPOTENCY.size() - MAX_IDEMPOTENCY_ENTRIES + 1))
            .map(Map.Entry::getKey).toList()
            .forEach(IDEMPOTENCY::remove);
    }
    /** Hash forte para comparar corpos: hashCode() são 32 bits e uma colisão devolve a resposta de OUTRA requisição. */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static Map<String, Object> body(HttpExchange exchange) throws IOException {
        return bodyOf(rawBody(exchange));
    }
    /** Lê o corpo bruto (texto) da requisição — use junto de replayIfIdempotent/rememberIdempotent. */
    public static String rawBody(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY)
                throw new IllegalArgumentException("Payload excede o limite de " + MAX_BODY + " bytes");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    public static Map<String, Object> bodyOf(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        return Json.object(text);
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
                String clientId = exchange.getRemoteAddress() == null ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
                if (!checkRateLimit(clientId)) {
                    problem(exchange, 429, "Limite de requisições excedido", "Tente novamente em instantes");
                    return;
                }
                if (isLockedOut(clientId)) {
                    problem(exchange, 429, "Bloqueado temporariamente", "Muitas tentativas inválidas de autenticação");
                    return;
                }
                List<ApiToken> tokens = configuredTokens();
                if (!tokens.isEmpty()) {
                    String actual = exchange.getRequestHeaders().getFirst("Authorization");
                    ApiToken matched = matchToken(actual, tokens);
                    if (matched == null) {
                        registerFailure(clientId);
                        auditAuth(clientId, exchange.getRequestURI().getPath(), false, "token ausente/inválido ou expirado");
                        problem(exchange, 401, "Não autorizado", "Token ausente, inválido ou expirado");
                        return;
                    }
                    if ("admin".equals(matched.scope())) {
                        String mfaSecret = env("SWISSKNIFE_MFA_SECRET");
                        if (mfaSecret != null && !mfaSecret.isBlank()) {
                            String submittedCode = exchange.getRequestHeaders().getFirst("X-MFA-Code");
                            if (submittedCode == null || !Totp.verify(mfaSecret, submittedCode)) {
                                registerFailure(clientId);
                                auditAuth(clientId, exchange.getRequestURI().getPath(), false, "MFA ausente/inválido para escopo admin");
                                problem(exchange, 401, "MFA obrigatório", "Informe um código TOTP válido no header X-MFA-Code");
                                return;
                            }
                        }
                    }
                    clearFailures(clientId);
                    auditAuth(clientId, exchange.getRequestURI().getPath(), true, "escopo=" + matched.scope());
                    exchange.setAttribute(SCOPE_ATTRIBUTE, matched.scope());
                } else {
                    exchange.setAttribute(SCOPE_ATTRIBUTE, "admin");
                }
                next.handle(exchange);
            } catch (ForbiddenException e) {
                problem(exchange, 403, "Acesso negado", e.getMessage());
            } catch (IllegalArgumentException e) {
                problem(exchange, 400, "Requisição inválida", e.getMessage());
            } catch (Exception e) {
                e.printStackTrace(System.err);
                problem(exchange, 500, "Erro interno", "Falha inesperada ao processar a requisição");
            }
        };
    }

    /**
     * Lê SWISSKNIFE_API_TOKEN (compatibilidade) e SWISSKNIFE_API_TOKENS (múltiplos, separados por vírgula,
     * no formato token[:escopo[:expiraEmISO]]) e retorna somente os ainda válidos (não expirados).
     */
    private static List<ApiToken> configuredTokens() {
        List<ApiToken> tokens = new ArrayList<>();
        String single = env("SWISSKNIFE_API_TOKEN");
        if (single != null && !single.isBlank()) tokens.add(new ApiToken(single, "admin", null));
        String multiple = env("SWISSKNIFE_API_TOKENS");
        if (multiple != null && !multiple.isBlank()) {
            for (String entry : multiple.split(",")) {
                String[] parts = entry.trim().split(":", 3);
                if (parts[0].isBlank()) continue;
                String scope = parts.length > 1 ? parts[1] : "default";
                java.time.Instant expiresAt = null;
                if (parts.length > 2 && !parts[2].isBlank()) {
                    try { expiresAt = java.time.Instant.parse(parts[2]); } catch (Exception ignored) {}
                }
                tokens.add(new ApiToken(parts[0], scope, expiresAt));
            }
        }
        java.time.Instant now = java.time.Instant.now();
        return tokens.stream().filter(t -> t.expiresAt() == null || t.expiresAt().isAfter(now)).toList();
    }
    private static ApiToken matchToken(String header, List<ApiToken> tokens) {
        if (header == null) return null;
        for (ApiToken token : tokens)
            if (MessageDigest.isEqual(("Bearer " + token.value()).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8)))
                return token;
        return null;
    }
    /**
     * Teto de clientes rastreados. RATE/FAILURES são indexados pelo IP de origem — em IPv6 o
     * atacante controla um espaço de endereços praticamente infinito, então mapas sem expurgo
     * viram exaustão de memória (e, pior, o rate limit deixa de proteger porque o processo cai antes).
     */
    private static final int MAX_TRACKED_CLIENTS = Integer.parseInt(
        System.getenv().getOrDefault("SWISSKNIFE_MAX_TRACKED_CLIENTS", "50000"));

    private static boolean checkRateLimit(String clientId) {
        long now = System.currentTimeMillis();
        if (RATE.size() >= MAX_TRACKED_CLIENTS) evictStaleWindows(now);
        RateWindow window = RATE.computeIfAbsent(clientId, ignored -> new RateWindow());
        synchronized (window) {
            if (now - window.windowStart > 60_000) { window.windowStart = now; window.count = 0; }
            window.count++;
            return window.count <= RATE_LIMIT_PER_MINUTE;
        }
    }
    private static void evictStaleWindows(long now) {
        RATE.values().removeIf(window -> { synchronized (window) { return now - window.windowStart > 60_000; } });
        FAILURES.values().removeIf(window -> { synchronized (window) { return window.lockedUntil < now; } });
        if (RATE.size() >= MAX_TRACKED_CLIENTS)
            RATE.keySet().stream().limit(RATE.size() - MAX_TRACKED_CLIENTS + 1).toList().forEach(RATE::remove);
    }
    private static boolean isLockedOut(String clientId) {
        FailureWindow window = FAILURES.get(clientId);
        return window != null && window.lockedUntil > System.currentTimeMillis();
    }
    private static void registerFailure(String clientId) {
        FailureWindow window = FAILURES.computeIfAbsent(clientId, ignored -> new FailureWindow());
        synchronized (window) {
            window.count++;
            if (window.count >= MAX_ATTEMPTS) window.lockedUntil = System.currentTimeMillis() + LOCKOUT_MILLIS;
        }
    }
    private static void clearFailures(String clientId) { FAILURES.remove(clientId); }
    private static void auditAuth(String clientId, String path, boolean success, String detail) {
        try {
            Path parent = AUTH_LOG.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            String event = Json.stringify(Map.of("timestamp", java.time.Instant.now().toString(), "client", clientId,
                "path", path, "success", success, "detail", detail)) + System.lineSeparator();
            Files.writeString(AUTH_LOG, event, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {  }
    }
    private record ApiToken(String value, String scope, java.time.Instant expiresAt) {}

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
