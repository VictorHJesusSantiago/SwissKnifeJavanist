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
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(filter).sorted().toList();
        }
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
