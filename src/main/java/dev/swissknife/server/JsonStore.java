package dev.swissknife.server;

import dev.swissknife.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.*;

public final class JsonStore {
    private final Path file;
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path auditFile;
    private String lastAuditHash = "GENESIS";

    public JsonStore(Path file) throws IOException {
        this.file = file;
        this.auditFile = file.resolveSibling(file.getFileName() + ".audit.jsonl");
        load();
        loadAuditTail();
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
            audit("SAVE", id, copy);
            return new LinkedHashMap<>(copy);
        } finally { lock.writeLock().unlock(); }
    }

    public boolean delete(String id) throws IOException {
        lock.writeLock().lock();
        try {
            if (records.remove(id) == null) return false;
            persist();
            audit("DELETE", id, Map.of());
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

    public Verification verify() {
        lock.readLock().lock();
        try {
            List<String> errors = new ArrayList<>();
            records.forEach((id, value) -> {
                if (id == null || id.equals("null") || id.isBlank()) errors.add("Registro sem ID");
                if (!Objects.equals(id, String.valueOf(value.get("id")))) errors.add("ID divergente: " + id);
            });
            boolean auditValid = verifyAudit(errors);
            return new Verification(errors.isEmpty(), records.size(), errors, auditValid,
                checksum(records.values().stream().map(Json::stringify).reduce("", (a,b)->a+"\n"+b)));
        } finally { lock.readLock().unlock(); }
    }

    public Backup backup(Path destination) throws IOException {
        lock.readLock().lock();
        try {
            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            String content = records.values().stream().map(Json::stringify).reduce("", (a,b)->a+b+"\n");
            Files.writeString(destination, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String hash = checksum(content);
            Files.writeString(destination.resolveSibling(destination.getFileName()+".sha256"), hash,
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return new Backup(destination, records.size(), Files.size(destination), hash, Instant.now().toString());
        } finally { lock.readLock().unlock(); }
    }

    public Verification restore(Path source) throws IOException {
        lock.writeLock().lock();
        try {
            if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Backup inexistente: " + source);
            Map<String, Map<String, Object>> restored = new LinkedHashMap<>();
            int lineNumber = 0;
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) continue;
                Map<String, Object> value;
                try { value = Json.object(line); }
                catch (Exception e) { throw new IllegalArgumentException("JSON inválido na linha " + lineNumber + ": " + e.getMessage()); }
                String id = String.valueOf(value.get("id"));
                if (id.equals("null") || id.isBlank()) throw new IllegalArgumentException("ID ausente na linha " + lineNumber);
                if (restored.put(id, value) != null) throw new IllegalArgumentException("ID duplicado no backup: " + id);
            }
            records.clear(); records.putAll(restored); persist();
            audit("RESTORE", "*", Map.of("source", source.toString(), "records", restored.size()));
            return verify();
        } finally { lock.writeLock().unlock(); }
    }

    public CompactResult compact() throws IOException {
        lock.writeLock().lock();
        try {
            long before = Files.isRegularFile(file) ? Files.size(file) : 0;
            persist();
            long after = Files.isRegularFile(file) ? Files.size(file) : 0;
            audit("COMPACT", "*", Map.of("beforeBytes", before, "afterBytes", after));
            return new CompactResult(records.size(), before, after, Math.max(0, before-after));
        } finally { lock.writeLock().unlock(); }
    }

    public List<Map<String, Object>> auditTrail(int limit) throws IOException {
        if (!Files.isRegularFile(auditFile)) return List.of();
        List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        int start = Math.max(0, lines.size()-Math.max(1, Math.min(limit, 10_000)));
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : lines.subList(start, lines.size())) if (!line.isBlank()) result.add(Json.object(line));
        return result;
    }

    private void loadAuditTail() throws IOException {
        if (!Files.isRegularFile(auditFile)) return;
        List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        for (int i=lines.size()-1;i>=0;i--) if (!lines.get(i).isBlank()) {
            lastAuditHash=String.valueOf(Json.object(lines.get(i)).getOrDefault("hash","GENESIS")); return;
        }
    }
    private void audit(String action, String id, Map<String, Object> record) throws IOException {
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("timestamp",Instant.now().toString());event.put("action",action);event.put("id",id);
        event.put("recordHash",checksum(Json.stringify(record)));event.put("previousHash",lastAuditHash);
        String hash=checksum(lastAuditHash+Json.stringify(event));event.put("hash",hash);
        Path parent=auditFile.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
        Files.writeString(auditFile,Json.stringify(event)+System.lineSeparator(),StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        lastAuditHash=hash;
    }
    private boolean verifyAudit(List<String> errors) {
        if (!Files.isRegularFile(auditFile)) return true;
        try {
            String previous="GENESIS";int line=0;
            for(String raw:Files.readAllLines(auditFile,StandardCharsets.UTF_8)){
                line++;if(raw.isBlank())continue;Map<String,Object> event=Json.object(raw);
                String actual=String.valueOf(event.remove("hash"));
                if(!Objects.equals(previous,String.valueOf(event.get("previousHash")))){errors.add("Cadeia de auditoria rompida na linha "+line);return false;}
                String expected=checksum(previous+Json.stringify(event));
                if(!Objects.equals(expected,actual)){errors.add("Hash de auditoria inválido na linha "+line);return false;}
                previous=actual;
            }
            return true;
        } catch(Exception e){errors.add("Auditoria ilegível: "+e.getMessage());return false;}
    }
    private String checksum(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public record Verification(boolean valid,int records,List<String> errors,boolean auditValid,String checksum){}
    public record Backup(Path file,int records,long bytes,String checksum,String createdAt){}
    public record CompactResult(int records,long beforeBytes,long afterBytes,long reclaimedBytes){}
}
