package dev.swissknife.load;

import dev.swissknife.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Gera simulações Gatling completas a partir de especificação JSON. */
public final class GatlingScenarioGenerator {
    public Report generate(Path specification, Path output) throws IOException {
        Map<String, Object> spec = Json.object(Files.readString(specification));
        String className = identifier(text(spec, "className", "GeneratedSimulation"));
        String packageName = text(spec, "packageName", "simulations").replaceAll("[^A-Za-z0-9_.]", "");
        String baseUrl = text(spec, "baseUrl", "http://localhost:8080");
        Map<String, String> globalHeaders = strings(spec.get("headers"));
        List<Assertion> assertions = assertions(spec.get("assertions"));
        List<ScenarioSpec> scenarios = scenarios(spec, className);
        int warmupUsers = number(spec, "warmupUsers", 0);
        int warmupSeconds = number(spec, "warmupSeconds", 0);

        StringBuilder source = new StringBuilder("package ").append(packageName).append(";\n\n")
            .append("""
                import io.gatling.javaapi.core.*;
                import io.gatling.javaapi.http.*;
                import java.time.Duration;
                import java.util.Map;
                import static io.gatling.javaapi.core.CoreDsl.*;
                import static io.gatling.javaapi.http.HttpDsl.*;

                """)
            .append("public class ").append(className).append(" extends Simulation {\n")
            .append("    private final HttpProtocolBuilder httpProtocol = http.baseUrl(\"").append(escape(baseUrl)).append("\")")
            .append("\n        .acceptHeader(\"application/json\")")
            .append("\n        .contentTypeHeader(\"application/json\");\n\n");

        for (ScenarioSpec scenarioSpec : scenarios) {
            if (!scenarioSpec.feeder().isBlank())
                source.append("    private final FeederBuilder<String> feeder_").append(scenarioSpec.variableName())
                    .append(" = ").append(feederCode(scenarioSpec)).append(".circular();\n\n");
            source.append("    private final ScenarioBuilder scenario_").append(scenarioSpec.variableName())
                .append(" = scenario(\"").append(escape(scenarioSpec.name())).append("\")\n");
            if (!scenarioSpec.feeder().isBlank()) source.append("        .feed(feeder_").append(scenarioSpec.variableName()).append(")\n");
            List<Endpoint> endpoints = scenarioSpec.endpoints();
            for (int i = 0; i < endpoints.size(); i++) appendEndpoint(source, endpoints.get(i), i + 1, globalHeaders);
            source.append("        ;\n\n");
        }

        source.append("    {\n        setUp(\n            ");
        List<String> injections = new ArrayList<>();
        for (ScenarioSpec scenarioSpec : scenarios) {
            String steps = warmupUsers > 0
                ? "nothingFor(Duration.ofSeconds(" + warmupSeconds + ")), atOnceUsers(" + warmupUsers + "), " + injection(scenarioSpec)
                : injection(scenarioSpec);
            injections.add("scenario_" + scenarioSpec.variableName() + ".injectOpen(" + steps + ")");
        }
        source.append(String.join(",\n            ", injections)).append("\n        )\n            .protocols(httpProtocol)");
        if (!assertions.isEmpty()) source.append("\n            .assertions(\n                ")
            .append(String.join(",\n                ", assertions.stream().map(this::assertionCode).toList())).append("\n            )");
        source.append(";\n    }\n}\n");
        FilesEx.write(output, source.toString());
        ScenarioSpec first = scenarios.getFirst();
        return new Report(className, scenarios.stream().mapToInt(s -> s.endpoints().size()).sum(),
            first.users(), first.rampSeconds(), first.injection(), assertions.size(), first.feeder(), output);
    }

