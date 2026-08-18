package dev.swissknife.util;

import java.util.*;
import java.lang.reflect.*;
import java.nio.file.Path;

/** Minimal JSON codec supporting maps, lists and primitive values. */
public final class Json {
    private Json() {}

    public static String stringify(Object value) {
        var out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof String s) out.append('"').append(escape(s)).append('"');
        else if (value instanceof Path || value instanceof Enum<?>) write(value.toString(), out);
        else if (value instanceof Number || value instanceof Boolean) out.append(value);
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                write(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value.getClass().isRecord()) {
            Map<String, Object> record = new LinkedHashMap<>();
            for (var component : value.getClass().getRecordComponents()) {
                try { record.put(component.getName(), component.getAccessor().invoke(value)); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
            write(record, out);
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            boolean first = true;
            for (var item : values) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else write(value.toString(), out);
    }

    /**
     * Escapa conforme RFC 8259: além de {@code \} e {@code "}, cada caractere de controle
     * (U+0000..U+001F) precisa virar escape. Trocar apenas \n\r\t deixava caracteres como
     * U+0000/U+0008/U+001B crus dentro da string, produzindo JSON que parsers externos
     * (SARIF do GitHub, CycloneDX, jq) rejeitam — e este projeto exporta exatamente esses formatos.
     */
    private static String escape(String value) {
        var out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    public static Object parse(String json) {
        if (json != null && !json.isEmpty() && json.charAt(0) == '\uFEFF') json = json.substring(1);
        return new Parser(json).parse();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String json) {
        var parsed = parse(json);
        if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON deve ser um objeto");
        return (Map<String, Object>) parsed;
    }

    private static final class Parser {
        /**
         * Profundidade máxima de aninhamento. Sem este limite, um corpo como "[[[[[…" vindo de
         * qualquer endpoint HTTP derruba a thread com StackOverflowError (que não é Exception e
         * portanto escapa dos catch dos handlers). 200 níveis excedem qualquer documento legítimo.
         */
        private static final int MAX_DEPTH = 200;
        private final String text;
        private int pos;
        private int depth;
        Parser(String text) { this.text = Objects.requireNonNull(text); }

        Object parse() {
            var result = value();
            whitespace();
            if (pos != text.length()) fail("conteúdo após o JSON");
            return result;
        }

        private Object value() {
            whitespace();
            if (pos >= text.length()) fail("fim inesperado");
            return switch (text.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            enter();
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) { depth--; return map; }
            do {
                whitespace();
                if (pos >= text.length() || text.charAt(pos) != '"') fail("chave esperada");
                var key = string();
                whitespace();
                if (!consume(':')) fail("':' esperado");
                map.put(key, value());
                whitespace();
            } while (consume(','));
            if (!consume('}')) fail("'}' esperado");
            depth--;
            return map;
        }

        private List<Object> array() {
            enter();
            pos++;
            List<Object> list = new ArrayList<>();
            whitespace();
            if (consume(']')) { depth--; return list; }
            do { list.add(value()); whitespace(); } while (consume(','));
            if (!consume(']')) fail("']' esperado");
            depth--;
            return list;
        }

        private void enter() {
            if (++depth > MAX_DEPTH) fail("aninhamento JSON excede " + MAX_DEPTH + " níveis");
        }

        private String string() {
            pos++;
            var out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (pos >= text.length()) fail("escape incompleto");
                    char e = text.charAt(pos++);
                    out.append(switch (e) {
                        case '"', '\\', '/' -> e;
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                        case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> unicodeEscape();
                        default -> throw new IllegalArgumentException("Escape JSON inválido: " + e);
                    });
                } else out.append(c);
            }
            fail("string não terminada");
            return null;
        }

        /** \\uXXXX truncado no fim do texto lançava StringIndexOutOfBounds (→ HTTP 500) em vez de erro de sintaxe (→ 400). */
        private char unicodeEscape() {
            if (pos + 4 > text.length()) fail("escape \\u incompleto");
            String hex = text.substring(pos, pos + 4);
            pos += 4;
            try { return (char) Integer.parseInt(hex, 16); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Escape \\u inválido: " + hex); }
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            try {
                var n = text.substring(start, pos);
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            } catch (NumberFormatException e) { fail("valor inválido"); return null; }
        }

        private Object literal(String token, Object value) {
            if (!text.startsWith(token, pos)) fail("literal inválido");
            pos += token.length();
            return value;
        }

        private boolean consume(char c) {
            if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; }
            return false;
        }
        private void whitespace() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        private void fail(String message) { throw new IllegalArgumentException(message + " na posição " + pos); }
    }
}
