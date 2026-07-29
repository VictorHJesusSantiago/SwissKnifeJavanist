package dev.swissknife;

import dev.swissknife.server.HttpSupport;
import dev.swissknife.server.Totp;
import dev.swissknife.vulnerability.VulnerabilityServer;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

/**
 * Cobre o caminho AUTENTICADO dos servidores — o que ServerTest não exercita (só o modo aberto sem
 * token). Foi exatamente por aqui que passou a regressão do SWISSKNIFE_API_TOKEN, que emitia o token
 * com escopo insuficiente e deixava a API somente-leitura. Um teste por invariante de autenticação.
 */
public final class AuthenticatedPathTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void run() throws Exception {
        try {
            tokenGrantsAdminAndAllowsWrite();
            missingTokenIsRejected();
            wrongTokenIsRejected();
            granularReadTokenCannotWrite();
            adminScopeRequiresMfaWhenConfigured();
        } finally {
            // Restaura o ambiente real e limpa janelas para não vazar estado para outros testes.
            HttpSupport.environmentForTesting(null);
            HttpSupport.resetSecurityStateForTesting();
        }
    }

    /** Seguir o README (definir SWISSKNIFE_API_TOKEN) deve habilitar a auth E permitir escrita. */
    private static void tokenGrantsAdminAndAllowsWrite() throws Exception {
        configure(Map.of("SWISSKNIFE_API_TOKEN", "s3cr3t"));
        withServer(port -> {
            TestSupport.equal(201, write(port, "Bearer s3cr3t", null).statusCode());
            // GET continua permitido com o mesmo token.
            TestSupport.equal(200, read(port, "Bearer s3cr3t").statusCode());
        });
    }

    private static void missingTokenIsRejected() throws Exception {
        configure(Map.of("SWISSKNIFE_API_TOKEN", "s3cr3t"));
        withServer(port -> TestSupport.equal(401, write(port, null, null).statusCode()));
    }

    private static void wrongTokenIsRejected() throws Exception {
        configure(Map.of("SWISSKNIFE_API_TOKEN", "s3cr3t"));
        withServer(port -> TestSupport.equal(401, write(port, "Bearer errado", null).statusCode()));
    }

    /** Um token com escopo "read" precisa poder ler mas ser reprovado (403) na escrita. */
    private static void granularReadTokenCannotWrite() throws Exception {
        configure(Map.of("SWISSKNIFE_API_TOKENS", "leitor:read"));
        withServer(port -> {
            TestSupport.equal(200, read(port, "Bearer leitor").statusCode());
            TestSupport.equal(403, write(port, "Bearer leitor", null).statusCode());
        });
    }

    /** Com MFA configurado, escopo admin exige um TOTP válido; ausente/errado → 401. */
    private static void adminScopeRequiresMfaWhenConfigured() throws Exception {
        String secret = Totp.generateSecret();
        configure(Map.of("SWISSKNIFE_API_TOKEN", "s3cr3t", "SWISSKNIFE_MFA_SECRET", secret));
        withServer(port -> {
            TestSupport.equal(401, write(port, "Bearer s3cr3t", null).statusCode());
            TestSupport.equal(201, write(port, "Bearer s3cr3t", Totp.currentCode(secret)).statusCode());
        });
    }

    // --- infraestrutura ---

    private static void configure(Map<String, String> env) {
        HttpSupport.resetSecurityStateForTesting();
        HttpSupport.environmentForTesting(env);
    }

    private interface PortConsumer { void accept(int port) throws Exception; }

    private static void withServer(PortConsumer body) throws Exception {
        Path temp = Files.createTempDirectory("auth-test");
        VulnerabilityServer server = new VulnerabilityServer(0, temp.resolve("vuln.db"));
        server.start();
        try { body.accept(server.port()); } finally { server.stop(); }
    }

    private static HttpResponse<String> write(int port, String authorization, String mfaCode) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/vulnerabilities"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"CVE\",\"component\":\"api\",\"severity\":\"HIGH\"}"));
        if (authorization != null) builder.header("Authorization", authorization);
        if (mfaCode != null) builder.header("X-MFA-Code", mfaCode);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> read(int port, String authorization) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/vulnerabilities")).GET();
        if (authorization != null) builder.header("Authorization", authorization);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