    private List<ScenarioSpec> scenarios(Map<String, Object> spec, String defaultClassName) {
        if (spec.get("scenarios") instanceof List<?> list && !list.isEmpty()) {
            List<ScenarioSpec> result = new ArrayList<>();
            int index = 0;
            for (Object item : list) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) item;
                index++;
                String name = text(map, "name", defaultClassName + "-" + index);
                result.add(new ScenarioSpec(name, identifier(name.replaceAll("[^A-Za-z0-9_]", "_")),
                    endpoints(map.get("endpoints")), number(map, "users", 10), number(map, "rampSeconds", 10),
                    text(map, "injection", "ramp"), text(map, "feeder", ""), text(map, "feederType", "csv")));
            }
            return result;
        }
        String name = text(spec, "scenarioName", defaultClassName);
        return List.of(new ScenarioSpec(name, identifier(name.replaceAll("[^A-Za-z0-9_]", "_")),
            endpoints(spec.get("endpoints")), number(spec, "users", 10), number(spec, "rampSeconds", 10),
            text(spec, "injection", "ramp"), text(spec, "feeder", ""), text(spec, "feederType", "csv")));
    }

    private String feederCode(ScenarioSpec scenario) {
        return scenario.feederType().equalsIgnoreCase("json")
            ? "jsonFile(\"" + escape(scenario.feeder()) + "\")"
            : "csv(\"" + escape(scenario.feeder()) + "\")";
    }

    public ProjectReport generateProject(Path specification, Path directory) throws IOException {
        Map<String, Object> spec = Json.object(Files.readString(specification));
        String className = identifier(text(spec, "className", "GeneratedSimulation"));
        Path simulation = directory.resolve("src/test/java/simulations").resolve(className + ".java");
        Report report = generate(specification, simulation);
        FilesEx.write(directory.resolve("pom.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.swissknife.load</groupId><artifactId>generated-load-tests</artifactId><version>1.0.0</version>
              <properties><maven.compiler.release>21</maven.compiler.release><gatling.version>3.13.5</gatling.version></properties>
              <dependencies>
                <dependency><groupId>io.gatling.highcharts</groupId><artifactId>gatling-charts-highcharts</artifactId><version>${gatling.version}</version><scope>test</scope></dependency>
              </dependencies>
              <build><plugins><plugin><groupId>io.gatling</groupId><artifactId>gatling-maven-plugin</artifactId><version>4.16.1</version></plugin></plugins></build>
            </project>
            """);
        FilesEx.write(directory.resolve("README.md"), """
            # Teste de carga gerado

            Execute:

            ```shell
            mvn gatling:test -Dgatling.simulationClass=simulations.%s
            ```
            """.formatted(className));
        return new ProjectReport(directory, simulation, directory.resolve("pom.xml"), report);
    }

    private static final java.util.regex.Pattern SENSITIVE_HEADER =
        java.util.regex.Pattern.compile("(?i)authorization|api[-_]?key|token|secret|password");

    private void appendEndpoint(StringBuilder source, Endpoint endpoint, int index,
                                Map<String, String> globalHeaders) {
        String requestName = endpoint.name().isBlank() ? "request-" + index : endpoint.name();
        boolean grouped = !endpoint.group().isBlank();
        if (grouped) source.append("        .group(\"").append(escape(endpoint.group())).append("\").on(\n    ");
        source.append("        .exec(http(\"").append(escape(requestName)).append("\").")
            .append(method(endpoint.method())).append("(\"").append(escape(endpoint.path())).append("\")");
        Map<String, String> headers = new LinkedHashMap<>(globalHeaders); headers.putAll(endpoint.headers());
        headers.forEach((name, value) -> {
            source.append("\n            .header(\"").append(escape(name)).append("\", ");
            if (SENSITIVE_HEADER.matcher(name).find()) {
                String envVar = "GATLING_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
                source.append("System.getenv(\"").append(envVar).append("\") /* segredo redigido; defina a variável de ambiente */");
            } else source.append("\"").append(escape(value)).append("\"");
            source.append(")");
        });
        applyAuth(source, endpoint.auth());
        if (!endpoint.body().isBlank()) source.append("\n            .body(StringBody(\"")
            .append(escape(endpoint.body())).append("\"))");
        source.append("\n            .check(status().is(").append(endpoint.expectedStatus()).append("))");
        endpoint.jsonPathChecks().forEach((path, value) -> source.append("\n            .check(jsonPath(\"")
            .append(escape(path)).append("\").is(\"").append(escape(value)).append("\"))"));
        endpoint.regexChecks().forEach(regex -> source.append("\n            .check(regex(\"")
            .append(escape(regex)).append("\").exists())"));
        endpoint.saves().forEach((path, variable) -> source.append("\n            .check(jsonPath(\"")
            .append(escape(path)).append("\").saveAs(\"").append(escape(variable)).append("\"))"));
        source.append(")\n");
        if (endpoint.pauseMs() > 0) source.append("        .pause(Duration.ofMillis(").append(endpoint.pauseMs()).append("))\n");
        if (grouped) source.append("        )\n");
    }

    private void applyAuth(StringBuilder source, Map<String, String> auth) {
        if (auth.isEmpty()) return;
        String type = auth.getOrDefault("type", "").toLowerCase(Locale.ROOT);
        String envVar = "GATLING_AUTH_" + auth.getOrDefault("name", "TOKEN").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (type.equals("bearer")) source.append("\n            .header(\"Authorization\", \"Bearer \" + System.getenv(\"")
            .append(envVar).append("\")) /* token lido de variável de ambiente, nunca embutido */");
        else if (type.equals("basic")) source.append("\n            .basicAuth(System.getenv(\"")
            .append(envVar).append("_USER\"), System.getenv(\"").append(envVar).append("_PASS\"))");
    }

    private String injection(ScenarioSpec scenario) {
        int users = scenario.users(), seconds = scenario.rampSeconds();
        return switch (scenario.injection().toLowerCase(Locale.ROOT)) {
            case "constant" -> "constantUsersPerSec(" + users + ").during(Duration.ofSeconds(" + seconds + "))";
            case "closed" -> "constantConcurrentUsers(" + users + ").during(Duration.ofSeconds(" + seconds + "))";
            case "at-once", "atonce" -> "atOnceUsers(" + users + ")";
            case "nothing-for" -> "nothingFor(Duration.ofSeconds(" + seconds + ")), atOnceUsers(" + users + ")";
            case "stress" -> "stressPeakUsers(" + users + ").during(Duration.ofSeconds(" + seconds + "))";
            case "soak", "endurance" -> "constantUsersPerSec(" + users + ").during(Duration.ofSeconds(" + seconds + "))";
            case "increment" -> "incrementUsersPerSec(" + Math.max(1, users / 5) + ").times(5).eachLevelLasting(Duration.ofSeconds(" +
                Math.max(1, seconds / 5) + ")).startingFrom(1)";
            default -> "rampUsers(" + users + ").during(Duration.ofSeconds(" + seconds + "))";
        };
    }
    private String assertionCode(Assertion assertion) {
        String scope = assertion.target().isBlank() ? "global()" : "details(\"" + escape(assertion.target()) + "\")";
        return switch (assertion.metric().toLowerCase(Locale.ROOT)) {
            case "p95" -> scope + ".responseTime().percentile(95.0).lt(" + assertion.maximum() + ")";
            case "p99" -> scope + ".responseTime().percentile(99.0).lt(" + assertion.maximum() + ")";
            case "mean" -> scope + ".responseTime().mean().lt(" + assertion.maximum() + ")";
            case "failed-percent" -> scope + ".failedRequests().percent().lt(" + assertion.maximum() + ")";
            case "requests" -> scope + ".allRequests().count().gte(" + assertion.maximum() + ")";
            default -> throw new IllegalArgumentException("Assertion Gatling desconhecida: " + assertion.metric());
        };
    }

    @SuppressWarnings("unchecked")
    private List<Endpoint> endpoints(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty())
            throw new IllegalArgumentException("endpoints deve ser uma lista não vazia");
        List<Endpoint> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("endpoint deve ser um objeto");
            Map<String, Object> map = (Map<String, Object>) raw;
            int repeat = Math.max(1, number(map, "repeat", 1));
            Endpoint endpoint = new Endpoint(text(map, "name", ""), text(map, "method", "GET").toUpperCase(Locale.ROOT),
                text(map, "path", "/"), number(map, "expectedStatus", 200), text(map, "body", ""),
                strings(map.get("headers")), strings(map.get("jsonPath")), stringList(map.get("regex")),
                strings(map.get("save")), number(map, "pauseMs", 0), text(map, "group", ""), strings(map.get("auth")));
            for (int i = 0; i < repeat; i++) result.add(endpoint);
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private List<Assertion> assertions(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> {
            Map<String, Object> map = (Map<String, Object>) item;
            return new Assertion(text(map, "metric", "p95"), number(map, "maximum", 1000), text(map, "target", ""));
        }).toList();
    }
    private Map<String, String> strings(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) ->
            result.put(String.valueOf(key), item instanceof String ? String.valueOf(item) : Json.stringify(item)));
        return result;
    }
    private List<String> stringList(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
    private int number(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number number ? number.intValue() : fallback;
    }
    private String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key); return value == null ? fallback : String.valueOf(value);
    }
    private String identifier(String value) {
        String result = value.replaceAll("[^A-Za-z0-9_$]", "");
        if (result.isBlank() || !Character.isJavaIdentifierStart(result.charAt(0)))
            throw new IllegalArgumentException("Nome de classe inválido: " + value);
        return result;
    }
    private String method(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "GET" -> "get"; case "POST" -> "post"; case "PUT" -> "put"; case "DELETE" -> "delete";
            case "PATCH" -> "patch"; case "HEAD" -> "head"; case "OPTIONS" -> "options";
            default -> throw new IllegalArgumentException("Método HTTP não suportado pelo gerador: " + value);
        };
    }
    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    public record Endpoint(String name, String method, String path, int expectedStatus, String body,
                           Map<String, String> headers, Map<String, String> jsonPathChecks,
                           List<String> regexChecks, Map<String, String> saves, int pauseMs,
                           String group, Map<String, String> auth) {}
    public record Assertion(String metric, int maximum, String target) {}
    public record ScenarioSpec(String name, String variableName, List<Endpoint> endpoints, int users,
                               int rampSeconds, String injection, String feeder, String feederType) {}
    public record Report(String className, int endpoints, int users, int rampSeconds,
                         String injection, int assertions, String feeder, Path output) {}
    public record ProjectReport(Path directory, Path simulation, Path pom, Report simulationReport) {}
}
