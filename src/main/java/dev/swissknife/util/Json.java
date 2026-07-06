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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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
        private final String text;
        private int pos;
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
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return map;
            do {
                whitespace();
                if (text.charAt(pos) != '"') fail("chave esperada");
                var key = string();
                whitespace();
                if (!consume(':')) fail("':' esperado");
                map.put(key, value());
                whitespace();
            } while (consume(','));
            if (!consume('}')) fail("'}' esperado");
            return map;
        }

        private List<Object> array() {
            pos++;
            List<Object> list = new ArrayList<>();
            whitespace();
            if (consume(']')) return list;
            do { list.add(value()); whitespace(); } while (consume(','));
            if (!consume(']')) fail("']' esperado");
            return list;
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
                        case 'u' -> (char) Integer.parseInt(text.substring(pos, pos += 4), 16);
                        default -> throw new IllegalArgumentException("Escape JSON inválido: " + e);
                    });
                } else out.append(c);
            }
            fail("string não terminada");
            return null;
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            try {
                var n = text.substring(start, pos);
                return n.contains(".") || n.contains("e") || n.contains("E")
                    ? Double.parseDouble(n) : Long.parseLong(n);
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
