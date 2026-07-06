package dev.swissknife.governance;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Métricas e regras estáticas conservadoras para fontes Java. */
public final class JavaQualityAnalyzer {
    private static final Pattern TYPE = Pattern.compile("\\b(class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern METHOD = Pattern.compile(
        "(?m)^\\s*(?:public|protected|private|static|final|synchronized|abstract|native|\\s)+" +
        "[\\w<>?,.\\[\\]]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^\\{]+)?\\{");
    private static final Pattern BRANCH = Pattern.compile("\\b(if|for|while|case|catch)\\b|&&|\\|\\|");

    public Report analyze(Path root) throws IOException {
        List<Path> files = FilesEx.walk(root, p -> p.toString().endsWith(".java"));
        List<FileMetric> metrics = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Map<String, Set<String>> packageDependencies = new TreeMap<>();
        Map<String, String> fingerprints = new HashMap<>();
        long lines = 0;
        for (Path file : files) {
            String code = Files.readString(file);
            List<String> sourceLines = code.lines().toList();
            long effective = sourceLines.stream().map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith("//")).count();
            lines += effective;
            int complexity = 1 + count(BRANCH, stripStringsAndComments(code));
            int methods = count(METHOD, code);
            int types = count(TYPE, code);
            String pkg = packageName(code);
            Set<String> imports = packageDependencies.computeIfAbsent(pkg, ignored -> new TreeSet<>());
            Matcher importMatcher = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)").matcher(code);
            while (importMatcher.find()) imports.add(packageOf(importMatcher.group(1)));
            metrics.add(new FileMetric(root.relativize(file).toString(), effective, types, methods, complexity));
            if (effective > 500) finding(findings, "LARGE_CLASS", "MEDIUM", root, file, 1,
                "Arquivo com " + effective + " linhas efetivas.", "Divida responsabilidades.");
            if (complexity > 30) finding(findings, "HIGH_COMPLEXITY", "MEDIUM", root, file, 1,
                "Complexidade aproximada " + complexity + ".", "Extraia métodos e simplifique condições.");
            inspectLines(root, file, sourceLines, findings);
            Matcher method = METHOD.matcher(code);
            while (method.find()) {
                long parameters = method.group(2).isBlank() ? 0 : method.group(2).chars().filter(c -> c == ',').count() + 1;
                if (parameters > 6) finding(findings, "TOO_MANY_PARAMETERS", "LOW", root, file,
                    lineOf(code, method.start()), "Método " + method.group(1) + " possui " + parameters + " parâmetros.",
                    "Agrupe parâmetros relacionados em um objeto.");
            }
            for (String block : normalizedBlocks(code)) {
                String previous = fingerprints.putIfAbsent(block, root.relativize(file).toString());
                if (previous != null && !previous.equals(root.relativize(file).toString()))
                    finding(findings, "DUPLICATED_CODE", "LOW", root, file, 1,
                        "Bloco semelhante também aparece em " + previous + ".", "Extraia comportamento comum.");
            }
        }
        List<List<String>> cycles = cycles(packageDependencies);
        cycles.forEach(cycle -> findings.add(new Finding("PACKAGE_CYCLE", "HIGH", String.join(" -> ", cycle),
            1, "Dependência circular entre pacotes.", "Inverta a dependência ou introduza uma abstração.")));
        int score = Math.max(0, 100 - findings.stream().mapToInt(f -> weight(f.severity())).sum());
        return new Report(files.size(), lines, metrics.stream().mapToInt(FileMetric::types).sum(),
            metrics.stream().mapToInt(FileMetric::methods).sum(), score, metrics, findings, cycles);
    }

    private void inspectLines(Path root, Path file, List<String> lines, List<Finding> out) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches(".*catch\\s*\\(\\s*(Exception|Throwable)\\b.*"))
                finding(out, "GENERIC_CATCH", "LOW", root, file, i + 1,
                    "Captura genérica de exceção.", "Capture exceções específicas.");
            if (line.matches(".*catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}.*"))
                finding(out, "SWALLOWED_EXCEPTION", "HIGH", root, file, i + 1,
                    "Exceção ignorada.", "Trate ou registre a exceção.");
            if (line.contains("Thread.sleep("))
                finding(out, "BLOCKING_SLEEP", "LOW", root, file, i + 1,
                    "Thread.sleep pode bloquear execução.", "Use agendamento ou sincronização apropriada.");
            if (line.matches(".*\\b(System\\.out|printStackTrace)\\b.*"))
                finding(out, "DIRECT_CONSOLE", "LOW", root, file, i + 1,
                    "Saída direta no console.", "Use logging estruturado em aplicações.");
            if (line.length() > 160)
                finding(out, "LONG_LINE", "LOW", root, file, i + 1,
                    "Linha com " + line.length() + " caracteres.", "Quebre a expressão para legibilidade.");
        }
    }

    private List<String> normalizedBlocks(String code) {
        List<String> lines = stripStringsAndComments(code).lines().map(String::strip)
            .filter(s -> !s.isBlank() && !Set.of("{", "}", "};").contains(s)).toList();
        List<String> result = new ArrayList<>();
        for (int i = 0; i + 7 < lines.size(); i += 4) {
            String block = String.join(" ", lines.subList(i, i + 8))
                .replaceAll("\\b\\d+\\b", "#").replaceAll("\\s+", " ");
            if (block.length() > 180) result.add(Integer.toHexString(block.hashCode()));
        }
        return result;
    }

    private List<List<String>> cycles(Map<String, Set<String>> graph) {
        List<List<String>> result = new ArrayList<>();
        for (String from : graph.keySet()) for (String to : graph.get(from)) {
            if (!from.equals(to) && graph.getOrDefault(to, Set.of()).contains(from)) {
                List<String> cycle = from.compareTo(to) < 0 ? List.of(from, to, from) : List.of(to, from, to);
                if (!result.contains(cycle)) result.add(cycle);
            }
        }
        return result;
    }

    private void finding(List<Finding> out, String kind, String severity, Path root, Path file,
                         int line, String description, String recommendation) {
        out.add(new Finding(kind, severity, root.relativize(file).toString(), line, description, recommendation));
    }
    private int count(Pattern pattern, String text) { int count = 0; Matcher m = pattern.matcher(text); while (m.find()) count++; return count; }
    private int lineOf(String text, int offset) { return (int) text.substring(0, offset).lines().count(); }
    private int weight(String severity) { return switch (severity) { case "HIGH" -> 8; case "MEDIUM" -> 4; default -> 1; }; }
    private String stripStringsAndComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ")
            .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }
    private String packageName(String code) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)").matcher(code);
        return m.find() ? m.group(1) : "(default)";
    }
    private String packageOf(String type) { int dot = type.lastIndexOf('.'); return dot > 0 ? type.substring(0, dot) : type; }

    public record FileMetric(String file, long lines, int types, int methods, int complexity) {}
    public record Finding(String kind, String severity, String file, int line,
                          String description, String recommendation) {}
    public record Report(int files, long lines, int types, int methods, int qualityScore,
                         List<FileMetric> metrics, List<Finding> findings,
                         List<List<String>> packageCycles) {}
}
