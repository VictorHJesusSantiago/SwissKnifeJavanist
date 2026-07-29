package dev.swissknife.cli;

import dev.swissknife.util.Json;
import java.util.*;

/** Serializa resultados da suíte nos formatos usados por terminal, CI e IDE. */
public final class OutputFormatter {
    private OutputFormatter() {}

    public static String format(Object value, String format) {
        String normalized = format == null ? "json" : format.toLowerCase(Locale.ROOT);
        Object tree = Json.parse(Json.stringify(value));
        return switch (normalized) {
            case "json" -> Json.stringify(tree);
            case "text", "txt" -> text(tree, 0);
            case "yaml", "yml" -> yaml(tree, 0);
            case "csv" -> csv(tree);
            case "xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xml("result", tree, 0);
            case "html" -> html(tree);
            case "markdown", "md" -> "```json\n" + prettyJson(tree, 0) + "\n```\n";
            case "asciidoc", "adoc" -> asciidoc(tree, 0);
            case "sarif" -> sarif(tree);
            case "junit", "junit-xml" -> junit(tree);
            default -> throw new IllegalArgumentException("Formato desconhecido: " + format +
                ". Use json, text, yaml, csv, xml, html, markdown, asciidoc, sarif ou junit.");
        };
    }

    private static String text(Object value, int depth) {
        String indent = "  ".repeat(depth);
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder();
            map.forEach((key, item) -> {
                out.append(indent).append(key).append(":");
                if (scalar(item)) out.append(" ").append(display(item)).append("\n");
                else out.append("\n").append(text(item, depth + 1));
            });
            return out.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            for (Object item : list) {
                out.append(indent).append("- ");
                if (scalar(item)) out.append(display(item)).append("\n");
                else out.append("\n").append(text(item, depth + 1));
            }
            return out.toString();
        }
        return indent + display(value) + "\n";
    }

    private static String yaml(Object value, int depth) {
        String indent = "  ".repeat(depth);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return indent + "{}\n";
            StringBuilder out = new StringBuilder();
            map.forEach((key, item) -> {
                out.append(indent).append(yamlScalar(String.valueOf(key))).append(":");
                if (scalar(item)) out.append(" ").append(yamlScalar(display(item))).append("\n");
                else if (item instanceof List<?> list && list.isEmpty()) out.append(" []\n");
                else if (item instanceof Map<?, ?> nested && nested.isEmpty()) out.append(" {}\n");
                else out.append("\n").append(yaml(item, depth + 1));
            });
            return out.toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return indent + "[]\n";
            StringBuilder out = new StringBuilder();
            for (Object item : list) {
                if (scalar(item)) out.append(indent).append("- ").append(yamlScalar(display(item))).append("\n");
                else {
                    String rendered = yaml(item, depth + 1);
                    out.append(indent).append("- ").append(rendered.stripLeading());
                }
            }
            return out.toString();
        }
        return indent + yamlScalar(display(value)) + "\n";
    }

    private static String yamlScalar(String value) {
        if (value.isEmpty()) return "\"\"";
        boolean needsQuote = value.matches("(?i)true|false|null|~|-?\\d+(\\.\\d+)?")
            || value.startsWith(" ") || value.endsWith(" ")
            || value.chars().anyMatch(c -> c == ':' || c == '#' || c == '\n' || c == '"' || c == '\'')
            || value.startsWith("-") || value.startsWith("[") || value.startsWith("{") || value.startsWith("&") || value.startsWith("*");
        if (!needsQuote) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String asciidoc(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder();
            map.forEach((key, item) -> {
                if (scalar(item)) out.append("* *").append(key).append(":* ").append(display(item)).append("\n");
                else {
                    out.append("* *").append(key).append(":*\n");
                    out.append(indentBlock(asciidoc(item, depth + 1)));
                }
            });
            return out.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            for (Object item : list) {
                if (scalar(item)) out.append("* ").append(display(item)).append("\n");
                else out.append(indentBlock(asciidoc(item, depth + 1)));
            }
            return out.toString();
        }
        return "* " + display(value) + "\n";
    }

    private static String indentBlock(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (!line.isEmpty()) out.append("  ").append(line).append("\n");
        }
        return out.toString();
    }

    private static String csv(Object value) {
        List<Map<String, Object>> rows = rows(value);
        if (rows.isEmpty()) return "value\n" + csvCell(display(value)) + "\n";
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        rows.forEach(row -> columns.addAll(row.keySet()));
        StringBuilder out = new StringBuilder(String.join(",", columns.stream().map(OutputFormatter::csvCell).toList())).append('\n');
        rows.forEach(row -> out.append(String.join(",", columns.stream()
            .map(c -> csvCell(display(row.get(c)))).toList())).append('\n'));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        if (value instanceof List<?> list && list.stream().allMatch(Map.class::isInstance))
            return list.stream().map(v -> (Map<String, Object>) v).toList();
        if (value instanceof Map<?, ?> map) return List.of((Map<String, Object>) map);
        return List.of();
    }

    private static String xml(String name, Object value, int depth) {
        String indent = "  ".repeat(depth);
        String safeName = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder(indent).append("<").append(safeName).append(">\n");
            map.forEach((key, item) -> out.append(xml(String.valueOf(key), item, depth + 1)));
            return out.append(indent).append("</").append(safeName).append(">\n").toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder(indent).append("<").append(safeName).append(">\n");
            list.forEach(item -> out.append(xml("item", item, depth + 1)));
            return out.append(indent).append("</").append(safeName).append(">\n").toString();
        }
        return indent + "<" + safeName + ">" + escapeXml(display(value)) + "</" + safeName + ">\n";
    }

    private static String html(Object value) {
        return """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>SwissKnife Javanist</title><style>
            :root{color-scheme:light dark}body{font:15px system-ui;margin:2rem;max-width:1100px}
            pre{padding:1rem;border:1px solid #8886;border-radius:8px;overflow:auto}
            </style></head><body><h1>Relatório SwissKnife Javanist</h1><pre>%s</pre></body></html>
            """.formatted(escapeXml(prettyJson(value, 0)));
    }

    private static String sarif(Object value) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", Map.of("name", "SwissKnife Javanist", "version", Version.current())));
        run.put("results", findings(value));
        return Json.stringify(Map.of("version", "2.1.0",
            "$schema", "https://json.schemastore.org/sarif-2.1.0.json", "runs", List.of(run)));
    }

    private static List<Map<String, Object>> findings(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        collectFindings(value, result);
        if (result.isEmpty()) result.add(Map.of("ruleId", "SWISSKNIFE-RESULT", "level", "note",
            "message", Map.of("text", compact(value))));
        return result;
    }

    private static void collectFindings(Object value, List<Map<String, Object>> out) {
        if (value instanceof Map<?, ?> map) {
            Object message = first(map, "message", "description", "warning", "error");
            Object kind = map.containsKey("kind") ? map.get("kind") : "SWISSKNIFE";
            if (message != null) out.add(Map.of("ruleId", String.valueOf(kind),
                "level", severity(map), "message", Map.of("text", String.valueOf(message))));
            map.values().forEach(v -> collectFindings(v, out));
        } else if (value instanceof List<?> list) list.forEach(v -> collectFindings(v, out));
    }

    private static String junit(Object value) {
        List<Map<String, Object>> findings = findings(value);
        StringBuilder cases = new StringBuilder();
        int failures = 0;
        for (int i = 0; i < findings.size(); i++) {
            Map<String, Object> finding = findings.get(i);
            boolean failed = Set.of("error", "warning").contains(finding.get("level"));
            if (failed) failures++;
            cases.append("  <testcase classname=\"SwissKnife\" name=\"finding-").append(i + 1).append("\">");
            if (failed) cases.append("<failure message=\"").append(escapeXml(compact(finding))).append("\"/>");
            cases.append("</testcase>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"SwissKnife\" tests=\"" +
            findings.size() + "\" failures=\"" + failures + "\">\n" + cases + "</testsuite>\n";
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }
    private static String severity(Map<?, ?> map) {
        Object raw = map.containsKey("severity") ? map.get("severity")
            : map.containsKey("level") ? map.get("level") : "note";
        String severity = String.valueOf(raw).toLowerCase(Locale.ROOT);
        return switch (severity) {
            case "critical", "high", "error" -> "error";
            case "medium", "warning", "warn" -> "warning";
            default -> "note";
        };
    }
    private static String prettyJson(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            String indent = "  ".repeat(depth + 1);
            return "{\n" + indent + String.join(",\n" + indent, map.entrySet().stream()
                .map(e -> Json.stringify(String.valueOf(e.getKey())) + ": " + prettyJson(e.getValue(), depth + 1)).toList())
                + "\n" + "  ".repeat(depth) + "}";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            String indent = "  ".repeat(depth + 1);
            return "[\n" + indent + String.join(",\n" + indent, list.stream()
                .map(v -> prettyJson(v, depth + 1)).toList()) + "\n" + "  ".repeat(depth) + "]";
        }
        return Json.stringify(value);
    }
    private static boolean scalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }
    private static String compact(Object value) {
        String text = display(value);
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }
    private static String display(Object value) {
        if (value == null) return "";
        return scalar(value) ? String.valueOf(value) : Json.stringify(value);
    }
    private static String csvCell(String value) {
        return "\"" + neutralizeFormula(value).replace("\"", "\"\"") + "\"";
    }

    /**
     * Neutraliza injeção de fórmula em CSV NESTE sink de relatório: Excel/Sheets executa uma célula
     * que começa por {@code = + - @ TAB CR}. Prefixa com apóstrofo, que a planilha trata como "texto".
     *
     * Só aqui, de propósito: em {@code Csv.escape} (usado por migrate/anonymize) o mesmo prefixo
     * corromperia números negativos legítimos num round-trip de dados. Relatório é para leitura humana,
     * onde o apóstrofo é aceitável; dados são para reimportação, onde não é.
     */
    private static String neutralizeFormula(String value) {
        if (value.isEmpty()) return value;
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r')
            return "'" + value;
        return value;
    }
    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
