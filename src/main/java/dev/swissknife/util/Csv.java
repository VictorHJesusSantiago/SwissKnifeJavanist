package dev.swissknife.util;

import java.util.*;

public final class Csv {
    private Csv() {}

    public static List<String> parseLine(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
        List<String> cells = new ArrayList<>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                cells.add(current.toString()); current.setLength(0);
            } else current.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Linha CSV com aspas não terminadas");
        cells.add(current.toString());
        return cells;
    }

    public static String line(List<String> cells) {
        return cells.stream().map(Csv::escape).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}
