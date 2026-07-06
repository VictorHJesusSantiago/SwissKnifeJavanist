package dev.swissknife.governance;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Catálogo e verificações de governança para projetos Spring Boot. */
public final class SpringGovernanceAnalyzer {
    public Report analyze(Path root) throws IOException {
        List<Component> components = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (Path file : FilesEx.walk(root, p -> p.toString().endsWith(".java"))) {
            String code = Files.readString(file);
            catalog(root, file, code, components, endpoints);
            if (code.contains("@RestController") && !code.matches("(?s).*(PreAuthorize|Secured|RolesAllowed).*"))
                findings.add(new Finding("CONTROLLER_WITHOUT_AUTHORIZATION", "MEDIUM",
                    root.relativize(file).toString(), "Controller sem autorização declarativa; valide a SecurityFilterChain."));
            if (code.contains("RestTemplate") && !code.matches("(?s).*(setConnectTimeout|connectTimeout).*"))
                findings.add(new Finding("HTTP_TIMEOUT_NOT_VISIBLE", "LOW",
                    root.relativize(file).toString(), "Cliente HTTP sem timeout visível."));
        }
        List<String> properties = new ArrayList<>();
        for (Path file : FilesEx.walk(root, p -> {
            String n = p.getFileName().toString();
            return n.startsWith("application") && (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"));
        })) {
            String content = Files.readString(file);
            properties.add(root.relativize(file).toString());
            if (content.matches("(?s).*open-in-view\\s*[:=]\\s*true.*"))
                findings.add(new Finding("OPEN_IN_VIEW", "MEDIUM", root.relativize(file).toString(),
                    "Open Session in View está habilitado."));
            if (content.matches("(?s).*show-sql\\s*[:=]\\s*true.*"))
                findings.add(new Finding("SHOW_SQL", "LOW", root.relativize(file).toString(),
                    "show-sql deve permanecer desativado em produção."));
            if (content.matches("(?s).*include\\s*:\\s*[\"']?\\*[\"']?.*"))
                findings.add(new Finding("ACTUATOR_ALL_EXPOSED", "HIGH", root.relativize(file).toString(),
                    "Todos os endpoints Actuator foram expostos."));
        }
        return new Report(components, endpoints, properties, findings);
    }

    private void catalog(Path root, Path file, String code, List<Component> components, List<Endpoint> endpoints) {
        Map<String, String> annotations = Map.of("@SpringBootApplication", "APPLICATION", "@RestController", "CONTROLLER",
            "@Controller", "CONTROLLER", "@Service", "SERVICE", "@Repository", "REPOSITORY",
            "@Configuration", "CONFIGURATION", "@Component", "COMPONENT");
        Matcher type = Pattern.compile("\\b(class|interface|record)\\s+(\\w+)").matcher(code);
        if (type.find()) annotations.forEach((annotation, kind) -> {
            if (code.substring(0, type.start()).contains(annotation))
                components.add(new Component(kind, type.group(2), root.relativize(file).toString()));
        });
        String base = mapping(code, "RequestMapping").orElse("");
        Matcher method = Pattern.compile("@(?:[\\w.]+\\.)?(Get|Post|Put|Delete|Patch|Request)Mapping\\s*(?:\\(([^)]*)\\))?").matcher(code);
        while (method.find()) {
            String verb = method.group(1).equals("Request") ? "ANY" : method.group(1).toUpperCase(Locale.ROOT);
            String path = quoted(method.group(2)).orElse("");
            endpoints.add(new Endpoint(verb, normalize(base, path), root.relativize(file).toString(),
                lineOf(code, method.start())));
        }
    }
    private Optional<String> mapping(String code, String annotation) {
        Matcher m = Pattern.compile("@" + annotation + "\\s*\\(([^)]*)\\)").matcher(code);
        return m.find() ? quoted(m.group(1)) : Optional.empty();
    }
    private Optional<String> quoted(String value) {
        if (value == null) return Optional.empty();
        Matcher m = Pattern.compile("[\"']([^\"']*)[\"']").matcher(value);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
    private String normalize(String base, String path) {
        String result = "/" + base + "/" + path;
        return result.replaceAll("/+", "/").replaceAll("/$", "");
    }
    private int lineOf(String value, int offset) { return (int) value.substring(0, offset).lines().count(); }
    public record Component(String kind, String name, String file) {}
    public record Endpoint(String method, String path, String file, int line) {}
    public record Finding(String kind, String severity, String file, String description) {}
    public record Report(List<Component> components, List<Endpoint> endpoints,
                         List<String> configurationFiles, List<Finding> findings) {}
}
