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
    private final Path anchorFile;
    private String lastAuditHash = "GENESIS";

    public JsonStore(Path file) throws IOException {
        this.file = file;
        this.auditFile = file.resolveSibling(file.getFileName() + ".audit.jsonl");
        this.anchorFile = file.resolveSibling(file.getFileName() + ".audit.anchor");
        load();
        loadAuditTail();
    }

    /**
     * Marcador de exclusão no log append-only. Um DELETE grava uma lápide em vez de reescrever o
     * arquivo; o replay em load() aplica as linhas em ordem, então a lápide apaga o registro anterior.
     */
    private static final String TOMBSTONE = "__deleted";
    /** Linhas gravadas além do número de registros vivos; dispara a compactação quando cresce demais. */
    private int appendedLines;

    /**
     * Replay do log append-only: a última ocorrência de cada id vence e uma lápide o remove.
     *
     * Uma última linha malformada é tolerada e descartada — é a assinatura de um append interrompido
     * por queda de processo, e perder a gravação que não completou é o comportamento correto. Uma
     * linha malformada no MEIO do arquivo é corrupção real e falha alto, em vez de sumir com dados.
     */
    private void load() throws IOException {
        if (!Files.exists(file)) return;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            Map<String, Object> object;
            try { object = Json.object(line); }
            catch (RuntimeException malformed) {
                boolean isLastLine = lines.subList(i + 1, lines.size()).stream().allMatch(String::isBlank);
                if (isLastLine) break;
                throw new IOException("Linha " + (i + 1) + " corrompida em " + file + ": " + malformed.getMessage(), malformed);
            }
            total++;
            String id = String.valueOf(object.get("id"));
            if (Boolean.TRUE.equals(object.get(TOMBSTONE))) records.remove(id);
            else records.put(id, object);
        }
        appendedLines = total;
    }

    public List<Map<String, Object>> all() {
        lock.readLock().lock();
        try { return records.values().stream().<Map<String, Object>>map(LinkedHashMap::new).toList(); }
        finally { lock.readLock().unlock(); }
    }

    /** Tamanho da coleção sem copiar cada registro — use em vez de {@code all().size()} quando só a contagem importa. */
    public int count() {
        lock.readLock().lock();
        try { return records.size(); }
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
            append(copy);
            audit("SAVE", id, copy);
            return new LinkedHashMap<>(copy);
        } finally { lock.writeLock().unlock(); }
    }

    public boolean delete(String id) throws IOException {
        lock.writeLock().lock();
        try {
            if (records.remove(id) == null) return false;
            Map<String, Object> tombstone = new LinkedHashMap<>();
            tombstone.put("id", id);
            tombstone.put(TOMBSTONE, true);
            append(tombstone);
            audit("DELETE", id, Map.of());
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    /**
     * Fator de compactação: reescreve o arquivo quando as linhas gravadas passam do dobro dos
     * registros vivos (com um piso, para não compactar a cada gravação em bases pequenas). Mantém o
     * arquivo em O(registros) amortizado sem pagar uma reescrita completa por gravação.
     */
    private static final int COMPACTION_FLOOR = 1_000;

    /**
     * Grava UMA linha ao fim do arquivo, em vez de reescrever a base inteira.
     *
     * A versão anterior serializava todos os registros e reescrevia o arquivo a cada save(): custo
     * O(n) por gravação e O(n²) para carregar n registros, o que tornava o store inutilizável muito
     * antes de "volume de produção". O append é O(1); a compactação periódica devolve o espaço.
     */
    private void append(Map<String, Object> entry) throws IOException {
        var parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, Json.stringify(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        appendedLines++;
        if (appendedLines > Math.max(COMPACTION_FLOOR, records.size() * 2L)) rewrite();
    }

    /** Reescrita completa e atômica do arquivo com apenas os registros vivos (compactação). */
    private void rewrite() throws IOException {
        var parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, records.values().stream().map(Json::stringify).toList(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        appendedLines = records.size();
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
            // String.join, não reduce((a,b)->a+b): a concatenação em reduce cria uma String nova a
            // cada registro, tornando o checksum O(n²) em tempo e memória justo na operação usada
            // para verificar bases grandes.
            return new Verification(errors.isEmpty(), records.size(), errors, auditValid,
                checksum(String.join("\n", records.values().stream().map(Json::stringify).toList())));
        } finally { lock.readLock().unlock(); }
    }

    public Backup backup(Path destination) throws IOException {
        lock.readLock().lock();
        try {
            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            StringBuilder builder = new StringBuilder();
            records.values().forEach(value -> builder.append(Json.stringify(value)).append('\n'));
            String content = builder.toString();
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
            records.clear(); records.putAll(restored); rewrite();
            audit("RESTORE", "*", Map.of("source", source.toString(), "records", restored.size()));
            return verify();
        } finally { lock.writeLock().unlock(); }
    }

    public CompactResult compact() throws IOException {
        lock.writeLock().lock();
        try {
            long before = Files.isRegularFile(file) ? Files.size(file) : 0;
            rewrite();
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
        lastAuditHash = auditAnchor();
        if (!Files.isRegularFile(auditFile)) return;
        List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        for (int i=lines.size()-1;i>=0;i--) if (!lines.get(i).isBlank()) {
            lastAuditHash=String.valueOf(Json.object(lines.get(i)).getOrDefault("hash",lastAuditHash)); return;
        }
    }
    private static final long AUDIT_ROTATION_BYTES = Long.parseLong(
        System.getenv().getOrDefault("SWISSKNIFE_AUDIT_ROTATION_BYTES", "10485760"));

    private void audit(String action, String id, Map<String, Object> record) throws IOException {
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("timestamp",Instant.now().toString());event.put("action",action);event.put("id",id);
        event.put("recordHash",checksum(Json.stringify(record)));event.put("previousHash",lastAuditHash);
        String hash=checksum(lastAuditHash+Json.stringify(event));event.put("hash",hash);
        Path parent=auditFile.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
        rotateAuditIfNeeded();
        Files.writeString(auditFile,Json.stringify(event)+System.lineSeparator(),StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        lastAuditHash=hash;
    }
    /**
     * Rotaciona o log de auditoria quando excede o limite configurado.
     *
     * A cadeia de hashes NÃO recomeça na rotação — se recomeçasse, ou a primeira entrada do arquivo
     * novo apontaria para um previousHash inexistente (verify() acusaria "cadeia rompida" para sempre,
     * transformando a verificação de integridade em ruído permanente), ou seria preciso reiniciar a
     * cadeia e perder a ligação criptográfica com o histórico arquivado. A âncora abaixo grava o
     * último hash do trecho arquivado num arquivo irmão, e verifyAudit() parte dela.
     */
    private void rotateAuditIfNeeded() throws IOException {
        if (!Files.isRegularFile(auditFile) || Files.size(auditFile) < AUDIT_ROTATION_BYTES) return;
        Path archived = auditFile.resolveSibling(auditFile.getFileName() + "." +
            Instant.now().toString().replace(":", "-") + ".archive");
        Files.move(auditFile, archived, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(anchorFile, lastAuditHash, StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    /** Hash da última entrada arquivada; "GENESIS" enquanto nunca houve rotação. */
    private String auditAnchor() {
        try { return Files.isRegularFile(anchorFile) ? Files.readString(anchorFile, StandardCharsets.US_ASCII).strip() : "GENESIS"; }
        catch (IOException e) { return "GENESIS"; }
    }
    private boolean verifyAudit(List<String> errors) {
        if (!Files.isRegularFile(auditFile)) return true;
        try {
            String previous=auditAnchor();int line=0;
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
