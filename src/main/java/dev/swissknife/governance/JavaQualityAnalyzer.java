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
            int cognitive = cognitiveComplexity(stripStringsAndComments(code));
            int methods = count(METHOD, code);
            int types = count(TYPE, code);
            String pkg = packageName(code);
            Set<String> imports = packageDependencies.computeIfAbsent(pkg, ignored -> new TreeSet<>());
            Matcher importMatcher = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)").matcher(code);
            while (importMatcher.find()) imports.add(packageOf(importMatcher.group(1)));
            metrics.add(new FileMetric(root.relativize(file).toString(), effective, types, methods, complexity, cognitive));
            if (effective > 500) finding(findings, "LARGE_CLASS", "MEDIUM", root, file, 1,
                "Arquivo com " + effective + " linhas efetivas.", "Divida responsabilidades.");
            if (complexity > 30) finding(findings, "HIGH_COMPLEXITY", "MEDIUM", root, file, 1,
                "Complexidade aproximada " + complexity + ".", "Extraia métodos e simplifique condições.");
            if (cognitive > 40) finding(findings, "HIGH_COGNITIVE_COMPLEXITY", "MEDIUM", root, file, 1,
                "Complexidade cognitiva aproximada " + cognitive + ".", "Reduza aninhamento extraindo métodos menores.");
            inspectLines(root, file, sourceLines, findings);
            inspectResourcesAndOptional(root, file, sourceLines, findings);
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
        findings.addAll(layerViolations(root, packageDependencies));
        int score = Math.max(0, 100 - findings.stream().mapToInt(f -> weight(f.severity())).sum());
        return new Report(files.size(), lines, metrics.stream().mapToInt(FileMetric::types).sum(),
            metrics.stream().mapToInt(FileMetric::methods).sum(), score, metrics, findings, cycles);
    }

    /** Complexidade cognitiva aproximada: soma 1 por estrutura de controle, +1 extra por nível de aninhamento. */
    private int cognitiveComplexity(String code) {
        int total = 0, depth = 0;
        Matcher tokens = Pattern.compile("[{}]|\\b(if|for|while|catch)\\b|&&|\\|\\|").matcher(code);
        while (tokens.find()) {
            String token = tokens.group();
            switch (token) {
                case "{" -> depth++;
                case "}" -> depth = Math.max(0, depth - 1);
                case "&&", "||" -> total += 1;
                default -> total += 1 + Math.max(0, depth - 1);
            }
        }
        return total;
    }

    private void inspectResourcesAndOptional(Path root, Path file, List<String> lines, List<Finding> out) {
        Pattern resource = Pattern.compile("new\\s+(FileInputStream|FileOutputStream|FileReader|FileWriter|" +
            "Scanner|BufferedReader|BufferedWriter|InputStreamReader|OutputStreamWriter|RandomAccessFile)\\s*\\(");
        Map<String, Integer> optionalVariables = new LinkedHashMap<>();
        Pattern optionalDecl = Pattern.compile("Optional<[^>]*>\\s+(\\w+)\\s*=");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (resource.matcher(line).find() && !stripped.startsWith("try") &&
                (i == 0 || !lines.get(i - 1).strip().endsWith("try (")) && !line.contains("try ("))
                finding(out, "RESOURCE_LEAK", "MEDIUM", root, file, i + 1,
                    "Recurso possivelmente aberto fora de try-with-resources.", "Use try-with-resources para garantir o fechamento.");
            Matcher declared = optionalDecl.matcher(line);
            if (declared.find()) optionalVariables.put(declared.group(1), i);
        }
        optionalVariables.forEach((variable, declaredLine) -> {
            for (int i = declaredLine; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains(variable + ".get()") && !line.contains("isPresent") && !line.contains("orElse")
                    && !line.contains("ifPresent") && !line.contains("isEmpty"))
                    finding(out, "OPTIONAL_MISUSE", "LOW", root, file, i + 1,
                        "Optional." + "get() sem verificação prévia (" + variable + ").",
                        "Prefira orElse/orElseThrow/ifPresent em vez de get() direto.");
            }
        });
    }

    /**
     * Valida regras de camadas a partir de .swissknife-quality.properties:
     * layer.&lt;nome&gt;=&lt;prefixoDePacote&gt; e rule.N=camadaA:camadaB (A não pode depender de B).
     */
    private List<Finding> layerViolations(Path root, Map<String, Set<String>> packageDependencies) throws IOException {
        Path file = root.resolve(".swissknife-quality.properties");
        if (!Files.isRegularFile(file)) return List.of();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
        Map<String, String> layers = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            String k = String.valueOf(key);
            if (k.startsWith("layer.")) layers.put(k.substring("layer.".length()), String.valueOf(value));
        });
        List<String> rules = new ArrayList<>();
        properties.forEach((key, value) -> { if (String.valueOf(key).startsWith("rule.")) rules.add(String.valueOf(value)); });
        List<Finding> violations = new ArrayList<>();
        for (String rule : rules) {
            String[] parts = rule.split(":", 2);
            if (parts.length != 2) continue;
            String fromPrefix = layers.get(parts[0]), toPrefix = layers.get(parts[1]);
            if (fromPrefix == null || toPrefix == null) continue;
            packageDependencies.forEach((pkg, imports) -> {
                if (!pkg.startsWith(fromPrefix)) return;
                imports.forEach(imported -> {
                    if (imported.startsWith(toPrefix))
                        violations.add(new Finding("LAYER_VIOLATION", "HIGH", pkg, 1,
                            "Camada '" + parts[0] + "' depende de '" + parts[1] + "' via " + imported,
                            "Inverta a dependência ou introduza uma interface na camada permitida."));
                });
            });
        }
        return violations;
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

    public record FileMetric(String file, long lines, int types, int methods, int complexity, int cognitiveComplexity) {}
    public record Finding(String kind, String severity, String file, int line,
                          String description, String recommendation) {}
    public record Report(int files, long lines, int types, int methods, int qualityScore,
                         List<FileMetric> metrics, List<Finding> findings,
                         List<List<String>> packageCycles) {}
}
