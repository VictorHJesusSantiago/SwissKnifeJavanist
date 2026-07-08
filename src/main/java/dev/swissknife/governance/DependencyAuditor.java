package dev.swissknife.governance;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Inventário Maven/Gradle, conflitos, políticas de repositório e SBOM. */
public final class DependencyAuditor {
    private static final Pattern MAVEN_DEP = Pattern.compile(
        "(?s)<dependency>\\s*<groupId>(.*?)</groupId>\\s*<artifactId>(.*?)</artifactId>" +
        "(?:\\s*<version>(.*?)</version>)?");
    private static final Pattern GRADLE_DEP = Pattern.compile(
        "(?m)(?:implementation|api|compileOnly|runtimeOnly|testImplementation)\\s*\\(?[\"']" +
        "([^:\"']+):([^:\"']+):([^\"']+)[\"']");
    private static final Pattern REPOSITORY = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern LICENSE = Pattern.compile("(?s)<license>\\s*<name>(.*?)</name>");

    public Report analyze(Path root, Set<String> allowedLicenses, Set<String> allowedRepositories)
        throws IOException {
        List<Dependency> dependencies = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<Path> manifests = FilesEx.walk(root, p -> {
            String name = p.getFileName().toString();
            return name.equals("pom.xml") || name.equals("build.gradle") ||
                name.equals("build.gradle.kts") || name.equals("libs.versions.toml");
        });
        String declaredLicense = "";
        for (Path file : manifests) {
            String content = Files.readString(file);
            if (file.getFileName().toString().equals("pom.xml")) {
                Matcher license = LICENSE.matcher(content);
                declaredLicense = license.find() ? license.group(1).trim() : "";
                collect(MAVEN_DEP, content, file, dependencies, "maven", declaredLicense);
            } else collect(GRADLE_DEP, content, file, dependencies, "gradle", "");
            Matcher repositories = REPOSITORY.matcher(content);
            while (repositories.find()) {
                String url = repositories.group();
                if (!allowedRepositories.isEmpty() &&
                    allowedRepositories.stream().noneMatch(url::startsWith))
                    findings.add(new Finding("REPOSITORY_NOT_ALLOWED", "HIGH", file.toString(),
                        "Repositório não autorizado: " + url, "Use apenas repositórios aprovados."));
            }
        }
        Map<String, Set<String>> versions = new TreeMap<>();
        dependencies.forEach(d -> versions.computeIfAbsent(d.group() + ":" + d.name(),
            ignored -> new TreeSet<>()).add(d.version()));
        versions.forEach((component, found) -> {
            if (found.size() > 1) findings.add(new Finding("VERSION_CONFLICT", "MEDIUM", component,
                "Versões conflitantes: " + found, "Centralize a versão em BOM ou catálogo."));
        });
        Set<String> seen = new HashSet<>();
        dependencies.forEach(d -> {
            String coordinate = d.group() + ":" + d.name() + ":" + d.version();
            if (!seen.add(coordinate)) findings.add(new Finding("DUPLICATE_DEPENDENCY", "LOW",
                d.file(), "Dependência duplicada: " + coordinate, "Remova a declaração redundante."));
            if (d.version().isBlank() || d.version().contains("${"))
                findings.add(new Finding("UNRESOLVED_VERSION", "LOW", d.file(),
                    "Versão herdada ou não resolvida: " + coordinate,
                    "Execute com o modelo efetivo para fixar a versão no SBOM."));
            if (d.license().isBlank())
                findings.add(new Finding("LICENSE_MISSING", "LOW", d.file(),
                    "Licença não declarada para " + coordinate, "Declare a licença no manifesto ou no catálogo."));
            else if (!allowedLicenses.isEmpty() && allowedLicenses.stream().noneMatch(d.license()::equalsIgnoreCase))
                findings.add(new Finding("LICENSE_NOT_ALLOWED", "HIGH", d.file(),
                    "Licença não permitida (" + d.license() + ") para " + coordinate,
                    "Substitua a dependência ou adicione a licença à política."));
        });
        Path catalog = root.resolve("versions-catalog.properties");
        if (Files.isRegularFile(catalog)) findings.addAll(outdated(dependencies, catalog));
        Map<String, Object> cyclonedx = cyclonedx(dependencies);
        Map<String, Object> spdx = spdx(dependencies);
        return new Report(manifests.stream().map(root::relativize).map(Path::toString).toList(),
            List.copyOf(dependencies), List.copyOf(findings), cyclonedx, spdx);
    }

