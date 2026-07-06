package dev.swissknife.dependencies;

import dev.swissknife.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Descobre serviços e relações e produz análises de impacto arquitetural. */
public final class DependencyVisualizer {
    public Graph analyze(Path directory) throws IOException {
        Map<String, MutableService> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        discoverProperties(directory, found);
        discoverBuilds(directory, found);
        discoverDocker(directory, found);
        discoverKubernetes(directory, found);
        discoverJavaClients(directory, found);
        Map<String, Service> services = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        found.forEach((name, service) -> services.put(name, service.freeze()));
        List<Edge> edges = new ArrayList<>();
        services.values().forEach(service -> service.dependencyDetails().forEach(target ->
            edges.add(new Edge(service.name(), target.name(), target.protocol(), target.kind(),
                target.optional(), target.source()))));
        Set<String> unknown = new TreeSet<>();
        edges.stream().map(Edge::to).filter(target -> !services.containsKey(target)).forEach(unknown::add);
        List<List<String>> cycles = cycles(services, edges);
        Set<String> orphans = new TreeSet<>(services.keySet());
        edges.forEach(edge -> { orphans.remove(edge.from()); orphans.remove(edge.to()); });
        Map<String, Integer> centrality = new TreeMap<>();
        services.keySet().forEach(name -> centrality.put(name, 0));
        edges.forEach(edge -> centrality.computeIfPresent(edge.to(), (key, value) -> value + 1));
        Map<String, List<String>> impact = new TreeMap<>();
        services.keySet().forEach(name -> impact.put(name, dependents(name, edges)));
        int criticalPath = services.keySet().stream().mapToInt(name -> longest(name, edges, new HashSet<>())).max().orElse(0);
        return new Graph(services, List.copyOf(edges), unknown, orphans, cycles,
            centrality, impact, criticalPath);
    }

    public void writeMermaid(Graph graph, Path output) throws IOException {
        StringBuilder text = new StringBuilder("flowchart LR\n");
        graph.services().values().forEach(service -> text.append("  ").append(id(service.name()))
            .append("[\"").append(escape(service.name())).append("\\n").append(escape(service.kind())).append("\"]\n"));
        graph.unknownServices().forEach(name -> text.append("  ").append(id(name)).append("{{\"")
            .append(escape(name)).append("\\nDESCONHECIDO\"}}\n"));
        graph.edges().forEach(edge -> text.append("  ").append(id(edge.from())).append(" -->|\"")
            .append(edge.protocol()).append(edge.optional() ? " opcional" : "").append("\"| ")
            .append(id(edge.to())).append("\n"));
        graph.cycles().forEach(cycle -> text.append("  %% ciclo: ").append(String.join(" -> ", cycle)).append("\n"));
        FilesEx.write(output, text.toString());
    }

    public void write(Graph graph, Path output, String format) throws IOException {
        switch (format.toLowerCase(Locale.ROOT)) {
            case "mermaid", "mmd" -> writeMermaid(graph, output);
            case "plantuml", "puml" -> FilesEx.write(output, plantUml(graph));
            case "dot", "graphviz" -> FilesEx.write(output, dot(graph));
            case "json" -> FilesEx.write(output, Json.stringify(graph));
            case "html" -> FilesEx.write(output, html(graph));
            default -> throw new IllegalArgumentException("Formato de grafo inválido: " + format);
        }
    }

    private void discoverProperties(Path root, Map<String, MutableService> found) throws IOException {
        for (Path file : FilesEx.walk(root, p -> p.getFileName().toString().equals("service.properties"))) {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
            String name = required(properties, "name", file);
            MutableService service = found.computeIfAbsent(name, MutableService::new);
            service.url = properties.getProperty("url", service.url);
            service.kind = properties.getProperty("kind", "SERVICE");
            service.domain = properties.getProperty("domain", "");
            service.owner = properties.getProperty("owner", "");
            service.version = properties.getProperty("version", "");
            for (String dependency : properties.getProperty("depends", "").split(",")) {
                String target = dependency.trim();
                if (!target.isEmpty()) service.add(target, protocol(service.url), "DECLARED", false, file.toString());
            }
            service.sources.add(file.toString());
        }
    }

    private void discoverBuilds(Path root, Map<String, MutableService> found) throws IOException {
        for (Path file : FilesEx.walk(root, p -> Set.of("pom.xml", "settings.gradle", "settings.gradle.kts").contains(p.getFileName().toString()))) {
            String content = Files.readString(file);
            if (file.getFileName().toString().equals("pom.xml")) {
                String artifact = match(content, "<artifactId>([^<]+)</artifactId>").orElse(file.getParent().getFileName().toString());
                MutableService service = found.computeIfAbsent(artifact, MutableService::new);
                service.kind = "JAVA_MODULE"; service.sources.add(file.toString());
                Matcher module = Pattern.compile("<module>([^<]+)</module>").matcher(content);
                while (module.find()) service.add(Path.of(module.group(1)).getFileName().toString(), "build", "MODULE", false, file.toString());
            } else {
                String name = match(content, "rootProject\\.name\\s*=\\s*[\"']([^\"']+)").orElse(file.getParent().getFileName().toString());
                MutableService service = found.computeIfAbsent(name, MutableService::new);
                service.kind = "JAVA_MODULE"; service.sources.add(file.toString());
                Matcher include = Pattern.compile("include\\s*\\(?\\s*[\"']:?([^\"']+)").matcher(content);
                while (include.find()) service.add(include.group(1).replace(':', '-'), "build", "MODULE", false, file.toString());
            }
        }
    }

