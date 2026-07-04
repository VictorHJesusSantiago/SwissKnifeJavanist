package dev.swissknife.load;

import dev.swissknife.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class GatlingScenarioGenerator {
    public Report generate(Path specification, Path output) throws IOException {
        var spec = Json.object(Files.readString(specification));
        String className = text(spec, "className", "GeneratedSimulation").replaceAll("\\W", "");
        String baseUrl = text(spec, "baseUrl", "http://localhost:8080");
        int users = ((Number) spec.getOrDefault("users", 10L)).intValue();
        int seconds = ((Number) spec.getOrDefault("rampSeconds", 10L)).intValue();
        var endpoints = endpoints(spec.get("endpoints"));
        var source = new StringBuilder("""
            package simulations;

            import io.gatling.javaapi.core.*;
            import io.gatling.javaapi.http.*;
            import static io.gatling.javaapi.core.CoreDsl.*;
            import static io.gatling.javaapi.http.HttpDsl.*;

            public class %s extends Simulation {
                private final HttpProtocolBuilder httpProtocol = http.baseUrl("%s");
                private final ScenarioBuilder scenario = scenario("%s")
            """.formatted(className, escape(baseUrl), className));
        for (int i = 0; i < endpoints.size(); i++) {
            var e = endpoints.get(i);
            source.append("        .exec(http(\"request-").append(i + 1).append("\").")
                .append(e.method().toLowerCase(Locale.ROOT)).append("(\"").append(escape(e.path())).append("\")")
                .append(".check(status().is(").append(e.expectedStatus()).append(")))\n")
                .append(i == endpoints.size() - 1 ? "        ;\n" : "");
        }
        source.append("""

                {
                    setUp(scenario.injectOpen(rampUsers(%d).during(%d)))
                        .protocols(httpProtocol);
                }
            }
            """.formatted(users, seconds));
        FilesEx.write(output, source.toString());
        return new Report(className, endpoints.size(), users, output);
    }

    @SuppressWarnings("unchecked")
    private List<Endpoint> endpoints(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("endpoints deve ser uma lista não vazia");
        List<Endpoint> result = new ArrayList<>();
        for (var item : list) {
            var map = (Map<String, Object>) item;
            result.add(new Endpoint(text(map, "method", "GET"), text(map, "path", "/"),
                ((Number) map.getOrDefault("expectedStatus", 200L)).intValue()));
        }
        return result;
    }
    private String text(Map<String, Object> m, String k, String d) { return m.get(k) == null ? d : String.valueOf(m.get(k)); }
    private String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
    public record Endpoint(String method, String path, int expectedStatus) {}
    public record Report(String className, int endpoints, int users, Path output) {}
}
