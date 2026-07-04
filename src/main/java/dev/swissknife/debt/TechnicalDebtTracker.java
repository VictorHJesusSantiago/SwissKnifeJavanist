package dev.swissknife.debt;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class TechnicalDebtTracker {
    private static final Pattern MARKER = Pattern.compile("\\b(TODO|FIXME|HACK|XXX)\\b[: -]*(.*)", Pattern.CASE_INSENSITIVE);

    public Report scan(Path root) throws IOException {
        List<Item> items = new ArrayList<>();
        var files = FilesEx.walk(root, p -> {
            var name = p.getFileName().toString();
            return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".xml")
                || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".sql");
        });
        for (var file : files) {
            var lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int comment = commentStart(line);
                if (comment < 0) continue;
                var matcher = MARKER.matcher(line.substring(comment));
                if (matcher.find()) items.add(new Item(root.relativize(file).toString(), i + 1,
                    matcher.group(1).toUpperCase(Locale.ROOT), matcher.group(2).trim(), severity(matcher.group(1))));
            }
        }
        int score = items.stream().mapToInt(i -> i.severity().weight).sum();
        return new Report(files.size(), items, score);
    }

    private int commentStart(String line) {
        int slash = line.indexOf("//");
        int block = line.indexOf("/*");
        int hash = line.indexOf('#');
        int dash = line.indexOf("--");
        return java.util.stream.IntStream.of(slash, block, hash, dash).filter(i -> i >= 0).min().orElse(-1);
    }

    private Severity severity(String marker) {
        return switch (marker.toUpperCase(Locale.ROOT)) {
            case "FIXME", "XXX" -> Severity.HIGH;
            case "HACK" -> Severity.MEDIUM;
            default -> Severity.LOW;
        };
    }
    public enum Severity {
        LOW(1), MEDIUM(3), HIGH(5);
        final int weight;
        Severity(int weight) { this.weight = weight; }
    }
    public record Item(String file, int line, String marker, String description, Severity severity) {}
    public record Report(int filesScanned, List<Item> items, int score) {}
}
