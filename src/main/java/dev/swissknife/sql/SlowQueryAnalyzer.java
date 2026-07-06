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
        if (Pattern.compile("(?i)\\boffset\\s+(?:[1-9]\\d{3,}|\\?)").matcher(sql).find())
            warnings.add("OFFSET elevado degrada com o volume; prefira paginação por cursor.");
        if (Pattern.compile("(?i)\\bnot\\s+in\\s*\\(").matcher(sql).find())
            warnings.add("NOT IN pode produzir resultados inesperados com NULL; avalie NOT EXISTS.");
        if (Pattern.compile("(?i)\\bwhere\\b[^;]+\\bor\\b").matcher(sql).find())
            warnings.add("OR pode impedir um plano de índice eficiente; avalie UNION ou índices específicos.");
        if (Pattern.compile("(?i)^\\s*(update|delete)\\b").matcher(sql).find() &&
            !Pattern.compile("(?i)\\bwhere\\b").matcher(sql).find())
            warnings.add("DML destrutivo sem WHERE afeta todas as linhas.");
        if (Pattern.compile("(?i)\\bselect\\b.*\\bselect\\b", Pattern.DOTALL).matcher(sql).find())
            warnings.add("Subquery detectada; confira cardinalidade e possibilidade de join/CTE.");
        if (Pattern.compile("(?i)\\bgroup\\s+by\\b").matcher(sql).find() &&
            !Pattern.compile("(?i)\\bwhere\\b").matcher(sql).find())
            warnings.add("Agregação sem filtro pode processar toda a tabela.");
        if (Pattern.compile("(?i)\\bunion\\b(?!\\s+all)").matcher(sql).find())
            warnings.add("UNION elimina duplicatas e exige ordenação; use UNION ALL quando possível.");
        List<String> rewrites = new ArrayList<>();
        if (warnings.stream().anyMatch(w -> w.contains("SELECT *")))
            rewrites.add("Substitua * pela lista explícita de colunas.");
        if (warnings.stream().anyMatch(w -> w.contains("paginação por cursor")))
            rewrites.add("Use WHERE chave > :ultimaChave ORDER BY chave LIMIT :limite.");
        if (warnings.stream().anyMatch(w -> w.contains("NOT EXISTS")))
            rewrites.add("Reescreva NOT IN como NOT EXISTS com correlação pela chave.");
        List<String> indexes = columns.isEmpty() ? List.of() : List.of("CREATE INDEX idx_" +
            table.replaceAll("\\W", "_") + "_" + String.join("_", columns) + " ON " + table +
            " (" + String.join(", ", columns) + ");");
        int score = Math.min(100, warnings.size() * 20 + (columns.isEmpty() ? 15 : 0));
        return new Analysis(table, List.copyOf(columns), indexes, warnings, score,
            List.copyOf(rewrites), classify(sql));
    }

    public BatchAnalysis analyzeBatch(String content) {
        List<String> statements = splitStatements(content);
        List<Analysis> analyses = statements.stream().filter(s -> !s.isBlank()).map(this::analyze).toList();
        return new BatchAnalysis(analyses.size(), analyses.stream().mapToInt(Analysis::riskScore).max().orElse(0),
            analyses.stream().mapToInt(Analysis::riskScore).sum() / Math.max(1, analyses.size()), analyses);
    }

    public BatchAnalysis analyzeLog(String log) {
        List<String> queries = new ArrayList<>();
        Pattern sql = Pattern.compile("(?im)(?:Hibernate:\\s*|SQL(?:Statement)?\\s*[:=]\\s*)?" +
            "((?:select|insert|update|delete|with)\\b[^\\r\\n;]*(?:;)?)");
        Matcher matcher = sql.matcher(log);
        while (matcher.find()) queries.add(matcher.group(1));
        return analyzeBatch(String.join(";\n", queries));
    }

    private List<String> splitStatements(String content) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, dbl = false;
        for (char c : content.toCharArray()) {
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            if (c == ';' && !single && !dbl) {
                if (!current.toString().isBlank()) result.add(current.toString().trim());
                current.setLength(0);
            } else current.append(c);
        }
        if (!current.toString().isBlank()) result.add(current.toString().trim());
        return result;
    }
    private String classify(String sql) {
        Matcher m = Pattern.compile("(?i)^\\s*(select|insert|update|delete|with)").matcher(sql);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "OTHER";
    }

    private Optional<String> find(Pattern p, String s) { var m = p.matcher(s); return m.find() ? Optional.of(m.group(1)) : Optional.empty(); }
    private void collect(Pattern p, String s, Set<String> out) { var m = p.matcher(s); while (m.find()) out.add(m.group(1)); }
    private String unqualify(String value) { int dot = value.lastIndexOf('.'); return dot < 0 ? value : value.substring(dot + 1); }
    public record Analysis(String table, List<String> columns, List<String> suggestedIndexes,
                           List<String> warnings, int riskScore, List<String> rewrites,
                           String statementType) {}
    public record BatchAnalysis(int statements, int maximumRisk, int averageRisk,
                                List<Analysis> analyses) {}
}
