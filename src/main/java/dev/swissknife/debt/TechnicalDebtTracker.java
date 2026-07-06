package dev.swissknife.debt;

import dev.swissknife.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Inventário configurável de dívida técnica com metadados, políticas e baseline. */
public final class TechnicalDebtTracker {
    private static final Set<String> DEFAULT_MARKERS = Set.of("TODO", "FIXME", "HACK", "XXX", "DEBT", "TECHDEBT");
    private static final Set<String> DEFAULT_EXTENSIONS = Set.of(".java", ".kt", ".kts", ".groovy", ".xml",
        ".yml", ".yaml", ".sql", ".properties", ".md", ".js", ".ts", ".tsx", ".jsx", ".gradle");
    private static final Pattern OWNER = Pattern.compile("^\\s*\\(([^)]+)\\)");
    private static final Pattern TICKET = Pattern.compile("\\[([A-Z][A-Z0-9]+-\\d+)]");
    private static final Pattern DUE = Pattern.compile("\\b(?:due|prazo)=(\\d{4}-\\d{2}-\\d{2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL = Pattern.compile("\\bseverity=(LOW|MEDIUM|HIGH|CRITICAL)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EFFORT = Pattern.compile("\\beffort=(\\d+(?:\\.\\d+)?)\\b", Pattern.CASE_INSENSITIVE);

    public Report scan(Path root) throws IOException {
        Policy policy = loadPolicy(root);
        Pattern marker = Pattern.compile("\\b(" + String.join("|", policy.markers()) + ")\\b[: -]*(.*)",
            Pattern.CASE_INSENSITIVE);
        List<Item> items = new ArrayList<>();
        List<Path> files = FilesEx.walk(root, p -> accepted(root, p, policy));
        for (Path file : files) {
            List<String> lines;
            try { lines = Files.readAllLines(file); } catch (Exception ignored) { continue; }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int comment = commentStart(line);
                if (comment < 0) continue;
                Matcher match = marker.matcher(line.substring(comment));
                if (match.find()) items.add(item(root, file, i + 1, match.group(1), match.group(2)));
            }
        }
        items.addAll(architecturalDebt(root));
        Map<String, Long> bySeverity = count(items, item -> item.severity().name());
        Map<String, Long> byOwner = count(items, item -> item.owner().isBlank() ? "UNASSIGNED" : item.owner());
        Map<String, Long> byMarker = count(items, Item::marker);
        Map<String, Long> byModule = count(items, Item::module);
        List<Duplicate> duplicates = duplicates(items);
        long overdue = items.stream().filter(Item::overdue).count();
        long malformed = items.stream().filter(item -> item.description().isBlank() || item.owner().isBlank()).count();
        int score = items.stream().mapToInt(item -> item.severity().weight).sum() +
            (int) overdue * 3 + duplicates.size();
        List<Violation> violations = new ArrayList<>();
        if (policy.maxScore() >= 0 && score > policy.maxScore())
            violations.add(new Violation("MAX_SCORE", "Score " + score + " excede " + policy.maxScore()));
        if (policy.requireOwner() && items.stream().anyMatch(item -> item.owner().isBlank()))
            violations.add(new Violation("OWNER_REQUIRED", "Existem itens sem responsável."));
        if (policy.requireTicket() && items.stream().anyMatch(item -> item.ticket().isBlank()))
            violations.add(new Violation("TICKET_REQUIRED", "Existem itens sem ticket."));
        if (overdue > 0) violations.add(new Violation("OVERDUE", overdue + " item(ns) vencido(s)."));
        double effort = items.stream().mapToDouble(Item::effort).sum();
        return new Report(files.size(), items, score, effort, overdue, malformed, bySeverity,
            byOwner, byMarker, byModule, duplicates, violations, violations.isEmpty(), LocalDate.now().toString());
    }

