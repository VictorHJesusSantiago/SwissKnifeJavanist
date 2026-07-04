package dev.swissknife;

import dev.swissknife.anonymize.*;
import dev.swissknife.contract.*;
import dev.swissknife.debt.*;
import dev.swissknife.dependencies.*;
import dev.swissknife.docs.*;
import dev.swissknife.itam.*;
import dev.swissknife.load.*;
import dev.swissknife.migration.*;
import dev.swissknife.schema.*;
import dev.swissknife.sql.*;
import dev.swissknife.util.*;
import dev.swissknife.vulnerability.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) {
        try {
            if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) { help(); return; }
            Object result = execute(args);
            if (result != null) System.out.println(Json.stringify(result));
        } catch (Exception e) {
            System.err.println("Erro: " + e.getMessage());
            if (System.getenv("SWISSKNIFE_DEBUG") != null) e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static Object execute(String[] args) throws Exception {
        return switch (args[0]) {
            case "docs" -> {
                require(args, 3, "docs <diretório-fonte> <arquivo-saída>");
                yield new DocumentationGenerator().generate(path(args[1]), path(args[2]));
            }
            case "deps" -> {
                require(args, 3, "deps <diretório-serviços> <arquivo.mmd>");
                var visualizer = new DependencyVisualizer();
                var graph = visualizer.analyze(path(args[1]));
                visualizer.writeMermaid(graph, path(args[2]));
                yield Map.of("services", graph.services().size(), "dependencies", graph.edges().size(), "output", args[2]);
            }
            case "slow-query" -> {
                require(args, 2, "slow-query <sql>");
                yield new SlowQueryAnalyzer().analyze(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "schema-diff" -> {
                require(args, 3, "schema-diff <schema-desejado.sql> <schema-atual.sql>");
                var comparator = new SchemaComparator();
                yield comparator.compare(comparator.parse(path(args[1])), comparator.parse(path(args[2])));
            }
            case "anonymize" -> {
                require(args, 4, "anonymize <entrada.csv> <política.properties> <saída.csv>");
                yield new DataAnonymizer().anonymize(path(args[1]), path(args[2]), path(args[3]));
            }
            case "contract-test" -> {
                require(args, 2, "contract-test <contrato.json>");
                yield new ContractTester().execute(path(args[1]));
            }
            case "gatling" -> {
                require(args, 3, "gatling <especificação.json> <Simulation.java>");
                yield new GatlingScenarioGenerator().generate(path(args[1]), path(args[2]));
            }
            case "debt" -> {
                require(args, 2, "debt <diretório>");
                yield new TechnicalDebtTracker().scan(path(args[1]));
            }
            case "migrate" -> {
                require(args, 9, "migrate <src-url> <src-user> <src-pass-env> <src-table> <dst-url> <dst-user> <dst-pass-env> <dst-table> [batch]");
                String sourcePassword = env(args[3]);
                String targetPassword = env(args[7]);
                int batch = args.length > 9 ? Integer.parseInt(args[9]) : 500;
                yield new DatabaseMigrator().migrate(new DatabaseMigrator.Config(
                    args[1], args[2], sourcePassword, args[4], args[5], args[6], targetPassword, args[8], batch));
            }
            case "vuln-server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : 8081;
                Path database = path(args.length > 2 ? args[2] : "data/vulnerabilities.db");
                var server = new VulnerabilityServer(port, database); server.start();
                System.out.println("Vulnerability API em http://127.0.0.1:" + server.port());
                new CountDownLatch(1).await(); yield null;
            }
            case "itam-server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : 8082;
                Path database = path(args.length > 2 ? args[2] : "data/assets.db");
                var server = new AssetServer(port, database); server.start();
                System.out.println("ITAM API em http://127.0.0.1:" + server.port());
                new CountDownLatch(1).await(); yield null;
            }
            default -> throw new IllegalArgumentException("Comando desconhecido: " + args[0]);
        };
    }

    private static Path path(String value) { return Path.of(value).toAbsolutePath().normalize(); }
    private static String env(String name) {
        var value = System.getenv(name);
        if (value == null) throw new IllegalArgumentException("Variável de ambiente ausente: " + name);
        return value;
    }
    private static void require(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("Uso: swissknife " + usage);
    }
    private static void help() {
        System.out.println("""
            SwissKnife Javanist
            Uso: swissknife <comando> [argumentos]

              docs           Gera documentação Markdown de código Java
              deps           Gera grafo Mermaid de microsserviços
              slow-query     Analisa SQL e sugere índices
              schema-diff    Compara dois arquivos DDL
              anonymize      Anonimiza dados CSV
              contract-test  Valida um contrato HTTP JSON
              gatling        Gera uma Simulation Gatling
              debt           Localiza débito técnico
              migrate        Migra uma tabela entre conexões JDBC
              vuln-server    Inicia API de vulnerabilidades
              itam-server    Inicia API de ativos de TI
            """);
    }
}
