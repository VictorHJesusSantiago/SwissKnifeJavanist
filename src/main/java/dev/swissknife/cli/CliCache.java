package dev.swissknife.cli;

import dev.swissknife.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Cache local por comando e fingerprint de entradas, com expiração. */
public final class CliCache {
    private final Path directory;
    public CliCache(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public String key(List<String> command, List<Path> inputs) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            command.forEach(value -> update(digest, value));
            for (Path input : inputs.stream().sorted().toList()) {
                update(digest, input.toAbsolutePath().normalize().toString());
                if (Files.isRegularFile(input)) {
                    update(digest, String.valueOf(Files.size(input)));
                    update(digest, String.valueOf(Files.getLastModifiedTime(input).toMillis()));
                } else if (Files.isDirectory(input)) {
                    try (var stream = Files.walk(input)) {
                        for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                            update(digest, input.relativize(file).toString());
                            update(digest, String.valueOf(Files.size(file)));
                            update(digest, String.valueOf(Files.getLastModifiedTime(file).toMillis()));
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Optional<Object> get(String key, Duration ttl) throws IOException {
        Path file = directory.resolve(key + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        if (Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(ttl))) {
            Files.deleteIfExists(file); return Optional.empty();
        }
        return Optional.ofNullable(Json.parse(Files.readString(file)));
    }
    public void put(String key, Object value) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(key + ".json");
        Path temporary = directory.resolve(key + ".tmp");
        Files.writeString(temporary, Json.stringify(value), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
    public Status status() throws IOException {
        if (!Files.isDirectory(directory)) return new Status(directory, 0, 0);
        try (var stream = Files.list(directory)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            return new Status(directory, files.size(), files.stream().mapToLong(path -> {
                try { return Files.size(path); } catch (IOException e) { return 0; }
            }).sum());
        }
    }
    public int clear() throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        int count = 0;
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().matches("[a-f0-9]{64}\\.json|[a-f0-9]{64}\\.tmp")) {
                    Files.deleteIfExists(file); count++;
                }
            }
        }
        return count;
    }
    private void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
    public record Status(Path directory, int entries, long bytes) {}
}
