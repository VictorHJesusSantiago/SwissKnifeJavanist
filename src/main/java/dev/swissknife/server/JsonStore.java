package dev.swissknife.server;

import dev.swissknife.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.*;

public final class JsonStore {
    private final Path file;
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonStore(Path file) throws IOException {
        this.file = file;
        load();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                var object = Json.object(line);
                records.put(String.valueOf(object.get("id")), object);
            }
        }
    }

    public List<Map<String, Object>> all() {
        lock.readLock().lock();
        try { return records.values().stream().<Map<String, Object>>map(LinkedHashMap::new).toList(); }
        finally { lock.readLock().unlock(); }
    }

    public Optional<Map<String, Object>> find(String id) {
        lock.readLock().lock();
        try { return Optional.ofNullable(records.get(id)).map(LinkedHashMap::new); }
        finally { lock.readLock().unlock(); }
    }

    public Map<String, Object> save(Map<String, Object> record) throws IOException {
        lock.writeLock().lock();
        try {
            var copy = new LinkedHashMap<>(record);
            String id = String.valueOf(copy.computeIfAbsent("id", ignored -> UUID.randomUUID().toString()));
            records.put(id, copy);
            persist();
            return new LinkedHashMap<>(copy);
        } finally { lock.writeLock().unlock(); }
    }

    public boolean delete(String id) throws IOException {
        lock.writeLock().lock();
        try {
            if (records.remove(id) == null) return false;
            persist();
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    private void persist() throws IOException {
        var parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, records.values().stream().map(Json::stringify).toList(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
