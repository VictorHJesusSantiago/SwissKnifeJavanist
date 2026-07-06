package dev.swissknife.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Configuração hierárquica da CLI. A precedência é: global, projeto, arquivo
 * explícito, perfil, ambiente e argumentos.
 */
public final class CliConfig {
    private final Map<String, String> values;
    private final List<Path> sources;

    private CliConfig(Map<String, String> values, List<Path> sources) {
        this.values = Map.copyOf(values);
        this.sources = List.copyOf(sources);
    }

    public static CliConfig load(Path workingDirectory, Path explicit, String profile,
                                 Map<String, String> overrides) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        List<Path> sources = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home", ".")).resolve(".swissknife.yml");
        readIfPresent(home, merged, sources);
        Path project = locateProjectConfig(workingDirectory);
        if (project != null && !project.equals(home)) readIfPresent(project, merged, sources);
        if (explicit != null) {
            if (!Files.isRegularFile(explicit))
                throw new IllegalArgumentException("Arquivo de configuração inexistente: " + explicit);
            readIfPresent(explicit, merged, sources);
        }
        String selectedProfile = firstNonBlank(profile, System.getenv("SWISSKNIFE_PROFILE"),
            merged.get("profile"), "development");
        applyProfile(merged, selectedProfile);
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("SWISSKNIFE_") && !key.equals("SWISSKNIFE_API_TOKEN")) {
                String normalized = key.substring("SWISSKNIFE_".length()).toLowerCase(Locale.ROOT)
                    .replace("__", ".").replace('_', '.');
                merged.put(normalized, value);
            }
        });
        merged.putAll(overrides);
        merged.put("profile", selectedProfile);
        return new CliConfig(merged, sources);
    }

    public static Path initialize(Path target, boolean force) throws IOException {
        if (Files.exists(target) && !force)
            throw new IllegalArgumentException("Configuração já existe: " + target + " (use --force)");
        String template = """
            # SwissKnife Javanist
            profile: development
            output:
              format: json
              color: auto
            policy:
              failOn: error
              maxDebtScore: 0
              minDocumentationCoverage: 0
            scan:
              respectGitignore: true
              ignoreFile: .swissknifeignore
            cache:
              enabled: true
              directory: .swissknife/cache
            telemetry:
              enabled: false
            aliases:
              doc: docs
              architecture: deps
            profiles:
              development:
                output:
                  format: text
              homologation:
                policy:
                  failOn: warning
              production:
                policy:
                  failOn: warning
            """;
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(target, template, StandardCharsets.UTF_8);
        return target;
    }

    public String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public boolean bool(String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? fallback : Set.of("true", "1", "yes", "sim", "on").contains(value.toLowerCase(Locale.ROOT));
    }
    public int integer(String key, int fallback) {
        try { return Integer.parseInt(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }
    public Map<String, String> values() { return values; }
    public List<Path> sources() { return sources; }
    public String alias(String command) { return values.getOrDefault("aliases." + command, command); }

    private static void applyProfile(Map<String, String> values, String profile) {
        String prefix = "profiles." + profile + ".";
        new ArrayList<>(values.entrySet()).forEach(entry -> {
            if (entry.getKey().startsWith(prefix))
                values.put(entry.getKey().substring(prefix.length()), entry.getValue());
        });
    }

    private static Path locateProjectConfig(Path start) {
        Path cursor = start.toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolve(".swissknife.yml");
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return null;
    }

    private static void readIfPresent(Path file, Map<String, String> output, List<Path> sources)
        throws IOException {
        if (!Files.isRegularFile(file)) return;
        output.putAll(parseYaml(file));
        sources.add(file.toAbsolutePath().normalize());
    }

    /**
     * Leitor YAML deliberadamente seguro para configurações escalares e mapas.
     * Listas podem ser informadas na forma "[a, b]" ou separadas por vírgula.
     */
    public static Map<String, String> parseYaml(Path file) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> hierarchy = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            int indentation = raw.length() - raw.stripLeading().length();
            int level = indentation / 2;
            String line = stripComment(raw.strip());
            int colon = line.indexOf(':');
            if (colon < 1) continue;
            String key = unquote(line.substring(0, colon).trim());
            String value = line.substring(colon + 1).trim();
            while (hierarchy.size() > level) hierarchy.removeLast();
            if (value.isEmpty()) {
                if (hierarchy.size() == level) hierarchy.add(key);
                else hierarchy.set(level, key);
            } else {
                List<String> full = new ArrayList<>(hierarchy);
                full.add(key);
                result.put(String.join(".", full), unquote(value));
            }
        }
        return result;
    }

    private static String stripComment(String value) {
        boolean single = false, dbl = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl) return value.substring(0, i).stripTrailing();
        }
        return value;
    }
    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))))
            return value.substring(1, value.length() - 1);
        return value;
    }
    private static String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(Objects::nonNull).filter(v -> !v.isBlank()).findFirst().orElse("");
    }
}
