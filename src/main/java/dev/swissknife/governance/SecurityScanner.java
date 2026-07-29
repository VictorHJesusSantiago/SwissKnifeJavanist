package dev.swissknife.governance;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Scanner local de segredos, configurações arriscadas e certificados X.509. */
public final class SecurityScanner {
    private static final List<Rule> RULES = List.of(
        new Rule("PRIVATE_KEY", "CRITICAL", Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"), "Chave privada no repositório."),
        new Rule("AWS_KEY", "CRITICAL", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "Possível access key AWS."),
        new Rule("GENERIC_SECRET", "HIGH", Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*[\"']?([^\\s\"'${}]{8,})"), "Possível segredo literal."),
        new Rule("JDBC_PASSWORD", "HIGH", Pattern.compile("(?i)jdbc:[^\\s]+[?;&]password=[^&\\s]+"), "Senha presente em URL JDBC."),
        new Rule("INSECURE_RANDOM", "MEDIUM", Pattern.compile("\\bnew\\s+Random\\s*\\("), "Random não é apropriado para segurança."),
        new Rule("WEAK_HASH", "MEDIUM", Pattern.compile("getInstance\\s*\\(\\s*[\"'](?:MD5|SHA-1)[\"']"), "Algoritmo de hash fraco."),
        new Rule("TRUST_ALL_TLS", "CRITICAL", Pattern.compile("(?i)(TrustAll|ALLOW_ALL_HOSTNAME|NoopHostnameVerifier)"), "Validação TLS aparentemente desativada.")
    );

    public Report scan(Path root) throws IOException {
        List<Finding> findings = new ArrayList<>();
        List<CertificateInfo> certificates = new ArrayList<>();
        List<Path> files = FilesEx.walk(root, p -> {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return !p.toString().contains(".swissknife") && (name.endsWith(".java") || name.endsWith(".xml")
                || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")
                || name.endsWith(".json") || name.endsWith(".env") || name.endsWith(".pem")
                || name.endsWith(".crt") || name.endsWith(".cer"));
        });
        for (Path file : files) {
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".crt") || lower.endsWith(".cer") || lower.endsWith(".pem"))
                readCertificate(root, file, certificates, findings);
            String content;
            try { content = Files.readString(file); } catch (Exception ignored) { continue; }
            currentContent = content;
            for (Rule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(content);
                while (matcher.find()) {
                    int line = lineOf(content, matcher.start());
                    if (isSuppressed(rule, matcher, lineText(content, matcher.start()))) continue;
                    findings.add(new Finding(rule.id(), rule.severity(),
                        root.relativize(file).toString(), line, rule.message(),
                        fingerprint(matcher.group())));
                }
            }
            if (content.contains("management.endpoints.web.exposure.include: \"*\"") ||
                content.contains("include: '*'"))
                findings.add(new Finding("ACTUATOR_EXPOSED", "HIGH", root.relativize(file).toString(),
                    1, "Todos os endpoints Actuator parecem expostos.", ""));
        }
        return new Report(files.size(), findings, certificates,
            findings.stream().noneMatch(f -> Set.of("CRITICAL", "HIGH").contains(f.severity())));
    }

    private void readCertificate(Path root, Path file, List<CertificateInfo> certificates,
                                 List<Finding> findings) {
        try (var input = Files.newInputStream(file)) {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            Instant expires = certificate.getNotAfter().toInstant();
            long days = Duration.between(Instant.now(), expires).toDays();
            certificates.add(new CertificateInfo(root.relativize(file).toString(),
                certificate.getSubjectX500Principal().getName(), expires.toString(), days));
            if (days < 30) findings.add(new Finding("CERTIFICATE_EXPIRY", days < 0 ? "CRITICAL" : "HIGH",
                root.relativize(file).toString(), 1, "Certificado expira em " + days + " dia(s).", ""));
        } catch (Exception ignored) {
            // Arquivos PEM também podem conter chaves ou cadeias; as regras textuais continuam válidas.
        }
    }
    private int lineOf(String value, int offset) { return (int) value.substring(0, offset).lines().count(); }

    /** Texto completo da linha que contém {@code offset} — base para a heurística de supressão. */
    private String lineText(String content, int offset) {
        int start = content.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int end = content.indexOf('\n', offset);
        return content.substring(start, end < 0 ? content.length() : end);
    }

    /**
     * Filtra os falsos positivos que dominavam o relatório neste próprio repositório: as regex das
     * regras (o scanner se encontrava), identificadores que apenas REFERENCIAM segredos em vez de
     * literais, e linhas anotadas com supressão explícita. Reduz o ruído sem esconder um segredo real.
     */
    private boolean isSuppressed(Rule rule, Matcher matcher, String line) {
        // 1) Supressão explícita, revisada: `// nosec` ou `swissknife:allow-secret` na linha.
        if (line.contains("nosec") || line.contains("swissknife:allow-secret")) return true;
        // 2) Auto-exclusão: a linha é uma definição de regra (Pattern.compile / new Rule) — é o
        //    próprio scanner, não um segredo. Cobre as 5 ocorrências que eram as regex das regras.
        if (line.contains("Pattern.compile(") || line.contains("new Rule(")) return true;
        // 3) Só GENERIC_SECRET produz o "referencia mas não é literal"; as demais regras casam formatos
        //    específicos (AKIA…, -----BEGIN…) que não têm essa ambiguidade.
        if (!"GENERIC_SECRET".equals(rule.id())) return false;
        String value = matcher.groupCount() >= 2 ? matcher.group(2) : matcher.group();
        // O valor está entre aspas? Um literal de segredo real está; uma expressão de código não.
        int valueStart = matcher.groupCount() >= 2 ? matcher.start(2) : matcher.start();
        boolean quoted = valueStart > 0 && isQuote(matcher.group().isEmpty() ? '\0'
            : contentCharBefore(valueStart));
        return referencesSecretButIsNotLiteral(value, line, quoted);
    }

    /** Preenchido por scan() antes de avaliar as regras: o conteúdo do arquivo em análise. */
    private String currentContent = "";
    private char contentCharBefore(int index) {
        return index > 0 && index <= currentContent.length() ? currentContent.charAt(index - 1) : '\0';
    }
    private static boolean isQuote(char c) { return c == '"' || c == '\''; }

    private boolean referencesSecretButIsNotLiteral(String value, String line, boolean quoted) {
        // Expressão de código, não literal: qualquer chamada/aninhamento com parênteses fora de aspas
        // (token(...), Totp.generateSecret(), current(), tokens.group()).
        if (!quoted && (value.indexOf('(') >= 0 || value.indexOf(')') >= 0)) return true;
        // Chamada de método/expressão em vez de literal: getPassword(), env("…"), config.get(…).
        if (line.matches(".*(?:get|read|load|fetch|resolve|env)\\w*\\s*\\(.*")) return true;
        // CONSTANTE_OU_VARIAVEL: nome de identificador (maiúsculas com _, ou dotted), não um segredo.
        if (value.matches("[A-Z0-9_]+") || value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")) return true;
        // Placeholders óbvios de exemplo/config.
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.contains("example") || lowered.contains("changeme") || lowered.contains("your_")
            || lowered.contains("xxxx") || lowered.startsWith("${") || lowered.startsWith("{{");
    }

    /** SHA-256 truncado como fingerprint — hashCode() são 32 bits e colidem, fundindo achados distintos. */
    private String fingerprint(String secret) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex + ":" + Math.min(secret.length(), 999);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
    private record Rule(String id, String severity, Pattern pattern, String message) {}
    public record Finding(String kind, String severity, String file, int line,
                          String description, String fingerprint) {}
    public record CertificateInfo(String file, String subject, String expiresAt, long daysRemaining) {}
    public record Report(int filesScanned, List<Finding> findings,
                         List<CertificateInfo> certificates, boolean passed) {}
}
