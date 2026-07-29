package dev.swissknife.anonymize;

import dev.swissknife.util.Csv;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import dev.swissknife.util.Json;

public final class DataAnonymizer {
    private Properties activePolicy = new Properties();
    private final Map<String, String> consistent = new HashMap<>();

    public Report anonymize(Path input, Path policyFile, Path output) throws IOException {
        var policy = loadPolicy(policyFile);
        activePolicy = policy;
        validatePolicy(policy);
        char delimiter = delimiter(policy);
        var lines = Csv.splitRecords(Files.readString(input, StandardCharsets.UTF_8));
        if (lines.isEmpty()) throw new IllegalArgumentException("CSV vazio");
        var headers = Csv.parseLine(lines.getFirst(), delimiter);
        List<List<String>> rows = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            if (lines.get(row).isBlank()) continue;
            var cells = Csv.parseLine(lines.get(row), delimiter);
            for (int col = 0; col < Math.min(headers.size(), cells.size()); col++) {
                var strategy = policy.getProperty(headers.get(col), "keep");
                cells.set(col, transform(strategy, cells.get(col), row));
            }
            rows.add(cells);
        }
        applyShuffle(headers, rows, policy);
        var out = new ArrayList<String>();
        out.add(Csv.line(headers, delimiter));
        rows.forEach(cells -> out.add(Csv.line(cells, delimiter)));
        var parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(output, out, StandardCharsets.UTF_8);
        return new Report(rows.size(), headers.size(), output, detectHeaders(headers), policyVersion(policy),
            uniquenessWarnings(headers, rows, policy));
    }

    /** Gera uma prévia (amostra) das transformações sem gravar o arquivo de saída. */
    public List<List<String>> preview(Path input, Path policyFile, int sampleRows) throws IOException {
        var policy = loadPolicy(policyFile);
        activePolicy = policy;
        validatePolicy(policy);
        char delimiter = delimiter(policy);
        var lines = Csv.splitRecords(Files.readString(input, StandardCharsets.UTF_8));
        if (lines.isEmpty()) return List.of();
        var headers = Csv.parseLine(lines.getFirst(), delimiter);
        List<List<String>> result = new ArrayList<>();
        result.add(headers);
        for (int row = 1; row < lines.size() && result.size() <= sampleRows; row++) {
            if (lines.get(row).isBlank()) continue;
            var cells = Csv.parseLine(lines.get(row), delimiter);
            for (int col = 0; col < Math.min(headers.size(), cells.size()); col++) {
                var strategy = policy.getProperty(headers.get(col), "keep");
                cells.set(col, transform(strategy, cells.get(col), row));
            }
            result.add(cells);
        }
        return result;
    }

    private Properties loadPolicy(Path policyFile) throws IOException {
        var policy = new Properties();
        try (var reader = Files.newBufferedReader(policyFile)) { policy.load(reader); }
        return policy;
    }

    private char delimiter(Properties policy) {
        String configured = policy.getProperty("_delimiter", policy.getProperty("_format", ",").equalsIgnoreCase("tsv") ? "\t" : ",");
        if (configured.equalsIgnoreCase("tab") || configured.equals("\\t")) return '\t';
        return configured.isEmpty() ? ',' : configured.charAt(0);
    }

    /**
     * A permutação usa SecureRandom: em "shuffle" a única coisa que impede religar valor a titular é
     * a imprevisibilidade da permutação. O gerador padrão da biblioteca é um LCG de 48 bits semeado
     * pelo relógio — dado o arquivo de saída, a permutação é reconstruível e a anonimização vira
     * reversível. (É também a regra INSECURE_RANDOM do SecurityScanner deste próprio repositório.)
     */
    private void applyShuffle(List<String> headers, List<List<String>> rows, Properties policy) {
        SecureRandom random = new SecureRandom();
        for (int col = 0; col < headers.size(); col++) {
            if (!"shuffle".equalsIgnoreCase(policy.getProperty(headers.get(col), ""))) continue;
            List<String> values = new ArrayList<>();
            for (List<String> row : rows) if (col < row.size()) values.add(row.get(col));
            Collections.shuffle(values, random);
            int index = 0;
            for (List<String> row : rows) if (col < row.size()) row.set(col, values.get(index++));
        }
    }

    private List<String> uniquenessWarnings(List<String> headers, List<List<String>> rows, Properties policy) {
        List<String> warnings = new ArrayList<>();
        Set<String> uniquenessStrategies = Set.of("hash", "uuid", "email-consistent", "hmac");
        for (int col = 0; col < headers.size(); col++) {
            String strategy = policy.getProperty(headers.get(col), "keep").toLowerCase(Locale.ROOT);
            if (!uniquenessStrategies.contains(strategy)) continue;
            Set<String> values = new HashSet<>();
            int total = 0;
            for (List<String> row : rows) if (col < row.size() && !row.get(col).isBlank()) { values.add(row.get(col)); total++; }
            if (total > 0 && values.size() < total)
                warnings.add("Coluna '" + headers.get(col) + "': " + (total - values.size()) +
                    " colisão(ões) possível(is) após anonimização com estratégia " + strategy);
        }
        return warnings;
    }

    public Report anonymizeJson(Path input, Path policyFile, Path output) throws IOException {
        var policy = new Properties();
        try (var reader = Files.newBufferedReader(policyFile)) { policy.load(reader); }
        activePolicy = policy;
        validatePolicy(policy);
        Object parsed = Json.parse(Files.readString(input));
        Counter counter = new Counter();
        Object transformed = transformJson(parsed, "", counter);
        var parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, Json.stringify(transformed), StandardCharsets.UTF_8);
        return new Report(counter.rows, counter.fields, output, counter.detected, policyVersion(policy), List.of());
    }

    public DetectionReport detect(Path input) throws IOException {
        // Csv.splitRecords (e não readAllLines) pelo mesmo motivo de anonymize(): um campo entre
        // aspas com quebra de linha conta como UM registro. Com readAllLines as colunas saíam
        // deslocadas a partir da primeira célula multilinha e a detecção de PII apontava o campo errado.
        List<String> lines = Csv.splitRecords(Files.readString(input, StandardCharsets.UTF_8));
        if (lines.isEmpty()) return new DetectionReport(List.of(), 0);
        List<String> headers = Csv.parseLine(lines.getFirst());
        List<PiiColumn> detected = detectHeaders(headers);
        for (int column = 0; column < headers.size(); column++) {
            String header = headers.get(column);
            List<String> sample = new ArrayList<>();
            for (int row = 1; row < Math.min(lines.size(), 101); row++) {
                var values = Csv.parseLine(lines.get(row));
                if (column < values.size()) sample.add(values.get(column));
            }
            String kind = inferValues(sample);
            if (!kind.equals("UNKNOWN") && detected.stream().noneMatch(p -> p.column().equals(header)))
                detected.add(new PiiColumn(header, kind, "MEDIUM"));
        }
        return new DetectionReport(detected, Math.max(0, lines.size() - 1));
    }

    String transform(String strategy, String value, int row) {
        if (value.isBlank()) return value;
        String lower = strategy.toLowerCase(Locale.ROOT);
        if (lower.startsWith("date-shift:")) return shiftDate(value, integerArg(strategy));
        if (lower.startsWith("number-noise:")) return noise(value, integerArg(strategy));
        if (lower.startsWith("keep-last:")) return keepLast(value, integerArg(strategy));
        if (lower.startsWith("keep-first:")) return keepFirst(value, integerArg(strategy));
        if (lower.startsWith("constant:")) return strategy.substring(strategy.indexOf(':') + 1);
        if (lower.startsWith("regex:")) return regex(value, strategy.substring(strategy.indexOf(':') + 1));
        if (lower.startsWith("age-bucket:")) return ageBucket(value, integerArg(strategy));
        if (lower.equals("shuffle")) return value;
        return switch (lower) {
            case "keep" -> value;
            case "null" -> "";
            case "mask" -> value.length() <= 2 ? "**" : value.charAt(0) + "*".repeat(value.length() - 2) + value.charAt(value.length() - 1);
            case "hash" -> hash(salt() + value).substring(0, 16);
            case "hmac" -> hmac(value).substring(0, 16);
            case "email-consistent" -> consistent("email", value,
                () -> "usuario-" + hash(salt() + value).substring(0, 10) + "@exemplo.test");
            case "email-domain" -> "anon-" + hash(salt() + value).substring(0, 8) +
                (value.contains("@") ? value.substring(value.indexOf('@')) : "@exemplo.test");
            case "email" -> "usuario" + row + "@exemplo.test";
            case "name" -> "Pessoa " + row;
            case "phone" -> "+5500000" + String.format("%04d", row % 10_000);
            case "cpf" -> cpf(hash(salt() + value));
            case "cnpj" -> cnpj(hash(salt() + value));
            // CEP derivado de SHA-256 com sal, não de String.hashCode(): o espaço de CEPs tem apenas
            // 10^8 valores, então um hashCode não salgado é invertível por força bruta em segundos.
            case "cep" -> cep(hash(salt() + value));
            case "uuid" -> UUID.nameUUIDFromBytes((salt() + value).getBytes(StandardCharsets.UTF_8)).toString();
            default -> throw new IllegalArgumentException("Estratégia desconhecida: " + strategy);
        };
    }

    private Object transformJson(Object value, String path, Counter counter) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String child = path.isEmpty() ? name : path + "." + name;
                String strategy = activePolicy.getProperty(child, activePolicy.getProperty(name, "keep"));
                if (item instanceof String text && !strategy.equalsIgnoreCase("keep")) {
                    result.put(name, transform(strategy, text, ++counter.rows)); counter.fields++;
                } else result.put(name, transformJson(item, child, counter));
                String pii = inferName(name);
                if (!pii.equals("UNKNOWN")) counter.detected.add(new PiiColumn(child, pii, "HIGH"));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> transformJson(v, path, counter)).toList();
        return value;
    }

    private void validatePolicy(Properties policy) {
        policy.forEach((key, value) -> {
            String strategy = String.valueOf(value).toLowerCase(Locale.ROOT);
            if (String.valueOf(key).startsWith("_")) return;
            boolean valid = Set.of("keep","null","mask","hash","hmac","email","email-consistent","email-domain",
                "name","phone","cpf","cnpj","cep","uuid","shuffle").contains(strategy)
                || strategy.matches("(date-shift|number-noise|keep-last|keep-first|age-bucket):.+")
                || strategy.startsWith("constant:") || strategy.startsWith("regex:");
            if (!valid) throw new IllegalArgumentException("Estratégia desconhecida em " + key + ": " + value);
        });
    }
    private List<PiiColumn> detectHeaders(List<String> headers) {
        List<PiiColumn> result = new ArrayList<>();
        headers.forEach(header -> {
            String kind = inferName(header);
            if (!kind.equals("UNKNOWN")) result.add(new PiiColumn(header, kind, "HIGH"));
        });
        return result;
    }
    private String inferName(String header) {
        String name = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (name.matches(".*(email|mail).*")) return "EMAIL";
        if (name.matches(".*(cpf|cnpj|document|taxid).*")) return "DOCUMENT";
        if (name.matches(".*(phone|telefone|celular|mobile).*")) return "PHONE";
        if (name.matches(".*(name|nome|fullname).*")) return "NAME";
        if (name.matches(".*(address|endereco|cep|zipcode).*")) return "ADDRESS";
        if (name.matches(".*(birth|nascimento|birthday).*")) return "BIRTH_DATE";
        if (name.matches(".*(card|cartao).*")) return "PAYMENT_CARD";
        return "UNKNOWN";
    }
    private String inferValues(List<String> sample) {
        if (sample.stream().filter(v -> v.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")).count() > sample.size() / 2) return "EMAIL";
        if (sample.stream().filter(v -> v.replaceAll("\\D", "").matches("\\d{11}|\\d{14}")).count() > sample.size() / 2) return "DOCUMENT";
        if (sample.stream().filter(v -> v.replaceAll("\\D", "").matches("\\d{10,13}")).count() > sample.size() / 2) return "PHONE";
        return "UNKNOWN";
    }
    private String salt() { return activePolicy.getProperty("_salt", "swissknife"); }
    private String policyVersion(Properties p) { return p.getProperty("_version", "1"); }
    private int integerArg(String strategy) {
        try { return Integer.parseInt(strategy.substring(strategy.indexOf(':') + 1)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Argumento inválido: " + strategy); }
    }
    private String shiftDate(String value, int days) {
        try { return LocalDate.parse(value).plusDays(days).format(DateTimeFormatter.ISO_DATE); }
        catch (Exception e) { throw new IllegalArgumentException("Data ISO inválida para date-shift: " + value); }
    }
    private String noise(String value, int percent) {
        try {
            double number = Double.parseDouble(value.replace(',', '.'));
            double factor = ((Math.floorMod((salt() + value).hashCode(), 2 * percent + 1) - percent) / 100.0);
            return String.format(Locale.ROOT, "%.2f", number * (1 + factor));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Número inválido: " + value); }
    }
    private String keepLast(String value, int count) {
        int preserved = Math.min(count, value.length());
        return "*".repeat(value.length() - preserved) + value.substring(value.length() - preserved);
    }
    private String keepFirst(String value, int count) {
        int preserved = Math.min(count, value.length());
        return value.substring(0, preserved) + "*".repeat(value.length() - preserved);
    }
    private String regex(String value, String expression) {
        int separator = expression.indexOf("=>");
        if (separator < 0) throw new IllegalArgumentException("regex exige padrão=>substituição");
        return value.replaceAll(expression.substring(0, separator), expression.substring(separator + 2));
    }
    private String consistent(String kind, String value, java.util.function.Supplier<String> supplier) {
        return consistent.computeIfAbsent(kind + "\0" + value, ignored -> supplier.get());
    }
    private String cpf(String hash) {
        int[] digits = new int[11];
        for (int i = 0; i < 9; i++) digits[i] = Character.digit(hash.charAt(i), 16) % 10;
        digits[9] = checkDigit(digits, 9, 10); digits[10] = checkDigit(digits, 10, 11);
        return "%d%d%d.%d%d%d.%d%d%d-%d%d".formatted(Arrays.stream(digits).boxed().toArray());
    }
    private String cnpj(String hash) {
        String digits = hash.chars().limit(14).map(c -> Character.digit(c, 16) % 10)
            .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString();
        return digits.substring(0,2)+"."+digits.substring(2,5)+"."+digits.substring(5,8)+"/"+
            digits.substring(8,12)+"-"+digits.substring(12,14);
    }
    private String cep(String hash) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 8; i++) digits.append(Character.digit(hash.charAt(i), 16) % 10);
        return digits.substring(0, 5) + "-" + digits.substring(5, 8);
    }
    private int checkDigit(int[] digits, int length, int weight) {
        int total = 0; for (int i = 0; i < length; i++) total += digits[i] * (weight - i);
        int digit = 11 - total % 11; return digit > 9 ? 0 : digit;
    }
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String hmac(String value) {
        String keyEnvVar = activePolicy.getProperty("_hmacKeyEnv", "SWISSKNIFE_HMAC_KEY");
        String key = System.getenv(keyEnvVar);
        if (key == null || key.isBlank())
            throw new IllegalArgumentException("Variável de ambiente ausente para HMAC: " + keyEnvVar);
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private String ageBucket(String value, int bucketSize) {
        try {
            int age = Integer.parseInt(value.trim());
            int bucketStart = (age / bucketSize) * bucketSize;
            return bucketStart + "-" + (bucketStart + bucketSize - 1);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Idade inválida para age-bucket: " + value); }
    }
    private static final class Counter {
        int rows, fields;
        final List<PiiColumn> detected = new ArrayList<>();
    }
    public record PiiColumn(String column, String kind, String confidence) {}
    public record DetectionReport(List<PiiColumn> columns, int sampledRows) {}
    public record Report(int rows, int columns, Path output, List<PiiColumn> detectedPii,
                         String policyVersion, List<String> uniquenessWarnings) {}
}