    public Comparison compare(Report baseline, Report current) {
        Set<String> oldKeys = baseline.items().stream().map(this::key).collect(java.util.stream.Collectors.toSet());
        Set<String> newKeys = current.items().stream().map(this::key).collect(java.util.stream.Collectors.toSet());
        List<Item> added = current.items().stream().filter(item -> !oldKeys.contains(key(item))).toList();
        List<Item> resolved = baseline.items().stream().filter(item -> !newKeys.contains(key(item))).toList();
        return new Comparison(current.score() - baseline.score(), added, resolved,
            added.isEmpty() && current.score() <= baseline.score());
    }

    private Item item(Path root, Path file, int line, String marker, String raw) {
        String description = raw.trim();
        String owner = group(OWNER, description).orElse("");
        String ticket = group(TICKET, description).orElse("");
        LocalDate due = group(DUE, description).map(value -> {
            try { return LocalDate.parse(value); } catch (Exception e) { return null; }
        }).orElse(null);
        Severity severity = group(LEVEL, description).map(value -> Severity.valueOf(value.toUpperCase(Locale.ROOT)))
            .orElse(defaultSeverity(marker));
        double effort = group(EFFORT, description).map(Double::parseDouble).orElse(defaultEffort(severity));
        description = description.replaceAll("^\\s*\\([^)]+\\)\\s*", "")
            .replaceAll("\\[[A-Z][A-Z0-9]+-\\d+]\\s*", "")
            .replaceAll("\\b(due|prazo|severity|effort)=[^\\s]+", "").trim();
        String relative = root.relativize(file).toString();
        String module = Path.of(relative).getNameCount() > 1 ? Path.of(relative).getName(0).toString() : "(root)";
        boolean overdue = due != null && due.isBefore(LocalDate.now());
        String suggestion = suggestion(marker, description);
        return new Item(relative, line, marker.toUpperCase(Locale.ROOT), description, severity,
            owner, ticket, due == null ? "" : due.toString(), overdue, effort, module, suggestion, "SOURCE");
    }

