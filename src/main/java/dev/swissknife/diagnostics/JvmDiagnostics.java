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
        return new Report("APPLICATION_LOG", Map.of("warnings", warnings, "errors", errors,
            "exceptionGroups", exceptions), errors > 0 ? List.of("Priorize os grupos de erro mais frequentes.") : List.of());
    }
    private Map<String, Integer> stackGroups(String content) {
        Map<String, Integer> groups = new TreeMap<>();
        Matcher m = Pattern.compile("(?m)^\\s+at\\s+([\\w.$]+\\.[\\w$]+)\\(").matcher(content);
        while (m.find()) groups.merge(m.group(1), 1, Integer::sum);
        return groups.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(20).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a,b)->a, LinkedHashMap::new));
    }
    public record Report(String type, Map<String, Object> metrics, List<String> recommendations) {}
}
