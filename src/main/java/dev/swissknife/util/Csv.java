package dev.swissknife.util;

import java.util.*;

public final class Csv {
    private Csv() {}

    public static List<String> parseLine(String line) { return parseLine(line, ','); }

    public static List<String> parseLine(String line, char delimiter) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
        List<String> cells = new ArrayList<>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == delimiter && !quoted) {
                cells.add(current.toString()); current.setLength(0);
            } else current.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Linha CSV com aspas não terminadas");
        cells.add(current.toString());
        return cells;
    }

    public static String line(List<String> cells) { return line(cells, ','); }

    public static String line(List<String> cells, char delimiter) {
        return cells.stream().map(v -> escape(v, delimiter)).reduce((a, b) -> a + delimiter + b).orElse("");
    }

    /**
     * Divide o conteúdo bruto de um arquivo em registros lógicos, respeitando campos entre
     * aspas que contenham quebras de linha (CSV/TSV multilinha).
     */
    public static List<String> splitRecords(String content) {
        List<String> records = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '"') { quoted = !quoted; current.append(c); }
            else if (c == '\n' && !quoted) { records.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        if (!current.isEmpty()) records.add(current.toString());
        return records;
    }

    private static String escape(String value, char delimiter) {
        if (value.indexOf(delimiter) >= 0 || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}
