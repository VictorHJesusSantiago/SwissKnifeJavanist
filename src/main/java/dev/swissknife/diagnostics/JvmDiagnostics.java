package dev.swissknife.diagnostics;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Análise offline de thread dumps, GC logs e logs de aplicação. */
public final class JvmDiagnostics {
    public Report analyze(Path file) throws IOException {
        String content = Files.readString(file);
        return switch (detect(content)) {
            case "THREAD_DUMP" -> threadDump(content);
            case "GC_LOG" -> gcLog(content);
            default -> applicationLog(content);
        };
    }
    private String detect(String content) {
        if (content.contains("java.lang.Thread.State:") || content.contains("Full thread dump")) return "THREAD_DUMP";
        if (content.matches("(?s).*(Pause Young|Pause Full|GC\\(|\\[gc).*")) return "GC_LOG";
        return "APPLICATION_LOG";
    }
    private Report threadDump(String content) {
        Map<String, Integer> states = new TreeMap<>();
        Matcher state = Pattern.compile("java\\.lang\\.Thread\\.State:\\s+(\\w+)").matcher(content);
        while (state.find()) states.merge(state.group(1), 1, Integer::sum);
        List<String> deadlocks = new ArrayList<>();
        if (content.toLowerCase(Locale.ROOT).contains("deadlock"))
            deadlocks.add("O dump contém indicação explícita de deadlock.");
        Map<String, Integer> traces = stackGroups(content);
        List<String> recommendations = new ArrayList<>();
        if (states.getOrDefault("BLOCKED", 0) > 3) recommendations.add("Investigue monitores com muitas threads BLOCKED.");
        if (states.getOrDefault("WAITING", 0) > 50) recommendations.add("Revise pools e filas com grande volume de WAITING.");
        return new Report("THREAD_DUMP", Map.of("states", states, "stackGroups", traces,
            "deadlocks", deadlocks), recommendations);
    }
    private Report gcLog(String content) {
        Matcher pause = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)ms").matcher(content);
        List<Double> pauses = new ArrayList<>();
        while (pause.find()) pauses.add(Double.parseDouble(pause.group(1).replace(',', '.')));
        pauses.sort(Double::compareTo);
        double total = pauses.stream().mapToDouble(Double::doubleValue).sum();
        double max = pauses.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double p95 = pauses.isEmpty() ? 0 : pauses.get(Math.min(pauses.size() - 1,
            (int) Math.ceil(pauses.size() * .95) - 1));
        List<String> recommendations = new ArrayList<>();
        if (max > 1000) recommendations.add("Há pausas superiores a 1 segundo; correlacione com pressão de heap.");
        if (content.contains("OutOfMemoryError")) recommendations.add("OutOfMemoryError encontrado; colete heap dump e histograma.");
        return new Report("GC_LOG", Map.of("pauses", pauses.size(), "totalPauseMs", total,
            "maxPauseMs", max, "p95PauseMs", p95), recommendations);
    }
    private Report applicationLog(String content) {
        Map<String, Integer> exceptions = new TreeMap<>();
        Matcher matcher = Pattern.compile("([\\w.$]+(?:Exception|Error))(?::|\\s|$)").matcher(content);
        while (matcher.find()) exceptions.merge(matcher.group(1), 1, Integer::sum);
        long warnings = content.lines().filter(l -> l.matches("(?i).*\\bWARN(?:ING)?\\b.*")).count();
        long errors = content.lines().filter(l -> l.matches("(?i).*\\bERROR\\b.*")).count();
        Map<String, Integer> byTrace = new TreeMap<>();
        Matcher traceMatcher = Pattern.compile("(?i)\\b(?:traceId|trace-id|correlationId|correlation-id)[=:]\\s*([\\w-]+)").matcher(content);
        while (traceMatcher.find()) byTrace.merge(traceMatcher.group(1), 1, Integer::sum);
        Optional<Double> startupSeconds = startupDuration(content);
        List<String> recommendations = new ArrayList<>(errors > 0 ? List.of("Priorize os grupos de erro mais frequentes.") : List.of());
        String redacted = redact(sample(content, 2000));
        return new Report("APPLICATION_LOG", Map.of("warnings", warnings, "errors", errors,
            "exceptionGroups", exceptions, "byTraceId", byTrace,
            "startupSeconds", startupSeconds.orElse(-1.0), "sampleRedacted", redacted), recommendations);
    }

    private Optional<Double> startupDuration(String content) {
        Matcher springBoot = Pattern.compile("(?i)started\\s+\\S+\\s+in\\s+([\\d.,]+)\\s*seconds").matcher(content);
        if (springBoot.find()) return Optional.of(Double.parseDouble(springBoot.group(1).replace(',', '.')));
        return Optional.empty();
    }
    /** Amostra segura para inclusão em relatórios (limitada em tamanho, nunca o log completo). */
    private String sample(String content, int maxChars) {
        return content.length() <= maxChars ? content : content.substring(0, maxChars) + "\n… (truncado)";
    }
    /** Redige segredos comuns (senha/token/chave) antes de qualquer amostra sair do processo local. */
    private String redact(String content) {
        return content
            .replaceAll("(?i)(password|senha|secret|token|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=[REDACTED]")
            .replaceAll("(?i)\\bAuthorization:\\s*Bearer\\s+\\S+", "Authorization: Bearer [REDACTED]");
    }
    private Map<String, Integer> stackGroups(String content) {
        Map<String, Integer> groups = new TreeMap<>();
        Matcher m = Pattern.compile("(?m)^\\s+at\\s+([\\w.$]+\\.[\\w$]+)\\(").matcher(content);
        while (m.find()) groups.merge(m.group(1), 1, Integer::sum);
        return groups.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(20).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a,b)->a, LinkedHashMap::new));
    }
    /** Compara dois relatórios de thread dump (antes/depois de uma implantação, por exemplo). */
    @SuppressWarnings("unchecked")
    public ThreadDumpDiff compareThreadDumps(Report before, Report after) {
        if (!before.type().equals("THREAD_DUMP") || !after.type().equals("THREAD_DUMP"))
            throw new IllegalArgumentException("compareThreadDumps exige dois relatórios do tipo THREAD_DUMP");
        Map<String, Integer> beforeStates = (Map<String, Integer>) before.metrics().get("states");
        Map<String, Integer> afterStates = (Map<String, Integer>) after.metrics().get("states");
        Map<String, Integer> delta = new TreeMap<>();
        Set<String> allStates = new TreeSet<>(); allStates.addAll(beforeStates.keySet()); allStates.addAll(afterStates.keySet());
        allStates.forEach(state -> delta.put(state, afterStates.getOrDefault(state, 0) - beforeStates.getOrDefault(state, 0)));
        Map<String, Integer> beforeGroups = (Map<String, Integer>) before.metrics().get("stackGroups");
        Map<String, Integer> afterGroups = (Map<String, Integer>) after.metrics().get("stackGroups");
        List<String> newHotspots = afterGroups.keySet().stream().filter(k -> !beforeGroups.containsKey(k)).toList();
        List<String> resolvedHotspots = beforeGroups.keySet().stream().filter(k -> !afterGroups.containsKey(k)).toList();
        return new ThreadDumpDiff(delta, newHotspots, resolvedHotspots);
    }

    /** Inventário de processos Java em execução localmente (equivalente simplificado ao jps). */
    public List<JavaProcess> listJavaProcesses() {
        return ProcessHandle.allProcesses()
            .map(handle -> new JavaProcess(handle.pid(), handle.info().command().orElse(""),
                handle.info().commandLine().orElse(""), handle.info().startInstant().map(Object::toString).orElse("")))
            .filter(process -> process.command().toLowerCase(Locale.ROOT).contains("java"))
            .toList();
    }

    /** Empacota vários arquivos de diagnóstico (dumps, logs, relatórios) em um único ZIP para suporte. */
    public Path bundle(List<Path> files, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(output))) {
            for (Path file : files) {
                if (!Files.isRegularFile(file)) continue;
                zip.putNextEntry(new java.util.zip.ZipEntry(file.getFileName().toString()));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return output;
    }

    public record ThreadDumpDiff(Map<String, Integer> stateDelta, List<String> newStackHotspots,
                                 List<String> resolvedStackHotspots) {}
    public record JavaProcess(long pid, String command, String commandLine, String startedAt) {}
    public record Report(String type, Map<String, Object> metrics, List<String> recommendations) {}
}
