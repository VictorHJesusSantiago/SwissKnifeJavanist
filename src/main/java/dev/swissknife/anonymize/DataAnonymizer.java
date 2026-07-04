package dev.swissknife.anonymize;

import dev.swissknife.util.Csv;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class DataAnonymizer {
    public Report anonymize(Path input, Path policyFile, Path output) throws IOException {
        var policy = new Properties();
        try (var reader = Files.newBufferedReader(policyFile)) { policy.load(reader); }
        var lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalArgumentException("CSV vazio");
        var headers = Csv.parseLine(lines.getFirst());
        var out = new ArrayList<String>();
        out.add(Csv.line(headers));
        for (int row = 1; row < lines.size(); row++) {
            var cells = Csv.parseLine(lines.get(row));
            for (int col = 0; col < Math.min(headers.size(), cells.size()); col++) {
                var strategy = policy.getProperty(headers.get(col), "keep");
                cells.set(col, transform(strategy, cells.get(col), row));
            }
            out.add(Csv.line(cells));
        }
        var parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(output, out, StandardCharsets.UTF_8);
        return new Report(lines.size() - 1, headers.size(), output);
    }

    String transform(String strategy, String value, int row) {
        if (value.isBlank()) return value;
        return switch (strategy.toLowerCase(Locale.ROOT)) {
            case "keep" -> value;
            case "null" -> "";
            case "mask" -> value.length() <= 2 ? "**" : value.charAt(0) + "*".repeat(value.length() - 2) + value.charAt(value.length() - 1);
            case "hash" -> hash(value).substring(0, 16);
            case "email" -> "usuario" + row + "@exemplo.test";
            case "name" -> "Pessoa " + row;
            case "phone" -> "+5500000" + String.format("%04d", row % 10_000);
            default -> throw new IllegalArgumentException("Estratégia desconhecida: " + strategy);
        };
    }
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record Report(int rows, int columns, Path output) {}
}
