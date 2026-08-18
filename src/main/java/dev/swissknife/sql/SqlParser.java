package dev.swissknife.sql;

import java.util.*;

/**
 * Parser real (não regex) para um subconjunto de SELECT ANSI SQL: tokeniza a query e percorre
 * a estrutura reconhecendo FROM, JOINs (com condição ON), WHERE, GROUP BY, ORDER BY, HAVING,
 * subqueries/CTEs (por profundidade de parênteses) e funções de janela (OVER).
 */
public final class SqlParser {
    private final List<SqlTokenizer.Token> tokens;
    private int position;

    private SqlParser(List<SqlTokenizer.Token> tokens) { this.tokens = tokens; }

    public static SelectStatement parseSelect(String sql) {
        return new SqlParser(new SqlTokenizer().tokenize(sql)).parse();
    }

    private SelectStatement parse() {
        boolean isCte = peekKeyword("WITH");
        if (isCte) skipToKeyword("SELECT");
        expectKeyword("SELECT");
        List<String> columns = new ArrayList<>();
        boolean distinct = peekKeyword("DISTINCT"); if (distinct) advance();
        boolean star = false;
        int depth = 0;
        StringBuilder current = new StringBuilder();
        while (!peekKeyword("FROM") && !atEnd()) {
            var token = current();
            if (token.text().equals("(")) depth++;
            if (token.text().equals(")")) depth--;
            if (depth == 0 && token.text().equals(",")) { addColumn(columns, current); current.setLength(0); advance(); continue; }
            if (depth == 0 && token.text().equals("*")) star = true;
            current.append(current.isEmpty() ? "" : " ").append(token.text());
            advance();
        }
        addColumn(columns, current);
        List<String> tables = new ArrayList<>();
        List<Join> joins = new ArrayList<>();
        String mainTable = "";
        if (peekKeyword("FROM")) {
            advance();
            mainTable = readTableReference();
            tables.add(mainTable);
            while (peekJoin()) joins.add(readJoin());
        }
        List<String> whereColumns = new ArrayList<>();
        boolean hasWhere = false;
        if (peekKeyword("WHERE")) {
            advance(); hasWhere = true;
            whereColumns.addAll(readColumnReferences(Set.of("GROUP", "ORDER", "HAVING", "LIMIT", "UNION")));
        }
        List<String> groupBy = new ArrayList<>();
        if (peekKeyword("GROUP")) {
            advance(); expectKeyword("BY");
            groupBy.addAll(readColumnReferences(Set.of("ORDER", "HAVING", "LIMIT", "UNION")));
        }
        boolean hasHaving = peekKeyword("HAVING");
        List<String> orderBy = new ArrayList<>();
        if (peekKeyword("HAVING")) { advance(); readColumnReferences(Set.of("ORDER", "LIMIT", "UNION")); }
        if (peekKeyword("ORDER")) {
            advance(); expectKeyword("BY");
            orderBy.addAll(readColumnReferences(Set.of("LIMIT", "UNION")));
        }
        boolean hasWindowFunction = tokens.stream().anyMatch(t -> t.text().equalsIgnoreCase("OVER"));
        int joinCount = joins.size();
        return new SelectStatement(distinct, star, columns, mainTable, joins, hasWhere, whereColumns,
            groupBy, hasHaving, orderBy, joinCount, hasWindowFunction, isCte);
    }

    private void addColumn(List<String> columns, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty() && !value.equals("*")) columns.add(unqualify(firstIdentifier(value)));
    }

    private String readTableReference() {
        if (atEnd() || current().type() != SqlTokenizer.Type.IDENTIFIER) return "";
        String name = current().text();
        advance();
        if (peekKeyword("AS")) advance();
        if (!atEnd() && current().type() == SqlTokenizer.Type.IDENTIFIER && !isReservedNext()) advance();
        return unqualify(name);
    }

    private boolean peekJoin() {
        return peekKeyword("JOIN") || peekKeyword("INNER") || peekKeyword("LEFT") || peekKeyword("RIGHT") || peekKeyword("FULL");
    }
    private Join readJoin() {
        StringBuilder kind = new StringBuilder();
        while (peekKeyword("INNER") || peekKeyword("LEFT") || peekKeyword("RIGHT") || peekKeyword("FULL") || peekKeyword("OUTER")) {
            kind.append(current().text()).append(" "); advance();
        }
        expectKeyword("JOIN");
        String table = readTableReference();
        List<String> onColumns = new ArrayList<>();
        if (peekKeyword("ON")) { advance(); onColumns.addAll(readColumnReferences(Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "WHERE", "GROUP", "ORDER"))); }
        return new Join(kind.toString().isBlank() ? "INNER" : kind.toString().trim(), table, onColumns);
    }

    private List<String> readColumnReferences(Set<String> stopKeywords) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        while (!atEnd()) {
            var token = current();
            if (token.type() == SqlTokenizer.Type.KEYWORD && depth == 0 && stopKeywords.contains(token.text().toUpperCase(Locale.ROOT))) break;
            if (token.text().equals("(")) depth++;
            if (token.text().equals(")")) { if (depth == 0) break; depth--; }
            if (token.type() == SqlTokenizer.Type.IDENTIFIER && depth == 0) {
                var next = peekAhead(1);
                boolean isFunctionCall = next != null && next.text().equals("(");
                if (!isFunctionCall) result.add(unqualify(token.text()));
            }
            advance();
        }
        return result;
    }

    private boolean isReservedNext() {
        String upper = current().text().toUpperCase(Locale.ROOT);
        return Set.of("WHERE", "JOIN", "ON", "GROUP", "ORDER", "INNER", "LEFT", "RIGHT", "FULL").contains(upper);
    }
    /**
     * Extrai o alias/identificador de uma expressão de coluna já normalizada com espaços simples.
     * Usa o ÚLTIMO "AS" no nível textual (não o primeiro espaço) para não confundir a saída com
     * palavras internas de expressões multi-token como "CASE WHEN x THEN y ELSE z END AS alias".
     */
    private String firstIdentifier(String expression) {
        String[] byAs = expression.split("(?i)\\bAS\\b");
        if (byAs.length > 1) return byAs[byAs.length - 1].trim().replaceAll("[()]", "");
        String[] parts = expression.trim().split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1].replaceAll("[()]", "") : expression;
    }
    private String unqualify(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }
    private void skipToKeyword(String keyword) { while (!atEnd() && !peekKeyword(keyword)) advance(); }
    private boolean peekKeyword(String keyword) {
        return !atEnd() && current().type() == SqlTokenizer.Type.KEYWORD && current().text().equalsIgnoreCase(keyword);
    }
    private void expectKeyword(String keyword) {
        if (!peekKeyword(keyword)) throw new IllegalArgumentException("Esperado '" + keyword + "' na posição " + (atEnd() ? -1 : current().position()));
        advance();
    }
    private SqlTokenizer.Token current() { return tokens.get(position); }
    private SqlTokenizer.Token peekAhead(int offset) { int idx = position + offset; return idx < tokens.size() ? tokens.get(idx) : null; }
    private boolean atEnd() { return current().type() == SqlTokenizer.Type.EOF; }
    private void advance() { if (!atEnd()) position++; }

    public record Join(String kind, String table, List<String> onColumns) {}
    public record SelectStatement(boolean distinct, boolean selectStar, List<String> selectColumns,
                                  String table, List<Join> joins, boolean hasWhere, List<String> whereColumns,
                                  List<String> groupByColumns, boolean hasHaving, List<String> orderByColumns,
                                  int joinCount, boolean hasWindowFunction, boolean isCte) {}
}
