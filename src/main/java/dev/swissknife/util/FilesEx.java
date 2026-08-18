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

    /** Igual a walk(), mas restrito a arquivos alterados/adicionados segundo o Git (modo incremental para CI). */
    public static List<Path> walkChanged(Path root, Predicate<Path> filter) throws IOException {
        Set<Path> changed = changedFiles(root);
        return walk(root, filter.and(changed::contains));
    }

    /** Arquivos modificados, adicionados ou não rastreados em relação a HEAD, via `git`. Vazio se não for repositório Git. */
    public static Set<Path> changedFiles(Path root) {
        Set<Path> result = new HashSet<>();
        collectGitPaths(root, result, "diff", "--name-only", "HEAD");
        collectGitPaths(root, result, "ls-files", "--others", "--exclude-standard");
        return result;
    }

    private static void collectGitPaths(Path root, Set<Path> out, String... gitArgs) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(List.of(gitArgs));
            process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                if (process.exitValue() == 0)
                    output.lines().filter(line -> !line.isBlank())
                        .forEach(line -> out.add(root.resolve(line).toAbsolutePath().normalize()));
            } else {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {  }
        finally { if (process != null) process.destroyForcibly(); }
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
                catch (Exception ignored) {  }
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
