package dev.swissknife.sql;

import java.util.*;
import java.util.regex.*;

public final class SlowQueryAnalyzer {
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:from|update|into)\\s+([\\w.]+)");
    private static final Pattern FILTER = Pattern.compile("(?i)\\b(?:where|and|or)\\s+([\\w.]+)\\s*(?:=|>|<|like|in\\b)");
    private static final Pattern JOIN = Pattern.compile("(?i)\\bjoin\\s+[\\w.]+\\s+(?:\\w+\\s+)?on\\s+([\\w.]+)\\s*=");
    private static final Pattern ORDER = Pattern.compile("(?i)\\border\\s+by\\s+([\\w.]+)");

    public Analysis analyze(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Query vazia");
        String table = find(TABLE, sql).orElse("tabela");
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        collect(FILTER, sql, columns);
        collect(JOIN, sql, columns);
        collect(ORDER, sql, columns);
        columns = new LinkedHashSet<>(columns.stream().map(this::unqualify).toList());
        List<String> warnings = new ArrayList<>();
        if (Pattern.compile("(?i)select\\s+\\*").matcher(sql).find()) warnings.add("Evite SELECT *; leia apenas as colunas necessárias.");
        if (!Pattern.compile("(?i)\\bwhere\\b").matcher(sql).find()) warnings.add("Query sem WHERE pode realizar varredura completa.");
        if (Pattern.compile("(?i)\\b(?:lower|upper|date)\\s*\\(").matcher(sql).find())
            warnings.add("Função aplicada em coluna pode impedir o uso de índice.");
        if (Pattern.compile("(?i)like\\s+['\"]%").matcher(sql).find())
            warnings.add("LIKE iniciado por % normalmente não usa índice B-tree.");
        List<String> indexes = columns.isEmpty() ? List.of() : List.of("CREATE INDEX idx_" +
            table.replaceAll("\\W", "_") + "_" + String.join("_", columns) + " ON " + table +
            " (" + String.join(", ", columns) + ");");
        int score = Math.min(100, warnings.size() * 20 + (columns.isEmpty() ? 15 : 0));
        return new Analysis(table, List.copyOf(columns), indexes, warnings, score);
    }

    private Optional<String> find(Pattern p, String s) { var m = p.matcher(s); return m.find() ? Optional.of(m.group(1)) : Optional.empty(); }
    private void collect(Pattern p, String s, Set<String> out) { var m = p.matcher(s); while (m.find()) out.add(m.group(1)); }
    private String unqualify(String value) { int dot = value.lastIndexOf('.'); return dot < 0 ? value : value.substring(dot + 1); }
    public record Analysis(String table, List<String> columns, List<String> suggestedIndexes,
                           List<String> warnings, int riskScore) {}
}