    private List<Item> architecturalDebt(Path root) throws IOException {
        Path file = root.resolve(".swissknife").resolve("architectural-debt.json");
        if (!Files.isRegularFile(file)) return List.of();
        Object parsed = Json.parse(Files.readString(file));
        if (!(parsed instanceof List<?> list)) throw new IllegalArgumentException("architectural-debt.json deve ser uma lista");
        List<Item> result = new ArrayList<>();
        int line = 1;
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) continue;
            String description = text(map, "description", "");
            String severityValue = text(map, "severity", "MEDIUM");
            String owner = text(map, "owner", "");
            String ticket = text(map, "ticket", "");
            double effort = map.get("effort") instanceof Number n ? n.doubleValue() : 3;
            result.add(new Item(".swissknife/architectural-debt.json", line++, "ARCHITECTURE", description,
                Severity.valueOf(severityValue.toUpperCase(Locale.ROOT)), owner, ticket, "", false,
                effort, "architecture", suggestion("ARCHITECTURE", description), "ARCHITECTURE"));
        }
        return result;
    }

    private Policy loadPolicy(Path root) throws IOException {
        Properties p = new Properties();
        Path file = root.resolve(".swissknife-debt.properties");
        if (Files.isRegularFile(file)) try (var reader = Files.newBufferedReader(file)) { p.load(reader); }
        Set<String> markers = split(p.getProperty("markers", String.join(",", DEFAULT_MARKERS)));
        Set<String> extensions = split(p.getProperty("extensions", String.join(",", DEFAULT_EXTENSIONS)));
        Set<String> excluded = split(p.getProperty("exclude", "build,target,node_modules,.git,.idea,.gradle"));
        int maxScore;
        try { maxScore = Integer.parseInt(p.getProperty("maxScore", "-1")); } catch (Exception e) { maxScore = -1; }
        return new Policy(markers, extensions, excluded, Boolean.parseBoolean(p.getProperty("requireOwner", "false")),
            Boolean.parseBoolean(p.getProperty("requireTicket", "false")), maxScore);
    }
    private boolean accepted(Path root, Path file, Policy policy) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (policy.excluded().stream().anyMatch(part -> Arrays.asList(relative.split("/")).contains(part))) return false;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return policy.extensions().stream().anyMatch(name::endsWith);
    }
    private int commentStart(String line) {
        return java.util.stream.IntStream.of(line.indexOf("//"), line.indexOf("/*"), line.indexOf('#'), line.indexOf("--"))
            .filter(index -> index >= 0).min().orElse(-1);
    }
    private Severity defaultSeverity(String marker) {
        return switch (marker.toUpperCase(Locale.ROOT)) {
            case "FIXME", "XXX" -> Severity.HIGH;
            case "HACK", "DEBT", "TECHDEBT" -> Severity.MEDIUM;
            default -> Severity.LOW;
        };
    }
    private double defaultEffort(Severity severity) {
        return switch (severity) { case LOW -> 1; case MEDIUM -> 3; case HIGH -> 5; case CRITICAL -> 8; };
    }
    private String suggestion(String marker, String description) {
        String lower = description.toLowerCase(Locale.ROOT);
        if (lower.contains("test")) return "Adicione um teste reproduzível antes de alterar o comportamento.";
        if (lower.contains("performance") || lower.contains("slow")) return "Meça com baseline e perfil antes de otimizar.";
        if (lower.contains("security") || lower.contains("segurança")) return "Priorize a correção e associe uma revisão de segurança.";
        if (marker.equalsIgnoreCase("HACK")) return "Substitua a solução temporária por uma abstração documentada.";
        if (marker.equalsIgnoreCase("ARCHITECTURE")) return "Registre uma ADR e divida a mudança em etapas reversíveis.";
        return "Defina critério de aceite, responsável e ticket para a correção.";
    }
    private List<Duplicate> duplicates(List<Item> items) {
        Map<String, List<Item>> grouped = new LinkedHashMap<>();
        items.stream().filter(item -> !item.description().isBlank()).forEach(item ->
            grouped.computeIfAbsent(normalize(item.description()), ignored -> new ArrayList<>()).add(item));
        return grouped.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
            .map(entry -> new Duplicate(entry.getKey(), entry.getValue())).toList();
    }
    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9áàâãéêíóôõúç ]", "")
            .replaceAll("\\b(o|a|de|do|da|the|and|para)\\b", "").replaceAll("\\s+", " ").trim();
    }
    private String key(Item item) { return item.file() + ":" + item.line() + ":" + normalize(item.description()); }
    private <T> Map<String, Long> count(List<T> values, java.util.function.Function<T, String> classifier) {
        Map<String, Long> result = new TreeMap<>();
        values.forEach(value -> result.merge(classifier.apply(value), 1L, Long::sum));
        return result;
    }
    private Optional<String> group(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value); return matcher.find() ? Optional.ofNullable(matcher.group(1)) : Optional.empty();
    }
    private Set<String> split(String value) {
        return new TreeSet<>(Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
    }
    private String text(Map<?, ?> map, String key, String fallback) {
        return map.containsKey(key) ? String.valueOf(map.get(key)) : fallback;
    }

    public enum Severity {
        LOW(1), MEDIUM(3), HIGH(5), CRITICAL(8);
        final int weight;
        Severity(int weight) { this.weight = weight; }
    }
    public record Policy(Set<String> markers, Set<String> extensions, Set<String> excluded,
                         boolean requireOwner, boolean requireTicket, int maxScore) {}
    public record Item(String file, int line, String marker, String description, Severity severity,
                       String owner, String ticket, String dueDate, boolean overdue, double effort,
                       String module, String suggestion, String source) {}
    public record Duplicate(String normalizedDescription, List<Item> items) {}
    public record Violation(String kind, String description) {}
    public record Report(int filesScanned, List<Item> items, int score, double estimatedEffort,
                         long overdue, long malformed, Map<String, Long> bySeverity,
                         Map<String, Long> byOwner, Map<String, Long> byMarker,
                         Map<String, Long> byModule, List<Duplicate> duplicates,
                         List<Violation> violations, boolean passed, String generatedAt) {}
    public record Comparison(int scoreDelta, List<Item> added, List<Item> resolved, boolean passed) {}
}
