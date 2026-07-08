package dev.swissknife.sql;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Executa EXPLAIN sem mutação e transforma planos textuais em recomendações. */
public final class ExplainPlanAnalyzer {
    private static final Pattern SAFE_QUERY = Pattern.compile("(?is)^\\s*(select|with)\\b");
    private static final Pattern COST = Pattern.compile("cost=([0-9.]+)\\.\\.([0-9.]+)");
    private static final Pattern ROWS = Pattern.compile("rows=([0-9]+)");
    private static final Pattern ACTUAL = Pattern.compile("actual time=([0-9.]+)\\.\\.([0-9.]+).*?rows=([0-9]+)");

    public Report explain(Config config, String sql) throws SQLException {
        validateQuery(sql);
        long started = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setReadOnly(true);
            if (config.timeoutSeconds() > 0) connection.setNetworkTimeout(Runnable::run, config.timeoutSeconds() * 1_000);
            String explain = prefix(config.dialect(), config.analyze()) + sql;
            List<String> lines = new ArrayList<>();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(config.timeoutSeconds());
                statement.setMaxRows(10_000);
                try (ResultSet result = statement.executeQuery(explain)) {
                    int columns = result.getMetaData().getColumnCount();
                    while (result.next()) {
                        StringJoiner line = new StringJoiner(" | ");
                        for (int i = 1; i <= columns; i++) line.add(Objects.toString(result.getObject(i), ""));
                        lines.add(line.toString());
                    }
                }
            }
            PlanAnalysis analysis = analyzePlan(String.join("\n", lines), config.dialect());
            return new Report(sql, explain, lines, analysis,
                Duration.ofNanos(System.nanoTime() - started).toMillis(), config.dialect());
        }
    }

    public PlanAnalysis analyzePlan(String plan, Dialect dialect) {
        if (plan == null || plan.isBlank()) throw new IllegalArgumentException("Plano vazio");
        String lower = plan.toLowerCase(Locale.ROOT);
        List<Finding> findings = new ArrayList<>();
        add(findings, lower.contains("seq scan") || lower.contains("table scan") || lower.contains("type: all"),
            "SEQUENTIAL_SCAN", "HIGH", "Varredura completa detectada; avalie filtro seletivo e índice.");
        add(findings, lower.contains("filesort") || lower.contains("external merge") || lower.contains("sort method: external"),
            "DISK_SORT", "HIGH", "Ordenação externa/em disco detectada; avalie índice compatível com ORDER BY.");
        add(findings, lower.contains("nested loop"),
            "NESTED_LOOP", "MEDIUM", "Nested loop detectado; confirme que o lado interno usa índice.");
        add(findings, lower.contains("hash join"),
            "HASH_JOIN", "LOW", "Hash join detectado; confira memória e cardinalidade das entradas.");
        add(findings, lower.contains("temporary") || lower.contains("temp table"),
            "TEMPORARY_TABLE", "MEDIUM", "Tabela temporária detectada no plano.");
        add(findings, lower.contains("never executed"),
            "DEAD_BRANCH", "LOW", "Ramo do plano nunca executado; revise estimativas e condições.");
        long estimatedRows = maximum(ROWS, plan, 1);
        double totalCost = maximumDouble(COST, plan, 2);
        Actual actual = actual(plan);
        if (actual != null && estimatedRows > 0) {
            double ratio = Math.max(actual.rows(), estimatedRows) / (double) Math.max(1, Math.min(actual.rows(), estimatedRows));
            add(findings, ratio >= 10, "CARDINALITY_MISMATCH", "HIGH",
                "Estimativa de cardinalidade diverge em " + String.format(Locale.ROOT, "%.1fx", ratio) +
                    "; atualize estatísticas ou revise predicados.");
        }
        int score = Math.min(100, findings.stream().mapToInt(f -> switch (f.severity()) {
            case "HIGH" -> 30; case "MEDIUM" -> 15; default -> 5;
        }).sum());
        return new PlanAnalysis(dialect, estimatedRows, totalCost,
            actual == null ? null : actual.maximumMs(), actual == null ? null : actual.rows(),
            score, List.copyOf(findings), plan);
    }

    public void validateQuery(String sql) {
        if (sql == null || !SAFE_QUERY.matcher(sql).find())
            throw new IllegalArgumentException("EXPLAIN aceita somente SELECT ou WITH");
        String stripped = sql.replaceAll("(?s)'(?:''|[^'])*'", "''").replaceAll("(?m)--.*$", "");
        if (stripped.contains(";") || Pattern.compile("(?i)\\b(insert|update|delete|merge|drop|alter|truncate|call|copy)\\b").matcher(stripped).find())
            throw new IllegalArgumentException("Query contém operação ou múltiplos comandos não permitidos");
    }

    private String prefix(Dialect dialect, boolean analyze) {
        return switch (dialect) {
            case POSTGRESQL -> analyze ? "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " : "EXPLAIN (FORMAT TEXT) ";
            case MYSQL, MARIADB -> analyze ? "EXPLAIN ANALYZE " : "EXPLAIN ";
            case ORACLE -> "EXPLAIN PLAN FOR ";
            case SQLSERVER -> throw new IllegalArgumentException("SQL Server requer SHOWPLAN_XML em sessão dedicada; use plano fornecido.");
            case H2, GENERIC -> "EXPLAIN ";
        };
    }
    private void add(List<Finding> findings, boolean condition, String code, String severity, String message) {
        if (condition) findings.add(new Finding(code, severity, message));
    }
    private long maximum(Pattern pattern, String text, int group) {
        long max = 0; Matcher matcher = pattern.matcher(text);
        while (matcher.find()) max = Math.max(max, Long.parseLong(matcher.group(group)));
        return max;
    }
    private double maximumDouble(Pattern pattern, String text, int group) {
        double max = 0; Matcher matcher = pattern.matcher(text);
        while (matcher.find()) max = Math.max(max, Double.parseDouble(matcher.group(group)));
        return max;
    }
    private Actual actual(String text) {
        Matcher matcher = ACTUAL.matcher(text); double time = 0; long rows = 0; boolean found = false;
        while (matcher.find()) {
            found = true; time = Math.max(time, Double.parseDouble(matcher.group(2)));
            rows = Math.max(rows, Long.parseLong(matcher.group(3)));
        }
        return found ? new Actual(time, rows) : null;
    }

    public enum Dialect { POSTGRESQL, MYSQL, MARIADB, SQLSERVER, ORACLE, H2, GENERIC }
    public record Config(String url, String user, String password, Dialect dialect,
                         boolean analyze, int timeoutSeconds) {
        public Config { if (timeoutSeconds < 1) timeoutSeconds = 15; }
    }
    public record Finding(String code, String severity, String recommendation) {}
    public record PlanAnalysis(Dialect dialect, long estimatedRows, double totalCost,
                               Double actualMaximumMs, Long actualRows, int riskScore,
                               List<Finding> findings, String rawPlan) {}
    public record Report(String query, String explainStatement, List<String> planLines,
                         PlanAnalysis analysis, long durationMs, Dialect dialect) {}
    private record Actual(double maximumMs, long rows) {}
}
