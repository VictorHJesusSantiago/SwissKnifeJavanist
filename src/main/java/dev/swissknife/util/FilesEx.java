package dev.swissknife.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

public final class FilesEx {
    private FilesEx() {}

    public static List<Path> walk(Path root, Predicate<Path> filter) throws IOException {
        if (!Files.exists(root)) throw new IllegalArgumentException("Caminho inexistente: " + root);
        List<PathMatcher> ignores = ignoreMatchers(root);
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                .filter(path -> !ignored(root, path, ignores))
                .filter(filter).sorted().toList();
        }
    }

    private static List<PathMatcher> ignoreMatchers(Path root) throws IOException {
        List<PathMatcher> result = new ArrayList<>();
        for (String filename : List.of(".gitignore", ".swissknifeignore")) {
            Path file = root.resolve(filename);
            if (!Files.isRegularFile(file)) continue;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String pattern = raw.strip().replace('\\', '/');
                if (pattern.isBlank() || pattern.startsWith("#") || pattern.startsWith("!")) continue;
                if (pattern.startsWith("/")) pattern = pattern.substring(1);
                if (pattern.endsWith("/")) pattern += "**";
                if (!pattern.contains("/")) pattern = "**/" + pattern;
                try { result.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern)); }
                catch (Exception ignored) { /* padrão inválido não deve impedir a análise */ }
            }
        }
        return result;
    }
    private static boolean ignored(Path root, Path file, List<PathMatcher> matchers) {
        Path relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        for (Path part : relative) if (part.toString().equals(".git")) return true;
        return matchers.stream().anyMatch(matcher -> matcher.matches(relative) ||
            matcher.matches(Path.of(relative.toString().replace('\\', '/'))));
    }

    public static void write(Path target, String content) throws IOException {
        var parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    public static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("(^-|-$)", "");
    }
}
