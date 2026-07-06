package dev.swissknife.governance;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Inventaria incompatibilidades e oportunidades de modernização sem alterar fontes. */
public final class ModernizationAnalyzer {
    private static final List<Rule> RULES = List.of(
        new Rule("JAVAX_TO_JAKARTA", "HIGH", "\\bimport\\s+javax\\.(servlet|persistence|validation|annotation)\\.",
            "Migre o namespace Java EE para jakarta.*."),
        new Rule("LEGACY_DATE", "LOW", "\\b(new\\s+Date\\s*\\(|Calendar\\.getInstance)", "Prefira java.time."),
        new Rule("LEGACY_SWITCH", "LOW", "\\bswitch\\s*\\([^)]*\\)\\s*\\{[^}]*\\bcase\\b[^:]+:",
            "Avalie switch expressions com ->."),
        new Rule("OLD_INSTANCEOF", "LOW", "instanceof\\s+([\\w<>]+)\\s*\\)\\s*\\{\\s*\\1\\s+\\w+\\s*=\\s*\\(\\1\\)",
            "Use pattern matching para instanceof."),
        new Rule("PLATFORM_THREAD_POOL", "MEDIUM", "Executors\\.new(Fixed|Cached)ThreadPool",
            "Avalie virtual threads para tarefas bloqueantes."),
        new Rule("DEPRECATED_FINALIZE", "HIGH", "\\bvoid\\s+finalize\\s*\\(", "Remova finalize; use Cleaner ou AutoCloseable."),
        new Rule("JUNIT4", "MEDIUM", "org\\.junit\\.(Test|Before|After|Assert)", "Migre para JUnit Jupiter.")
    );
    public Report analyze(Path root, int targetJava) throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (Path file : FilesEx.walk(root, p -> p.toString().endsWith(".java"))) {
            String code = Files.readString(file);
            for (Rule rule : RULES) {
                Matcher matcher = Pattern.compile(rule.regex(), Pattern.DOTALL).matcher(code);
                while (matcher.find()) findings.add(new Finding(rule.id(), rule.severity(),
                    root.relativize(file).toString(), (int) code.substring(0, matcher.start()).lines().count(),
                    rule.recommendation()));
            }
        }
        int effort = findings.stream().mapToInt(f -> switch (f.severity()) {
            case "HIGH" -> 4; case "MEDIUM" -> 2; default -> 1;
        }).sum();
        return new Report(targetJava, findings, effort,
            effort < 10 ? "BAIXO" : effort < 30 ? "MÉDIO" : "ALTO");
    }
    private record Rule(String id, String severity, String regex, String recommendation) {}
    public record Finding(String kind, String severity, String file, int line, String recommendation) {}
    public record Report(int targetJava, List<Finding> findings, int effortPoints, String estimatedEffort) {}
}
