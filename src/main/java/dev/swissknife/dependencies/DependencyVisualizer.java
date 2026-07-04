package dev.swissknife.dependencies;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class DependencyVisualizer {
    public Graph analyze(Path directory) throws IOException {
        Map<String, Service> services = new TreeMap<>();
        for (var file : FilesEx.walk(directory, p -> p.getFileName().toString().equals("service.properties"))) {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(file)) { props.load(reader); }
            String name = required(props, "name", file);
            var deps = Arrays.stream(props.getProperty("depends", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).sorted().toList();
            services.put(name, new Service(name, props.getProperty("url", ""), deps));
        }
        List<Edge> edges = new ArrayList<>();
        services.values().forEach(s -> s.dependencies().forEach(d -> edges.add(new Edge(s.name(), d))));
        return new Graph(services, edges);
    }

    public void writeMermaid(Graph graph, Path output) throws IOException {
        var text = new StringBuilder("flowchart LR\n");
        graph.services().values().forEach(s -> text.append("  ").append(id(s.name()))
            .append("[\"").append(s.name()).append("\"]\n"));
        graph.edges().forEach(e -> text.append("  ").append(id(e.from())).append(" --> ").append(id(e.to())).append("\n"));
        FilesEx.write(output, text.toString());
    }

    private String required(Properties p, String key, Path file) {
        var value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " ausente em " + file);
        return value.trim();
    }
    private String id(String name) { return name.replaceAll("[^A-Za-z0-9_]", "_"); }

    public record Service(String name, String url, List<String> dependencies) {}
    public record Edge(String from, String to) {}
    public record Graph(Map<String, Service> services, List<Edge> edges) {}
}