    private void discoverDocker(Path root, Map<String, MutableService> found) throws IOException {
        for (Path file : FilesEx.walk(root, p -> p.getFileName().toString().matches("docker-compose.*\\.ya?ml|compose\\.ya?ml"))) {
            String content = Files.readString(file);
            boolean servicesSection = false;
            int serviceIndent = -1;
            MutableService current = null;
            for (String line : content.lines().toList()) {
                if (line.strip().equals("services:")) { servicesSection = true; continue; }
                if (!servicesSection || line.isBlank() || line.stripLeading().startsWith("#")) continue;
                int indent = line.length() - line.stripLeading().length();
                Matcher key = Pattern.compile("([\\w.-]+):\\s*$").matcher(line.strip());
                if (key.matches() && (serviceIndent < 0 || indent == serviceIndent)) {
                    serviceIndent = indent; current = found.computeIfAbsent(key.group(1), MutableService::new);
                    current.kind = "CONTAINER"; current.sources.add(file.toString()); continue;
                }
                if (current != null && line.strip().startsWith("- ") && line.contains("http"))
                    for (String url : urls(line))
                        current.add(host(url), protocol(url), "HTTP", false, file.toString());
            }
        }
    }

    private void discoverKubernetes(Path root, Map<String, MutableService> found) throws IOException {
        for (Path file : FilesEx.walk(root, p -> p.getFileName().toString().matches(".*\\.ya?ml"))) {
            String content = Files.readString(file);
            if (!content.matches("(?s).*kind:\\s*(Deployment|StatefulSet|Service).*")) continue;
            String name = match(content, "(?m)^\\s*name:\\s*([\\w.-]+)").orElse(null);
            if (name == null) continue;
            MutableService service = found.computeIfAbsent(name, MutableService::new);
            service.kind = "KUBERNETES"; service.sources.add(file.toString());
            urls(content).forEach(url -> service.add(host(url), protocol(url), "CONFIGURATION", false, file.toString()));
        }
    }

    private void discoverJavaClients(Path root, Map<String, MutableService> found) throws IOException {
        for (Path file : FilesEx.walk(root, p -> p.toString().endsWith(".java"))) {
            String content = Files.readString(file);
            String module = nearestModule(root, file);
            MutableService service = found.computeIfAbsent(module, MutableService::new);
            if (content.contains("@FeignClient")) {
                Matcher feign = Pattern.compile("@FeignClient\\s*\\([^)]*(?:name|value)\\s*=\\s*[\"']([^\"']+)").matcher(content);
                while (feign.find()) service.add(feign.group(1), "http", "FEIGN", false, file.toString());
            }
            if (content.matches("(?s).*(RestClient|RestTemplate|WebClient).*"))
                urls(content).forEach(url -> service.add(host(url), protocol(url), "HTTP_CLIENT", false, file.toString()));
            Matcher kafka = Pattern.compile("@KafkaListener\\s*\\([^)]*topics\\s*=\\s*[\"']([^\"']+)").matcher(content);
            while (kafka.find()) service.add("kafka:" + kafka.group(1), "kafka", "CONSUMER", false, file.toString());
            Matcher rabbit = Pattern.compile("@RabbitListener\\s*\\([^)]*queues\\s*=\\s*[\"']([^\"']+)").matcher(content);
            while (rabbit.find()) service.add("rabbit:" + rabbit.group(1), "amqp", "CONSUMER", false, file.toString());
        }
    }

    private String plantUml(Graph graph) {
        StringBuilder out = new StringBuilder("@startuml\nleft to right direction\n");
        graph.services().values().forEach(s -> out.append("component \"").append(escape(s.name())).append("\" as ").append(id(s.name())).append("\n"));
        graph.edges().forEach(e -> out.append(id(e.from())).append(" --> ").append(id(e.to()))
            .append(" : ").append(e.protocol()).append("\n"));
        return out.append("@enduml\n").toString();
    }
    private String dot(Graph graph) {
        StringBuilder out = new StringBuilder("digraph services {\n  rankdir=LR;\n");
        graph.services().keySet().forEach(name -> out.append("  ").append(id(name)).append(" [label=\"").append(escape(name)).append("\"];\n"));
        graph.edges().forEach(e -> out.append("  ").append(id(e.from())).append(" -> ").append(id(e.to()))
            .append(" [label=\"").append(e.protocol()).append("\"];\n"));
        return out.append("}\n").toString();
    }
    private String html(Graph graph) {
        return """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Arquitetura SwissKnife</title>
            <style>body{font:15px system-ui;margin:2rem;background:#f7f5ef;color:#17213a}input{padding:.7rem;width:min(30rem,90%%)}
            table{border-collapse:collapse;width:100%%;margin-top:1rem}th,td{padding:.6rem;border-bottom:1px solid #ccc;text-align:left}
            .warn{color:#b33}</style></head><body><h1>Mapa de dependências</h1><input id="q" placeholder="Filtrar serviços">
            <p>%d serviços · %d relações · caminho crítico %d</p><p class="warn">Desconhecidos: %s · Ciclos: %s</p>
            <table><thead><tr><th>Origem</th><th>Destino</th><th>Protocolo</th><th>Tipo</th></tr></thead><tbody id="rows">%s</tbody></table>
            <script>q.oninput=()=>{for(const r of rows.rows)r.hidden=!r.innerText.toLowerCase().includes(q.value.toLowerCase())}</script>
            </body></html>
            """.formatted(graph.services().size(), graph.edges().size(), graph.criticalPathLength(),
            escape(graph.unknownServices().toString()), escape(graph.cycles().toString()),
            graph.edges().stream().map(e -> "<tr><td>"+escape(e.from())+"</td><td>"+escape(e.to())+
                "</td><td>"+escape(e.protocol())+"</td><td>"+escape(e.kind())+"</td></tr>").reduce("", String::concat));
    }

