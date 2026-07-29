package dev.swissknife;

import dev.swissknife.server.JsonStore;
import dev.swissknife.sql.SlowQueryAnalyzer;
import dev.swissknife.util.Json;
import java.nio.file.*;
import java.util.*;

/**
 * Regressões da auditoria de engenharia: cada teste aqui falhava antes da correção correspondente.
 * Mantenha um teste por defeito e cite o sintoma, para que uma reintrodução seja diagnosticada pelo nome.
 */
public final class AuditRegressionTest {
    public static void run() throws Exception {
        jsonEscapesControlCharacters();
        jsonRejectsDeepNesting();
        jsonRejectsTruncatedUnicodeEscape();
        auditChainSurvivesRotation();
        slowQueryHtmlEscapesUntrustedIdentifiers();
        jsonKeepsIntegersAsIntegers();
        jsonStoreAppendsCompactsAndHonorsTombstones();
    }

    /**
     * Antes: `cond ? Double.parseDouble(n) : Long.parseLong(n)` sofria promoção numérica binária
     * (JLS 15.25), boxando TODO inteiro como Double — contagens e nºs de linha saíam como "130.0".
     */
    private static void jsonKeepsIntegersAsIntegers() {
        Object parsed = Json.object("{\"n\":130}").get("n");
        TestSupport.truth(parsed instanceof Long, "Inteiro deveria ser Long, não " + parsed.getClass().getSimpleName());
        TestSupport.equal("{\"n\":130}", Json.stringify(Json.parse("{\"n\":130}")));
        TestSupport.truth(Json.object("{\"x\":1.5}").get("x") instanceof Double, "Fracionário deveria continuar Double");
    }

    /**
     * Antes: persist() reescrevia o arquivo inteiro a cada save() — O(n) por gravação, O(n²) na carga.
     * Agora grava append e compacta; este teste prova append, reload com lápide de DELETE e compactação.
     */
    private static void jsonStoreAppendsCompactsAndHonorsTombstones() throws Exception {
        Path directory = Files.createTempDirectory("jsonstore-append");
        Path database = directory.resolve("store.db");
        JsonStore store = new JsonStore(database);
        store.save(new LinkedHashMap<>(Map.of("id", "a", "v", "1")));
        store.save(new LinkedHashMap<>(Map.of("id", "b", "v", "2")));
        store.delete("a");

        // Reabrir aplica o log: a lápide de "a" deve tê-lo removido; "b" permanece.
        JsonStore reopened = new JsonStore(database);
        TestSupport.truth(reopened.find("a").isEmpty(), "Lápide deveria remover 'a' no reload");
        TestSupport.equal("2", reopened.find("b").orElseThrow().get("v"));
        TestSupport.equal(1, reopened.count());

        // Gravações repetidas do mesmo id disparam a compactação; o valor final vence e a contagem fica 1.
        for (int i = 0; i < 1200; i++) reopened.save(new LinkedHashMap<>(Map.of("id", "b", "v", String.valueOf(i))));
        TestSupport.equal(1, reopened.count());
        TestSupport.equal("1199", new JsonStore(database).find("b").orElseThrow().get("v"));
        TestSupport.truth(new JsonStore(database).verify().auditValid(), "Cadeia de auditoria deveria seguir válida");
    }

    /** Antes: caracteres de controle saíam crus e o JSON era rejeitado por parsers externos (SARIF/CycloneDX). */
    private static void jsonEscapesControlCharacters() {
        String rendered = Json.stringify(Map.of("k", "a\u0000b\u001bc"));
        TestSupport.truth(rendered.contains("\\u0000"), "U+0000 deveria virar \\u0000: " + rendered);
        TestSupport.truth(rendered.contains("\\u001b"), "U+001B deveria virar \\u001b: " + rendered);
        TestSupport.equal("a\u0000b\u001bc", Json.object(rendered).get("k"));
    }

    /** Antes: aninhamento profundo estourava a pilha com StackOverflowError (Error, não Exception → escapava dos handlers). */
    private static void jsonRejectsDeepNesting() {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        try {
            Json.parse(deep);
            throw new AssertionError("Aninhamento de 5000 níveis deveria ser rejeitado");
        } catch (IllegalArgumentException expected) {
            TestSupport.truth(expected.getMessage().contains("aninhamento"), "Mensagem inesperada: " + expected.getMessage());
        }
    }

    /** Antes: "\\u12" no fim do texto lançava StringIndexOutOfBounds → HTTP 500 em vez de 400. */
    private static void jsonRejectsTruncatedUnicodeEscape() {
        try {
            Json.parse("\"\\u12\"");
            throw new AssertionError("Escape \\u truncado deveria ser rejeitado");
        } catch (IllegalArgumentException expected) { /* esperado */ }
    }

    /** Antes: após a rotação do log, verify() acusava "cadeia rompida" para sempre. */
    private static void auditChainSurvivesRotation() throws Exception {
        Path directory = Files.createTempDirectory("audit-rotation");
        Path database = directory.resolve("store.db");
        Path auditFile = directory.resolve("store.db.audit.jsonl");

        JsonStore store = new JsonStore(database);
        store.save(new LinkedHashMap<>(Map.of("id", "a", "value", "1")));
        store.save(new LinkedHashMap<>(Map.of("id", "b", "value", "2")));
        TestSupport.truth(store.verify().auditValid(), "Cadeia deveria ser válida antes da rotação");

        // Simula a rotação disparada por SWISSKNIFE_AUDIT_ROTATION_BYTES arquivando o log corrente
        // e registrando a âncora — exatamente o que rotateAuditIfNeeded() faz ao exceder o limite.
        String lastHash = lastAuditHash(auditFile);
        Files.move(auditFile, directory.resolve("store.db.audit.jsonl.archive"));
        Files.writeString(directory.resolve("store.db.audit.anchor"), lastHash);

        JsonStore reopened = new JsonStore(database);
        reopened.save(new LinkedHashMap<>(Map.of("id", "c", "value", "3")));
        JsonStore.Verification verification = reopened.verify();
        TestSupport.truth(verification.auditValid(),
            "Cadeia deveria continuar válida após rotação, mas: " + verification.errors());
    }

    private static String lastAuditHash(Path auditFile) throws Exception {
        List<String> lines = Files.readAllLines(auditFile);
        for (int i = lines.size() - 1; i >= 0; i--)
            if (!lines.get(i).isBlank()) return String.valueOf(Json.object(lines.get(i)).get("hash"));
        throw new AssertionError("Log de auditoria vazio");
    }

    /**
     * Antes: XSS armazenado. O tokenizer devolve identificadores entre aspas com o texto bruto, e o
     * relatório HTML interpolava esse texto sem escape.
     */
    private static void slowQueryHtmlEscapesUntrustedIdentifiers() {
        var analyzer = new SlowQueryAnalyzer();
        String malicious = "SELECT id FROM \"<img src=x onerror=alert(1)>\" WHERE a = 1;";
        String html = analyzer.html(analyzer.analyzeBatch(malicious));
        TestSupport.truth(!html.contains("<img src=x"), "Identificador hostil não deveria aparecer cru no HTML");
        TestSupport.truth(html.contains("&lt;img src=x"), "Identificador hostil deveria aparecer escapado");
    }
}