    private void collect(Pattern pattern, String content, Path file, List<Dependency> out, String manager, String license) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String version = matcher.groupCount() >= 3 && matcher.group(3) != null
                ? matcher.group(3).trim() : "";
            out.add(new Dependency(matcher.group(1).trim(), matcher.group(2).trim(), version,
                manager, file.toString(), license));
        }
    }

    /** Compara versões declaradas com um catálogo local (offline) de versões recomendadas. */
    private List<Finding> outdated(List<Dependency> dependencies, Path catalog) throws IOException {
        Properties recommended = new Properties();
        try (var reader = Files.newBufferedReader(catalog)) { recommended.load(reader); }
        List<Finding> findings = new ArrayList<>();
        for (Dependency d : dependencies) {
            String key = d.group() + ":" + d.name();
            String latest = recommended.getProperty(key);
            if (latest != null && !latest.isBlank() && !latest.equals(d.version()) && !d.version().isBlank())
                findings.add(new Finding("OUTDATED_VERSION", "MEDIUM", d.file(),
                    key + " está em " + d.version() + ", catálogo recomenda " + latest,
                    "Atualize para " + latest + " e rode os testes de regressão."));
        }
        return findings;
    }

    /** Gera um catálogo de versões (lockfile) group:name=version a partir do inventário atual. */
    public String lockfile(Report report) {
        Map<String, String> resolved = new TreeMap<>();
        report.dependencies().forEach(d -> {
            if (!d.version().isBlank()) resolved.put(d.group() + ":" + d.name(), d.version());
        });
        StringBuilder out = new StringBuilder("# Catálogo de versões gerado por SwissKnife Javanist\n");
        resolved.forEach((key, version) -> out.append(key).append("=").append(version).append("\n"));
        return out.toString();
    }

    private Map<String, Object> cyclonedx(List<Dependency> dependencies) {
        List<Map<String, Object>> components = dependencies.stream().map(d -> Map.<String, Object>of(
            "type", "library", "group", d.group(), "name", d.name(), "version", d.version(),
            "purl", purl(d))).toList();
        return Map.of("bomFormat", "CycloneDX", "specVersion", "1.6", "version", 1,
            "metadata", Map.of("component", Map.of("type", "application", "name", "SwissKnife-scan")),
            "components", components);
    }

    private Map<String, Object> spdx(List<Dependency> dependencies) {
        List<Map<String, Object>> packages = dependencies.stream().map(d -> Map.<String, Object>of(
            "SPDXID", "SPDXRef-" + safe(d.group() + "-" + d.name() + "-" + d.version()),
            "name", d.group() + ":" + d.name(), "versionInfo", d.version(),
            "downloadLocation", "NOASSERTION", "licenseConcluded", d.license())).toList();
        return Map.of("spdxVersion", "SPDX-2.3", "dataLicense", "CC0-1.0",
            "SPDXID", "SPDXRef-DOCUMENT", "name", "SwissKnife-scan",
            "documentNamespace", "urn:uuid:" + UUID.randomUUID(), "packages", packages);
    }

    private String purl(Dependency d) {
        return "pkg:maven/" + d.group().replace('.', '/') + "/" + d.name() + "@" + d.version();
    }
    private String safe(String value) { return value.replaceAll("[^A-Za-z0-9.-]", "-"); }

    public record Dependency(String group, String name, String version, String manager,
                             String file, String license) {}
    public record Finding(String kind, String severity, String file, String description,
                          String recommendation) {}
    public record Report(List<String> manifests, List<Dependency> dependencies,
                         List<Finding> findings, Map<String, Object> cyclonedx,
                         Map<String, Object> spdx) {}
}