    private List<List<String>> cycles(Map<String, Service> services, List<Edge> edges) {
        List<List<String>> result = new ArrayList<>();
        for (String start : services.keySet()) cycleDfs(start, start, edges, new LinkedHashSet<>(), result);
        return result.stream().distinct().toList();
    }
    private void cycleDfs(String start, String current, List<Edge> edges, LinkedHashSet<String> path,
                          List<List<String>> result) {
        if (!path.add(current)) return;
        for (Edge edge : edges.stream().filter(e -> e.from().equalsIgnoreCase(current)).toList()) {
            if (edge.to().equalsIgnoreCase(start) && path.size() > 1) {
                List<String> cycle = new ArrayList<>(path); cycle.add(start);
                String minimum = cycle.stream().limit(cycle.size() - 1).min(String.CASE_INSENSITIVE_ORDER).orElse(start);
                if (cycle.getFirst().equalsIgnoreCase(minimum)) result.add(cycle);
            } else if (path.size() < 30) cycleDfs(start, edge.to(), edges, new LinkedHashSet<>(path), result);
        }
    }
    private List<String> dependents(String target, List<Edge> edges) {
        Set<String> result = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>(); queue.add(target);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            edges.stream().filter(e -> e.to().equalsIgnoreCase(current)).map(Edge::from).forEach(parent -> {
                if (result.add(parent)) queue.add(parent);
            });
        }
        result.remove(target); return List.copyOf(result);
    }
    private int longest(String current, List<Edge> edges, Set<String> visited) {
        if (!visited.add(current)) return 0;
        return 1 + edges.stream().filter(e -> e.from().equalsIgnoreCase(current))
            .mapToInt(e -> longest(e.to(), edges, new HashSet<>(visited))).max().orElse(0);
    }
    private Optional<String> match(String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content); return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
    private List<String> urls(String content) {
        List<String> result = new ArrayList<>(); Matcher m = Pattern.compile("https?://[A-Za-z0-9_.:-]+").matcher(content);
        while (m.find()) result.add(m.group()); return result;
    }
    private String host(String url) { try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return url; } }
    private String protocol(String url) { try { return java.net.URI.create(url).getScheme(); } catch (Exception e) { return "declared"; } }
    private String nearestModule(Path root, Path file) {
        Path relative = root.relativize(file);
        return relative.getNameCount() > 1 ? relative.getName(0).toString() : root.getFileName().toString();
    }
    private String required(Properties properties, String key, Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " ausente em " + file);
        return value.trim();
    }
    private String id(String name) { return name.replaceAll("[^A-Za-z0-9_]", "_"); }
    private String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }

    private static final class MutableService {
        final String name;
        String url = "", kind = "SERVICE", domain = "", owner = "", version = "";
        final Map<String, Dependency> dependencies = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Set<String> sources = new TreeSet<>();
        MutableService(String name) { this.name = name; }
        void add(String target, String protocol, String kind, boolean optional, String source) {
            if (target == null || target.isBlank() || target.equalsIgnoreCase(name)) return;
            dependencies.putIfAbsent(target, new Dependency(target, protocol == null ? "" : protocol, kind, optional, source));
        }
        Service freeze() { return new Service(name, url, List.copyOf(dependencies.values()), kind, domain, owner, version, List.copyOf(sources)); }
    }
    public record Dependency(String name, String protocol, String kind, boolean optional, String source) {}
    public record Service(String name, String url, List<Dependency> dependencyDetails, String kind,
                          String domain, String owner, String version, List<String> sources) {
        public List<String> dependencies() { return dependencyDetails.stream().map(Dependency::name).toList(); }
    }
    public record Edge(String from, String to, String protocol, String kind, boolean optional, String source) {}
    public record Graph(Map<String, Service> services, List<Edge> edges, Set<String> unknownServices,
                        Set<String> orphanServices, List<List<String>> cycles,
                        Map<String, Integer> centrality, Map<String, List<String>> impact,
                        int criticalPathLength) {}
}
