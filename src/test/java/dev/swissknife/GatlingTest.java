package dev.swissknife;

import dev.swissknife.load.GatlingScenarioGenerator;
import dev.swissknife.util.Json;
import java.nio.file.*;

public final class GatlingTest {
    public static void run() throws Exception {
        var root = Files.createTempDirectory("gatling-test");
        var spec = root.resolve("load.json");
        var output = root.resolve("ExampleSimulation.java");
        Files.writeString(spec, """
            {"className":"ExampleSimulation","baseUrl":"http://localhost:8080",
             "users":5,"endpoints":[{"method":"GET","path":"/health","expectedStatus":200}]}
            """);
        var report = new GatlingScenarioGenerator().generate(spec, output);
        TestSupport.truth(Json.stringify(report).contains("ExampleSimulation.java"), "Path deve serializar");
        TestSupport.truth(Files.readString(output).contains("rampUsers(5)"), "Carga inválida");
    }
}
