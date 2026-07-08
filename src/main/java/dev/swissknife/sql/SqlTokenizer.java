package dev.swissknife.sql;

import java.util.*;

/** Tokenizador real de SQL: separa palavras-chave, identificadores, literais, operadores e pontuação. */
public final class SqlTokenizer {
    public enum Type { KEYWORD, IDENTIFIER, STRING, NUMBER, PUNCTUATION, OPERATOR, EOF }
    public record Token(Type type, String text, int position) {}

    private static final Set<String> KEYWORDS = Set.of(
        "SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "ON",
        "GROUP", "BY", "ORDER", "HAVING", "AS", "AND", "OR", "NOT", "IN", "EXISTS", "NULL",
        "IS", "LIKE", "BETWEEN", "DISTINCT", "LIMIT", "OFFSET", "UNION", "ALL", "WITH",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CASE", "WHEN", "THEN", "ELSE", "END",
        "OVER", "PARTITION", "ASC", "DESC");

    public List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i); i = end < 0 ? length : end + 1; continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2); i = end < 0 ? length : end + 2; continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int start = i; char quote = c; i++;
                while (i < length && sql.charAt(i) != quote) { if (sql.charAt(i) == '\\' && i + 1 < length) i++; i++; }
                i = Math.min(length, i + 1);
                tokens.add(new Token(quote == '\'' ? Type.STRING : Type.IDENTIFIER, sql.substring(start, i), start));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i; while (i < length && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                tokens.add(new Token(Type.NUMBER, sql.substring(start, i), start)); continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i; while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '.' || sql.charAt(i) == '$')) i++;
                String word = sql.substring(start, i);
                tokens.add(new Token(KEYWORDS.contains(word.toUpperCase(Locale.ROOT)) ? Type.KEYWORD : Type.IDENTIFIER, word, start));
                continue;
            }
            if ("(),;*".indexOf(c) >= 0) { tokens.add(new Token(Type.PUNCTUATION, String.valueOf(c), i)); i++; continue; }
            if ("=<>!+-/%".indexOf(c) >= 0) {
                int start = i; i++;
                if (i < length && "=<>".indexOf(sql.charAt(i)) >= 0) i++;
                tokens.add(new Token(Type.OPERATOR, sql.substring(start, i), start)); continue;
            }
            i++;
        }
        tokens.add(new Token(Type.EOF, "", length));
        return tokens;
    }
}
