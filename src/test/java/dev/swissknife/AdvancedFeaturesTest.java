package dev.swissknife;

import dev.swissknife.schema.SchemaComparator;
import dev.swissknife.dependencies.DependencyVisualizer;
import dev.swissknife.docs.DocumentationGenerator;
import dev.swissknife.debt.TechnicalDebtTracker;
import dev.swissknife.load.GatlingScenarioGenerator;
import java.nio.file.*;

public final class AdvancedFeaturesTest {
    public static void run() throws Exception {
        schema();
        architecture();
        documentation();
        debt();
        load();
    }

    private static void schema() {
        var comparator = new SchemaComparator();
        var desired = comparator.parse("""
            CREATE SEQUENCE order_seq;
            CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(200) NOT NULL UNIQUE);
            CREATE TABLE orders (
              id BIGINT PRIMARY KEY,
              customer_id BIGINT NOT NULL,
              status VARCHAR(20) DEFAULT 'NEW',
              CONSTRAINT fk_orders_customer FOREIGN KEY(customer_id) REFERENCES customers(id)
            );
            CREATE INDEX idx_orders_status ON orders(status);
            CREATE VIEW open_orders AS SELECT id FROM orders WHERE status = 'NEW';
            """);
        var actual = comparator.parse("""
            CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(100));
            CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT);
            """);
        var diff = comparator.compare(desired, actual);
        TestSupport.truth(diff.changes().stream().anyMatch(c -> c.kind().equals("ADD_CONSTRAINT")), "FK não detectada");
        TestSupport.truth(diff.changes().stream().anyMatch(c -> c.kind().equals("CREATE_INDEX")), "Índice não detectado");
        TestSupport.truth(diff.migrationSql().contains("CREATE SEQUENCE"), "Sequence não gerada");
        TestSupport.truth(!diff.rollbackSql().isBlank(), "Rollback ausente");
    }

    private static void architecture() throws Exception {
        Path root = Files.createTempDirectory("architecture-advanced");
        Files.createDirectories(root.resolve("orders"));
        Files.createDirectories(root.resolve("payments"));
        Files.writeString(root.resolve("orders/service.properties"), "name=orders\ndepends=payments\nurl=http://orders:8080\n");
        Files.writeString(root.resolve("payments/service.properties"), "name=payments\ndepends=orders,missing\nurl=http://payments:8080\n");
        var visualizer = new DependencyVisualizer();
        var graph = visualizer.analyze(root);
        TestSupport.truth(!graph.cycles().isEmpty(), "Ciclo não detectado");
        TestSupport.truth(graph.unknownServices().contains("missing"), "Dependência desconhecida não detectada");
        Path html = root.resolve("graph.html");
        visualizer.write(graph, html, "html");
        TestSupport.truth(Files.size(html) > 100, "HTML arquitetural vazio");
    }

    private static void documentation() throws Exception {
        Path oldRoot = Files.createTempDirectory("docs-old");
        Path newRoot = Files.createTempDirectory("docs-new");
        Files.writeString(oldRoot.resolve("Api.java"), """
            package api; /** API pública. */ public class Api {
              /** Executa. @param value valor @return resultado */ public String run(String value) { return value; }
            }
            """);
        Files.writeString(newRoot.resolve("Api.java"), "package api; public class Api { public int other() { return 1; } }");
        Path site = newRoot.resolve("site");
        var generator = new DocumentationGenerator();
        var report = generator.generateSite(oldRoot, site);
        TestSupport.truth(report.coveragePercentage() > 0, "Cobertura documental ausente");
        TestSupport.truth(Files.isRegularFile(site.resolve("search-index.json")), "Índice de busca ausente");
        TestSupport.truth(generator.compare(oldRoot, newRoot).breaking(), "Breaking change não detectada");
    }

    private static void debt() throws Exception {
        Path root = Files.createTempDirectory("debt-advanced");
        Files.writeString(root.resolve(".swissknife-debt.properties"), "requireOwner=true\nmarkers=TODO,FIXME\n");
        Files.writeString(root.resolve("Example.java"), """
            class Example {
              // FIXME(jose) [SK-42] due=2020-01-01 severity=HIGH effort=5 corrigir segurança
              // TODO sem responsável
            }
            """);
        var report = new TechnicalDebtTracker().scan(root);
        TestSupport.equal(2, report.items().size());
        TestSupport.truth(report.overdue() == 1, "Vencimento não detectado");
        TestSupport.truth(!report.passed(), "Política deveria falhar");
    }

    private static void load() throws Exception {
        Path root = Files.createTempDirectory("load-advanced");
        Path spec = root.resolve("spec.json");
        Files.writeString(spec, """
            {"className":"AdvancedSimulation","baseUrl":"http://localhost:8080","users":20,
             "rampSeconds":30,"injection":"stress","feeder":"users.csv",
             "headers":{"Authorization":"Bearer #{token}"},
             "assertions":[{"metric":"p95","maximum":500},{"metric":"failed-percent","maximum":1}],
             "endpoints":[{"name":"create","method":"POST","path":"/orders","body":"{\\"id\\":\\"#{id}\\"}",
              "expectedStatus":201,"jsonPath":{"$.status":"CREATED"},"save":{"$.id":"orderId"},"pauseMs":100}]}
            """);
        var project = new GatlingScenarioGenerator().generateProject(spec, root.resolve("project"));
        String source = Files.readString(project.simulation());
        TestSupport.truth(source.contains("stressPeakUsers"), "Perfil stress ausente");
        TestSupport.truth(source.contains("percentile(95.0)"), "Assertion p95 ausente");
        TestSupport.truth(Files.isRegularFile(project.pom()), "Projeto Maven ausente");
    }
}
