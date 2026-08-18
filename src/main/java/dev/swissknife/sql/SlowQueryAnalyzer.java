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
        SqlParser.SelectStatement parsed = tryParseSelect(sql);
        if (parsed != null) {
            if (!parsed.table().isBlank()) table = parsed.table();
            LinkedHashSet<String> structural = new LinkedHashSet<>();
            structural.addAll(parsed.whereColumns());
            parsed.joins().forEach(j -> structural.addAll(j.onColumns()));
            structural.addAll(parsed.orderByColumns());
            if (!structural.isEmpty()) columns = structural;
        }
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
        int joinCount = parsed != null ? parsed.joinCount() : countMatches(Pattern.compile("(?i)\\bjoin\\b"), sql);
        if (joinCount > 3) warnings.add("Query com " + joinCount + " joins; avalie desnormalização ou views materializadas.");
        boolean windowFunction = parsed != null ? parsed.hasWindowFunction() : Pattern.compile("(?i)\\bover\\s*\\(").matcher(sql).find();
        if (windowFunction) warnings.add("Função de janela (OVER) detectada; confirme índice de particionamento/ordenação.");
        boolean destructiveWithoutFilter = Pattern.compile("(?i)^\\s*(drop|truncate)\\b").matcher(sql).find();
        if (destructiveWithoutFilter) warnings.add("Comando destrutivo (DROP/TRUNCATE) detectado; bloqueie em pipelines automatizados.");
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
        boolean blocksCi = warnings.stream().anyMatch(w -> w.contains("DROP/TRUNCATE") || w.contains("DML destrutivo"));
        return new Analysis(table, List.copyOf(columns), indexes, warnings, score,
            List.copyOf(rewrites), classify(sql), joinCount, windowFunction, blocksCi, parsed != null);
    }

    /** Tenta o parser SQL real (tokenizer + AST); retorna null silenciosamente para DML/DDL ou queries que o parser não reconhece. */
    private SqlParser.SelectStatement tryParseSelect(String sql) {
        if (!Pattern.compile("(?i)^\\s*(with\\b.*)?\\bselect\\b").matcher(sql).find()) return null;
        try { return SqlParser.parseSelect(sql); } catch (Exception notParseable) { return null; }
    }

    public BatchAnalysis analyzeBatch(String content) {
        List<String> statements = splitStatements(content);
        List<Analysis> analyses = statements.stream().filter(s -> !s.isBlank()).map(this::analyze).toList();
        List<Analysis> ranked = analyses.stream()
            .sorted(Comparator.comparingInt(Analysis::riskScore).reversed()).limit(5).toList();
        return new BatchAnalysis(analyses.size(), analyses.stream().mapToInt(Analysis::riskScore).max().orElse(0),
            analyses.stream().mapToInt(Analysis::riskScore).sum() / Math.max(1, analyses.size()), analyses, ranked);
    }

    public BatchAnalysis analyzeLog(String log) {
        List<String> queries = new ArrayList<>();
        Pattern sql = Pattern.compile("(?im)(?:Hibernate:\\s*|SQL(?:Statement)?\\s*[:=]\\s*)?" +
            "((?:select|insert|update|delete|with)\\b[^\\r\\n;]*(?:;)?)");
        Matcher matcher = sql.matcher(log);
        while (matcher.find()) queries.add(matcher.group(1));
        BatchAnalysis batch = analyzeBatch(String.join(";\n", queries));
        List<String> nPlusOne = detectNPlusOne(queries);
        return nPlusOne.isEmpty() ? batch : new BatchAnalysis(batch.statements(), batch.maximumRisk(),
            batch.averageRisk(), batch.analyses(), batch.mostProblematic(), nPlusOne);
    }

    /** Detecta padrão N+1: a mesma "forma" de query (literais normalizados) repetida muitas vezes. */
    private List<String> detectNPlusOne(List<String> queries) {
        Map<String, Integer> shapes = new LinkedHashMap<>();
        for (String query : queries) {
            String shape = query.toLowerCase(Locale.ROOT)
                .replaceAll("'[^']*'", "?").replaceAll("\\b\\d+\\b", "?").replaceAll("\\s+", " ").strip();
            shapes.merge(shape, 1, Integer::sum);
        }
        List<String> findings = new ArrayList<>();
        shapes.forEach((shape, count) -> {
            if (count >= 5) findings.add("Possível N+1: a mesma query executada " + count +
                " vezes (\"" + (shape.length() > 80 ? shape.substring(0, 80) + "…" : shape) + "\")");
        });
        return findings;
    }

    /** Gera um script SQL com os índices sugeridos pela análise em lote. */
    public String indexMigrationScript(BatchAnalysis batch) {
        LinkedHashSet<String> statements = new LinkedHashSet<>();
        batch.analyses().forEach(a -> statements.addAll(a.suggestedIndexes()));
        StringBuilder out = new StringBuilder("-- Índices sugeridos por SwissKnife Javanist\n");
        statements.forEach(s -> out.append(s).append("\n"));
        return out.toString();
    }

    /**
     * Gera um relatório HTML consolidado da análise em lote.
     *
     * Todo valor interpolado passa por escape(): o conteúdo NÃO é confiável. O SqlTokenizer devolve
     * identificadores entre aspas com o texto bruto (por exemplo {@code FROM "<img src=x onerror=...>"}),
     * então esse texto chega intacto em Analysis.table(); e nPlusOneFindings() carrega trechos crus do
     * log analisado. Sem escape, um .sql/.log de terceiro vira XSS armazenado no relatório que o
     * engenheiro abre no navegador — e slow-query-file/slow-query-log são expostos pelo portal.
     */
    public String html(BatchAnalysis batch) {
        StringBuilder rows = new StringBuilder();
        batch.analyses().forEach(a -> rows.append("<tr><td>").append(escape(a.table())).append("</td><td>")
            .append(escape(a.statementType())).append("</td><td>").append(a.riskScore()).append("</td><td>")
            .append(escape(String.join("; ", a.warnings()))).append("</td></tr>"));
        StringBuilder nplus1 = new StringBuilder();
        batch.nPlusOneFindings().forEach(f -> nplus1.append("<li>").append(escape(f)).append("</li>"));
        return """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Relatório de queries lentas</title>
            <style>:root{color-scheme:light dark}body{font:15px system-ui;max-width:1100px;margin:auto;padding:2rem}
            table{border-collapse:collapse;width:100%%}th,td{padding:.5rem;border-bottom:1px solid #8885;text-align:left}
            </style></head><body><h1>Relatório de queries lentas</h1>
            <p>%d statements · risco máximo %d · risco médio %d</p>
            <ul>%s</ul>
            <table><thead><tr><th>Tabela</th><th>Tipo</th><th>Risco</th><th>Alertas</th></tr></thead><tbody>%s</tbody></table>
            </body></html>
            """.formatted(batch.statements(), batch.maximumRisk(), batch.averageRisk(), nplus1, rows);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
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
                           String statementType, int joinCount, boolean windowFunction, boolean blocksCi,
                           boolean parsedByRealParser) {}
    public record BatchAnalysis(int statements, int maximumRisk, int averageRisk,
                                List<Analysis> analyses, List<Analysis> mostProblematic,
                                List<String> nPlusOneFindings) {
        public BatchAnalysis(int statements, int maximumRisk, int averageRisk,
                             List<Analysis> analyses, List<Analysis> mostProblematic) {
            this(statements, maximumRisk, averageRisk, analyses, mostProblematic, List.of());
        }
    }
}
