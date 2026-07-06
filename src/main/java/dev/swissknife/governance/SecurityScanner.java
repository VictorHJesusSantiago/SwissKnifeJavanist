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
            for (Rule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(content);
                while (matcher.find()) findings.add(new Finding(rule.id(), rule.severity(),
                    root.relativize(file).toString(), lineOf(content, matcher.start()), rule.message(),
                    fingerprint(matcher.group())));
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
    private String fingerprint(String secret) {
        return Integer.toHexString(secret.hashCode()) + ":" + Math.min(secret.length(), 999);
    }
    private record Rule(String id, String severity, Pattern pattern, String message) {}
    public record Finding(String kind, String severity, String file, int line,
                          String description, String fingerprint) {}
    public record CertificateInfo(String file, String subject, String expiresAt, long daysRemaining) {}
    public record Report(int filesScanned, List<Finding> findings,
                         List<CertificateInfo> certificates, boolean passed) {}
}
