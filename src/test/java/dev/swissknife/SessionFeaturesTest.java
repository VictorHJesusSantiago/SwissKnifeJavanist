package dev.swissknife;

import dev.swissknife.ai.AiAssistant;
import dev.swissknife.contract.ContractTester;
import dev.swissknife.debt.TechnicalDebtTracker;
import dev.swissknife.dependencies.DependencyVisualizer;
import dev.swissknife.load.GatlingScenarioGenerator;
import dev.swissknife.schema.SchemaComparator;
import dev.swissknife.sql.SqlParser;
import dev.swissknife.testing.TestProductivityAnalyzer;
import dev.swissknife.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Cobertura para as funcionalidades adicionadas ao longo desta sessão de implementação de backlog. */
public final class SessionFeaturesTest {
    public static void run() throws Exception {
        schemaRenameDetection();
        debtNearDuplicates();
        gatlingMultiScenarioAndRedaction();
        contractCookiesAndForm();
        dependencyDomainRules();
        testScaffoldAndFlaky();
        sqlParserMultiJoin();
    }

    private static void sqlParserMultiJoin() {
        var result = SqlParser.parseSelect(
            "SELECT o.id, c.name FROM orders o " +
            "INNER JOIN customers c ON o.customer_id = c.id " +
            "LEFT JOIN payments p ON p.order_id = o.id " +
            "WHERE o.status = 'PAID' GROUP BY c.name ORDER BY o.id DESC");
        TestSupport.equal("orders", result.table());
        TestSupport.equal(2, result.joinCount());
        TestSupport.equal("customers", result.joins().get(0).table());
        TestSupport.equal("payments", result.joins().get(1).table());
        TestSupport.truth(result.whereColumns().contains("status"), "Coluna do WHERE não capturada");
        TestSupport.truth(result.groupByColumns().contains("name"), "Coluna do GROUP BY não capturada");
        TestSupport.truth(result.orderByColumns().contains("id"), "Coluna do ORDER BY não capturada");
    }

    private static void schemaRenameDetection() {
        var comparator = new SchemaComparator();
        var desired = comparator.parse("CREATE TABLE customers (id INTEGER PRIMARY KEY, full_name VARCHAR(120));");
        var actual = comparator.parse("CREATE TABLE clients (id INTEGER PRIMARY KEY, full_name VARCHAR(120));");
        var diff = comparator.compare(desired, actual);
        TestSupport.truth(!diff.possibleRenames().isEmpty(), "Rename de tabela deveria ser sugerido");
        TestSupport.truth(diff.possibleRenames().getFirst().contains("clients") &&
            diff.possibleRenames().getFirst().contains("customers"), "Sugestão de rename incompleta");
    }

    private static void debtNearDuplicates() throws Exception {
        var root = Files.createTempDirectory("debt-near-duplicates");
        Files.writeString(root.resolve("Sample.java"), """
            public class Sample {
                // TODO(ana) melhorar performance consulta principal usuarios
                void a() {}
                // TODO(bruno) melhorar performance consulta principal usuario
                void b() {}
            }
            """);
        var report = new TechnicalDebtTracker().scan(root);
        TestSupport.truth(!report.nearDuplicates().isEmpty(), "Quase-duplicata deveria ser detectada");
    }

    private static void gatlingMultiScenarioAndRedaction() throws Exception {
        var root = Files.createTempDirectory("gatling-session-test");
        var spec = root.resolve("load.json");
        var output = root.resolve("Multi.java");
        Files.writeString(spec, """
            {"className":"Multi","baseUrl":"http://localhost:8080",
             "scenarios":[
               {"name":"checkout","users":5,"rampSeconds":10,
                "endpoints":[{"method":"POST","path":"/checkout","auth":{"type":"bearer","name":"checkout"},
                  "headers":{"X-Api-Key":"segredo-nao-pode-vazar"},"expectedStatus":200}]},
               {"name":"browse","users":8,"rampSeconds":5,
                "endpoints":[{"method":"GET","path":"/catalog","expectedStatus":200}]}
             ]}
            """);
        new GatlingScenarioGenerator().generate(spec, output);
        String generated = Files.readString(output);
        TestSupport.truth(generated.contains("scenario_checkout") && generated.contains("scenario_browse"),
            "Ambos os cenários deveriam ser gerados");
        TestSupport.truth(!generated.contains("segredo-nao-pode-vazar"), "Segredo vazou no código gerado");
        TestSupport.truth(generated.contains("System.getenv("), "Segredo deveria ser lido de variável de ambiente");
    }

    private static void contractCookiesAndForm() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=abc123; Path=/");
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/form", exchange -> {
            byte[] received = exchange.getRequestBody().readAllBytes();
            byte[] body = ("got:" + new String(received, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Path file = Files.createTempFile("contract-session", ".json");
            Files.writeString(file, ("""
                {"contracts":[
                  {"url":"http://127.0.0.1:%d/login","expectedStatus":200,"expectedCookies":{"session":"abc123"}},
                  {"url":"http://127.0.0.1:%d/form","method":"POST","form":{"user":"ana"},"bodyContains":["user=ana"]}
                ]}
                """).formatted(server.getAddress().getPort(), server.getAddress().getPort()));
            var result = (ContractTester.SuiteResult) new ContractTester().executeAny(file);
            TestSupport.truth(result.passed(), "Suíte com cookies e form deveria passar: " + result.results());
        } finally { server.stop(0); }
    }

    private static void dependencyDomainRules() throws Exception {
        var root = Files.createTempDirectory("deps-rules-test");
        Files.createDirectories(root.resolve("order"));
        Files.createDirectories(root.resolve("billing"));
        Files.writeString(root.resolve("order/service.properties"), "name=order\ndomain=sales\ndepends=billing\n");
        Files.writeString(root.resolve("billing/service.properties"), "name=billing\ndomain=finance\n");
        var visualizer = new DependencyVisualizer();
        var graph = visualizer.analyze(root);
        var violations = visualizer.validateDomainRules(graph,
            List.of(new DependencyVisualizer.DomainRule("sales", "finance")));
        TestSupport.truth(!violations.isEmpty(), "Violação de regra de domínio deveria ser detectada");
    }

    private static void testScaffoldAndFlaky() throws Exception {
        var root = Files.createTempDirectory("test-scaffold-test");
        Files.writeString(root.resolve("Calculator.java"), """
            package demo;
            public class Calculator {
                public int sum(int a, int b) { return a + b; }
            }
            """);
        var output = root.resolve("CalculatorTest.java");
        var scaffold = new TestProductivityAnalyzer().scaffold(root.resolve("Calculator.java"), output);
        TestSupport.equal(1, scaffold.methodsCovered());
        TestSupport.truth(Files.readString(output).contains("sum_deveFuncionarCorretamente"), "Método de teste não gerado");

        Path passRun = root.resolve("run1.xml"), failRun = root.resolve("run2.xml");
        Files.writeString(passRun, "<testsuite><testcase classname=\"demo.CalculatorTest\" name=\"sum\" time=\"0.1\"/></testsuite>");
        Files.writeString(failRun, "<testsuite><testcase classname=\"demo.CalculatorTest\" name=\"sum\" time=\"0.1\"><failure message=\"x\"/></testcase></testsuite>");
        var flaky = new TestProductivityAnalyzer().detectFlaky(List.of(passRun, failRun));
        TestSupport.truth(!flaky.isEmpty(), "Teste inconsistente entre execuções deveria ser sinalizado como flaky");
    }
}
