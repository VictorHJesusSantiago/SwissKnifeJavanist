package dev.swissknife;

import dev.swissknife.anonymize.*;
import dev.swissknife.ai.*;
import dev.swissknife.contract.*;
import dev.swissknife.cli.*;
import dev.swissknife.debt.*;
import dev.swissknife.dependencies.*;
import dev.swissknife.diagnostics.*;
import dev.swissknife.docs.*;
import dev.swissknife.governance.*;
import dev.swissknife.itam.*;
import dev.swissknife.integrations.*;
import dev.swissknife.load.*;
import dev.swissknife.management.*;
import dev.swissknife.migration.*;
import dev.swissknife.portal.*;
import dev.swissknife.schema.*;
import dev.swissknife.server.*;
import dev.swissknife.sql.*;
import dev.swissknife.util.*;
import dev.swissknife.vulnerability.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) {
        try {
            var invocation = Invocation.parse(args);
            if (invocation.help() || invocation.arguments().length == 0) { help(); return; }
            var config = CliConfig.load(Path.of("").toAbsolutePath(), invocation.config(),
                invocation.profile(), invocation.overrides());
            String[] effective = invocation.arguments();
            effective[0] = config.alias(effective[0]);
            Object result = executeWithPlatform(effective, config);
            telemetry(config, effective, result);
            if (result != null && !invocation.quiet()) {
                String format = invocation.format() == null
                    ? config.get("output.format", "json") : invocation.format();
                String rendered = OutputFormatter.format(result, format);
                if (invocation.output() == null || invocation.output().toString().equals("-"))
                    System.out.println(rendered);
                else FilesEx.write(invocation.output(), rendered);
            }
            if (!invocation.noFail()) {
                int exitCode = PolicyEvaluator.evaluate(result, config).exitCode();
                if (exitCode != 0) System.exit(exitCode);
            }
        } catch (Exception e) {
            System.err.println("Erro: " + e.getMessage());
            if (System.getenv("SWISSKNIFE_DEBUG") != null ||
                Arrays.asList(args).contains("--debug")) e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Object executeWithPlatform(String[] args, CliConfig config) throws Exception {
        return switch (args[0]) {
            case "version" -> Map.of("name", "SwissKnife Javanist", "version", version(),
                "java", Runtime.version().toString());
            case "init" -> {
                Path target = path(args.length > 1 && !args[1].startsWith("--") ? args[1] : ".swissknife.yml");
                boolean force = Arrays.asList(args).contains("--force");
                yield Map.of("created", CliConfig.initialize(target, force), "profile", "development");
            }
            case "doctor" -> CliTools.doctor(config, Path.of("").toAbsolutePath());
            case "cache-status" -> cache(config).status();
            case "cache-clear" -> Map.of("cleared", cache(config).clear(), "directory", cache(config).status().directory());
            case "completion" -> {
                require(args, 2, "completion <bash|zsh|fish|powershell>");
                yield Map.of("shell", args[1], "script", CliTools.completion(args[1]));
            }
            case "pipeline" -> {
                require(args, 2, "pipeline <arquivo>");
                List<Map<String, Object>> results = new ArrayList<>();
                long started = System.nanoTime();
                for (var command : CliTools.pipeline(path(args[1]))) {
                    long stepStarted = System.nanoTime();
                    try {
                        Object output = executeWithPlatform(command.toArray(String[]::new), config);
                        results.add(new LinkedHashMap<>(Map.of("command", command, "success", true,
                            "durationMs", (System.nanoTime() - stepStarted) / 1_000_000, "result", output)));
                    } catch (Exception e) {
                        results.add(new LinkedHashMap<>(Map.of("command", command, "success", false,
                            "durationMs", (System.nanoTime() - stepStarted) / 1_000_000, "error", e.getMessage())));
                        if (!config.bool("pipeline.continueOnError", false)) break;
                    }
                }
                yield Map.of("success", results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success"))),
                    "durationMs", (System.nanoTime() - started) / 1_000_000, "steps", results);
            }
            default -> cachedExecute(args, config);
        };
    }

    private static Object cachedExecute(String[] args, CliConfig config) throws Exception {
        Set<String> cacheable = Set.of("slow-query", "slow-query-file", "slow-query-log", "schema-diff",
            "debt", "dependency-audit", "sbom", "quality", "security-scan", "spring-audit",
            "modernize", "jvm-diagnose", "detect-pii");
        if (!config.bool("cache.enabled", true) || !cacheable.contains(args[0])) return execute(args);
        List<Path> inputs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            try { Path candidate = path(args[i]); if (Files.exists(candidate)) inputs.add(candidate); }
            catch (Exception ignored) {}
        }
        CliCache cache = cache(config);
        String key = cache.key(Arrays.asList(args), inputs);
        var cached = cache.get(key, java.time.Duration.ofHours(config.integer("cache.ttlHours", 24)));
        if (cached.isPresent()) return cached.get();
        Object result = execute(args);
        cache.put(key, result);
        return result;
    }

    private static CliCache cache(CliConfig config) {
        return new CliCache(path(config.get("cache.directory", ".swissknife/cache")));
    }

    private static void telemetry(CliConfig config, String[] args, Object result) {
        if (!config.bool("telemetry.enabled", false) || args.length == 0) return;
        try {
            Path file = path(config.get("telemetry.file", ".swissknife/telemetry.jsonl"));
            Path parent = file.getParent(); if (parent != null) Files.createDirectories(parent);
            String event = Json.stringify(Map.of("command", args[0], "success", true,
                "timestamp", java.time.Instant.now().toString(), "java", Runtime.version().feature())) + System.lineSeparator();
            Files.writeString(file, event, java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { /* telemetria opt-in nunca deve quebrar o comando */ }
    }

    public static Object execute(String[] args) throws Exception {
        return switch (args[0]) {
            case "docs" -> {
                require(args, 3, "docs <diretório-fonte> <arquivo-saída>");
                yield new DocumentationGenerator().generate(path(args[1]), path(args[2]));
            }
            case "docs-site" -> {
                require(args, 3, "docs-site <diretório-fonte> <diretório-saída>");
                yield new DocumentationGenerator().generateSite(path(args[1]), path(args[2]));
            }
            case "docs-diff" -> {
                require(args, 3, "docs-diff <fontes-anteriores> <fontes-atuais>");
                yield new DocumentationGenerator().compare(path(args[1]), path(args[2]));
            }
            case "deps" -> {
                require(args, 3, "deps <diretório-serviços> <arquivo.mmd>");
                var visualizer = new DependencyVisualizer();
                var graph = visualizer.analyze(path(args[1]));
                visualizer.writeMermaid(graph, path(args[2]));
                yield Map.of("services", graph.services().size(), "dependencies", graph.edges().size(),
                    "cycles", graph.cycles().size(), "unknown", graph.unknownServices().size(),
                    "criticalPath", graph.criticalPathLength(), "output", args[2]);
            }
            case "deps-export" -> {
                require(args, 4, "deps-export <diretório> <saída> <mermaid|plantuml|dot|json|html>");
                var visualizer = new DependencyVisualizer();
                var graph = visualizer.analyze(path(args[1]));
                visualizer.write(graph, path(args[2]), args[3]);
                yield Map.of("services", graph.services().size(), "dependencies", graph.edges().size(),
                    "format", args[3], "output", args[2], "analysis", graph);
            }
            case "slow-query" -> {
                require(args, 2, "slow-query <sql>");
                yield new SlowQueryAnalyzer().analyze(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "slow-query-file" -> {
                require(args, 2, "slow-query-file <arquivo.sql>");
                yield new SlowQueryAnalyzer().analyzeBatch(Files.readString(path(args[1])));
            }
            case "slow-query-log" -> {
                require(args, 2, "slow-query-log <arquivo.log>");
                yield new SlowQueryAnalyzer().analyzeLog(Files.readString(path(args[1])));
            }
            case "schema-diff" -> {
                require(args, 3, "schema-diff <schema-desejado.sql> <schema-atual.sql>");
                var comparator = new SchemaComparator();
                yield comparator.compare(comparator.parse(path(args[1])), comparator.parse(path(args[2])));
            }
            case "schema-script" -> {
                require(args, 5, "schema-script <desejado.sql> <atual.sql> <saída> <migration|rollback|flyway|liquibase>");
                var comparator = new SchemaComparator();
                var diff = comparator.compare(comparator.parse(path(args[1])), comparator.parse(path(args[2])));
                String content = switch (args[4].toLowerCase(Locale.ROOT)) {
                    case "migration" -> diff.migrationSql();
                    case "rollback" -> diff.rollbackSql();
                    case "flyway" -> comparator.flyway(diff, option(args, "--description", "schema_sync"));
                    case "liquibase" -> comparator.liquibase(diff, option(args, "--id", "schema-sync"),
                        option(args, "--author", "swissknife"));
                    default -> throw new IllegalArgumentException("Formato inválido: " + args[4]);
                };
                FilesEx.write(path(args[3]), content);
                yield Map.of("output", path(args[3]), "format", args[4], "changes", diff.changes().size(),
                    "destructive", diff.destructiveChanges());
            }
            case "anonymize" -> {
                require(args, 4, "anonymize <entrada.csv> <política.properties> <saída.csv>");
                yield new DataAnonymizer().anonymize(path(args[1]), path(args[2]), path(args[3]));
            }
            case "anonymize-json" -> {
                require(args, 4, "anonymize-json <entrada.json> <política.properties> <saída.json>");
                yield new DataAnonymizer().anonymizeJson(path(args[1]), path(args[2]), path(args[3]));
            }
            case "detect-pii" -> {
                require(args, 2, "detect-pii <entrada.csv>");
                yield new DataAnonymizer().detect(path(args[1]));
            }
            case "contract-test" -> {
                require(args, 2, "contract-test <contrato.json>");
                yield new ContractTester().executeAny(path(args[1]));
            }
            case "gatling" -> {
                require(args, 3, "gatling <especificação.json> <Simulation.java>");
                yield new GatlingScenarioGenerator().generate(path(args[1]), path(args[2]));
            }
            case "gatling-project" -> {
                require(args, 3, "gatling-project <especificação.json> <diretório>");
                yield new GatlingScenarioGenerator().generateProject(path(args[1]), path(args[2]));
            }
            case "debt" -> {
                require(args, 2, "debt <diretório>");
                yield new TechnicalDebtTracker().scan(path(args[1]));
            }
            case "dependency-audit", "sbom" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                Set<String> licenses = optionSet(args, "--allowed-licenses");
                Set<String> repositories = optionSet(args, "--allowed-repositories");
                yield new DependencyAuditor().analyze(root, licenses, repositories);
            }
            case "quality" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                yield new JavaQualityAnalyzer().analyze(root);
            }
            case "security-scan" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                yield new SecurityScanner().scan(root);
            }
            case "spring-audit" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                yield new SpringGovernanceAnalyzer().analyze(root);
            }
            case "test-audit" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                yield new DevelopmentGovernanceAnalyzer().tests(root);
            }
            case "config-audit" -> {
                require(args, 2, "config-audit <diretório-1> [diretório-2]");
                yield new DevelopmentGovernanceAnalyzer().configurations(path(args[1]),
                    args.length>2?path(args[2]):null);
            }
            case "release-readiness" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                yield new DevelopmentGovernanceAnalyzer().release(root);
            }
            case "modernize" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                int target = Integer.parseInt(option(args, "--target", "21"));
                yield new ModernizationAnalyzer().analyze(root, target);
            }
            case "jvm-diagnose" -> {
                require(args, 2, "jvm-diagnose <thread-dump|gc-log|application-log>");
                yield new JvmDiagnostics().analyze(path(args[1]));
            }
            case "integrate" -> {
                require(args, 3, "integrate <config.properties> <payload.json> [--send]");
                boolean send = Arrays.asList(args).contains("--send");
                yield new IntegrationDispatcher().dispatch(path(args[1]), path(args[2]), !send);
            }
            case "ai-assist" -> {
                require(args, 4, "ai-assist <config.properties> <tarefa> <entrada> [--send --consent]");
                boolean send=Arrays.asList(args).contains("--send"),consent=Arrays.asList(args).contains("--consent");
                yield new AiAssistant().execute(path(args[1]),args[2],path(args[3]),send,consent);
            }
            case "vuln-import" -> {
                require(args, 4, "vuln-import <database.db> <arquivo> <sarif|cyclonedx|semgrep|generic>");
                yield new GovernanceDataManager().importVulnerabilities(path(args[1]), path(args[2]), args[3]);
            }
            case "vuln-transition" -> {
                require(args, 5, "vuln-transition <database.db> <id> <status> <ator> [comentário]");
                yield new GovernanceDataManager().vulnerabilityTransition(path(args[1]),args[2],args[3],args[4],
                    args.length>5?String.join(" ",Arrays.copyOfRange(args,5,args.length)):"");
            }
            case "vuln-report" -> {
                require(args, 2, "vuln-report <database.db>");
                yield new GovernanceDataManager().vulnerabilityReport(path(args[1]));
            }
            case "itam-import" -> {
                require(args, 3, "itam-import <database.db> <assets.csv>");
                yield new GovernanceDataManager().importAssets(path(args[1]),path(args[2]));
            }
            case "itam-transition" -> {
                require(args, 5, "itam-transition <database.db> <id> <ação> <ator> [detalhes]");
                yield new GovernanceDataManager().assetTransition(path(args[1]),args[2],args[3],args[4],
                    args.length>5?String.join(" ",Arrays.copyOfRange(args,5,args.length)):"");
            }
            case "itam-report" -> {
                require(args, 2, "itam-report <database.db>");
                yield new GovernanceDataManager().assetReport(path(args[1]));
            }
            case "migrate" -> {
                require(args, 9, "migrate <src-url> <src-user> <src-pass-env> <src-table> <dst-url> <dst-user> <dst-pass-env> <dst-table> [batch]");
                String sourcePassword = env(args[3]);
                String targetPassword = env(args[7]);
                int batch = args.length > 9 ? Integer.parseInt(args[9]) : 500;
                yield new DatabaseMigrator().migrate(new DatabaseMigrator.Config(
                    args[1], args[2], sourcePassword, args[4], args[5], args[6], targetPassword, args[8], batch));
            }
            case "migrate-plan" -> {
                require(args, 9, "migrate-plan <src-url> <src-user> <src-pass-env> <src-table> <dst-url> <dst-user> <dst-pass-env> <dst-table>");
                yield new DatabaseMigrator().plan(new DatabaseMigrator.Config(args[1], args[2], env(args[3]), args[4],
                    args[5], args[6], env(args[7]), args[8], 500));
            }
            case "migrate-config" -> {
                require(args, 2, "migrate-config <arquivo.properties>");
                var migrator = new DatabaseMigrator();
                yield migrator.migrate(migrator.load(path(args[1])));
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
            case "portal-server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
                String vuln = option(args, "--vulnerability-url", "http://127.0.0.1:8081");
                String itam = option(args, "--itam-url", "http://127.0.0.1:8082");
                var server = new PortalServer(port, vuln, itam); server.start();
                System.out.println("Portal em http://127.0.0.1:" + server.port());
                new CountDownLatch(1).await(); yield null;
            }
            case "store-admin" -> {
                require(args, 3, "store-admin <arquivo.db> <verify|backup|restore|compact|audit> [destino]");
                var store = new JsonStore(path(args[1]));
                yield switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "verify" -> store.verify();
                    case "backup" -> {
                        require(args, 4, "store-admin <arquivo.db> backup <destino>");
                        yield store.backup(path(args[3]));
                    }
                    case "restore" -> {
                        require(args, 4, "store-admin <arquivo.db> restore <origem> --confirm");
                        if (!Arrays.asList(args).contains("--confirm"))
                            throw new IllegalArgumentException("Restore exige --confirm");
                        yield store.restore(path(args[3]));
                    }
                    case "compact" -> store.compact();
                    case "audit" -> store.auditTrail(Integer.parseInt(option(args, "--limit", "100")));
                    default -> throw new IllegalArgumentException("Operação de store desconhecida: " + args[2]);
                };
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
    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return fallback;
    }
    private static Set<String> optionSet(String[] args, String name) {
        String value = option(args, name, "");
        if (value.isBlank()) return Set.of();
        return new TreeSet<>(Arrays.stream(value.split(",")).map(String::trim)
            .filter(v -> !v.isBlank()).toList());
    }
    private static void help() {
        System.out.println("""
            SwissKnife Javanist
            Uso: swissknife <comando> [argumentos]

              init           Cria .swissknife.yml
              doctor         Diagnostica o ambiente e a configuração
              cache-status   Exibe uso do cache incremental
              cache-clear    Limpa entradas geradas pelo cache
              version        Exibe versões da suíte e do Java
              completion     Gera autocompletar do shell
              pipeline       Executa uma sequência de comandos
              docs           Gera documentação Markdown de código Java
              docs-site      Gera site HTML pesquisável e índice
              docs-diff      Detecta breaking changes na API Java
              deps           Gera grafo Mermaid de microsserviços
              deps-export    Exporta arquitetura em Mermaid/PlantUML/DOT/JSON/HTML
              slow-query     Analisa SQL e sugere índices
              slow-query-file Analisa lote de comandos SQL
              slow-query-log Extrai e analisa SQL de logs
              schema-diff    Compara dois arquivos DDL
              schema-script  Gera migration/rollback/Flyway/Liquibase
              anonymize      Anonimiza dados CSV
              anonymize-json Anonimiza documentos JSON
              detect-pii     Detecta possíveis dados pessoais em CSV
              contract-test  Valida um contrato HTTP JSON
              gatling        Gera uma Simulation Gatling
              gatling-project Gera projeto Maven Gatling executável
              debt           Localiza débito técnico
              dependency-audit Inventaria dependências e gera SBOM
              quality        Analisa qualidade e arquitetura Java
              security-scan  Localiza segredos e configurações inseguras
              spring-audit   Cataloga e valida aplicações Spring
              test-audit     Detecta testes frágeis, lentos e sem assertions
              config-audit   Compara ambientes e verifica configuração
              release-readiness Consolida quality gates de uma release
              modernize      Planeja modernização de Java
              jvm-diagnose   Analisa thread dumps, GC e logs
              integrate      Envia findings a webhooks e plataformas externas
              ai-assist      Explica/prioriza findings com IA opcional e redigida
              vuln-import    Importa SARIF/CycloneDX/Semgrep com deduplicação
              vuln-transition Aplica workflow e histórico de vulnerabilidade
              vuln-report    Gera aging, SLA e MTTR de vulnerabilidades
              itam-import    Importa e reconcilia inventário CSV
              itam-transition Registra checkout/devolução/manutenção/descarte
              itam-report    Gera indicadores financeiros e operacionais
              migrate        Migra uma tabela entre conexões JDBC
              migrate-plan   Valida compatibilidade e volume antes da migração
              migrate-config Executa migração avançada configurada
              vuln-server    Inicia API de vulnerabilidades
              itam-server    Inicia API de ativos de TI
              portal-server  Inicia o portal web local
              store-admin    Verifica, copia, restaura e compacta JSON store

            Opções globais:
              --config <arquivo>  Configuração YAML explícita
              --profile <nome>    Perfil de configuração
              --format <formato>  json, text, yaml, csv, xml, html, markdown, sarif, junit
              --output <arquivo>  Grava a resposta; use - para stdout
              --quiet             Não imprime a resposta
              --no-fail           Não altera o código de saída por findings/políticas
              --verbose           Exibe informações adicionais
              --debug             Exibe stack traces
            """);
    }

    private static String version() {
        var implementation = Main.class.getPackage().getImplementationVersion();
        return implementation == null ? "2.0.0-dev" : implementation;
    }

    private record Invocation(String[] arguments, Path config, String profile, String format,
                              Path output, boolean quiet, boolean verbose, boolean help, boolean noFail,
                              Map<String, String> overrides) {
        static Invocation parse(String[] raw) {
            List<String> positional = new ArrayList<>();
            Map<String, String> overrides = new LinkedHashMap<>();
            Path config = null, output = null;
            String profile = null, format = null;
            boolean quiet = false, verbose = false, help = false, noFail = false;
            for (int i = 0; i < raw.length; i++) {
                String arg = raw[i];
                switch (arg) {
                    case "--config" -> config = path(value(raw, ++i, arg));
                    case "--profile" -> profile = value(raw, ++i, arg);
                    case "--format" -> format = value(raw, ++i, arg);
                    case "--output" -> {
                        String value = value(raw, ++i, arg);
                        output = value.equals("-") ? Path.of("-") : path(value);
                    }
                    case "--quiet" -> quiet = true;
                    case "--verbose" -> verbose = true;
                    case "--no-fail" -> noFail = true;
                    case "--debug", "--dry-run", "--force" -> positional.add(arg);
                    case "--help", "-h" -> help = true;
                    case "--version" -> positional.add("version");
                    case "--set" -> {
                        String pair = value(raw, ++i, arg);
                        int equals = pair.indexOf('=');
                        if (equals < 1) throw new IllegalArgumentException("--set exige chave=valor");
                        overrides.put(pair.substring(0, equals), pair.substring(equals + 1));
                    }
                    default -> positional.add(arg);
                }
            }
            return new Invocation(positional.toArray(String[]::new), config, profile, format,
                output, quiet, verbose, help, noFail, overrides);
        }
        private static String value(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException("Valor ausente para " + option);
            return args[index];
        }
    }
}
