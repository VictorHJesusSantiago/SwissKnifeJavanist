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
import dev.swissknife.testing.*;
import dev.swissknife.util.*;
import dev.swissknife.vulnerability.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) {
        boolean watch = Arrays.asList(args).contains("--watch");
        if (!watch) { runOnce(args, true); return; }
        String[] cleaned = Arrays.stream(args).filter(a -> !a.equals("--watch")).toArray(String[]::new);
        watchLoop(cleaned);
    }

    /** Executa o comando repetidamente sempre que arquivos sob o alvo mudarem, até Ctrl+C. */
    private static void watchLoop(String[] args) {
        Path target = watchTarget(args);
        System.out.println("[watch] observando " + target + " (Ctrl+C para sair)");
        runOnce(args, false);
        try (var watcher = java.nio.file.FileSystems.getDefault().newWatchService()) {
            Map<java.nio.file.WatchKey, Path> keys = new HashMap<>();
            registerRecursively(target, watcher, keys);
            while (true) {
                var key = watcher.take();
                boolean relevant = false;
                for (var event : key.pollEvents()) if (event.kind() != java.nio.file.StandardWatchEventKinds.OVERFLOW) relevant = true;
                key.reset();
                if (!relevant) continue;
                Thread.sleep(300);
                while (watcher.poll() != null) { /* drena rajada de eventos consecutivos */ }
                System.out.println("\n[watch] mudança detectada, reexecutando " + String.join(" ", args) + "...");
                runOnce(args, false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[watch] erro: " + e.getMessage());
            System.exit(1);
        }
    }
    private static void registerRecursively(Path root, java.nio.file.WatchService watcher,
                                            Map<java.nio.file.WatchKey, Path> keys) throws java.io.IOException {
        if (!Files.isDirectory(root)) { root = root.getParent() == null ? Path.of(".") : root.getParent(); }
        try (var stream = Files.walk(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                if (dir.toString().contains(".git") || dir.toString().contains(".swissknife")) continue;
                keys.put(dir.register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY, java.nio.file.StandardWatchEventKinds.ENTRY_DELETE), dir);
            }
        }
    }
    private static Path watchTarget(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) { i++; continue; }
            try { Path candidate = Path.of(args[i]).toAbsolutePath(); if (Files.exists(candidate)) return candidate; }
            catch (Exception ignored) {}
        }
        return Path.of("").toAbsolutePath();
    }

    private static void runOnce(String[] args, boolean exitOnFailure) {
        long started = System.nanoTime();
        try {
            var invocation = Invocation.parse(args);
            if (invocation.help() || invocation.arguments().length == 0) { help(); return; }
            var config = CliConfig.load(Path.of("").toAbsolutePath(), invocation.config(),
                invocation.profile(), invocation.overrides());
            String[] effective = invocation.arguments();
            effective[0] = config.alias(effective[0]);
            if (invocation.verbose())
                System.err.println("[verbose] executando: " + String.join(" ", effective));
            Object result = executeWithPlatform(effective, config);
            telemetry(config, effective, result);
            if (result != null && !invocation.quiet()) {
                String format = invocation.format() == null
                    ? config.get("output.format", "json") : invocation.format();
                String rendered = OutputFormatter.format(result, format);
                boolean colorable = Set.of("text", "txt", "yaml", "yml").contains(format.toLowerCase(Locale.ROOT));
                if (colorable) rendered = Ansi.colorize(rendered, Ansi.enabled(config));
                if (invocation.output() == null || invocation.output().toString().equals("-"))
                    System.out.println(rendered);
                else FilesEx.write(invocation.output(), rendered);
            }
            if (invocation.verbose())
                System.err.println("[verbose] concluído em " + ((System.nanoTime() - started) / 1_000_000) + "ms");
            if (!invocation.noFail()) {
                int exitCode = PolicyEvaluator.evaluate(result, config).exitCode();
                if (exitCode != 0 && exitOnFailure) System.exit(exitCode);
            }
        } catch (Exception e) {
            String suggestion = suggestForUnknownCommand(e.getMessage());
            System.err.println("Erro: " + e.getMessage() + (suggestion == null ? "" :
                " (você quis dizer \"" + suggestion + "\"?)"));
            if (System.getenv("SWISSKNIFE_DEBUG") != null ||
                Arrays.asList(args).contains("--debug")) e.printStackTrace(System.err);
            if (exitOnFailure) System.exit(1);
        }
    }

    private static String suggestForUnknownCommand(String message) {
        if (message == null || !message.startsWith("Comando desconhecido: ")) return null;
        String typed = message.substring("Comando desconhecido: ".length()).strip();
        return CommandCatalog.suggest(typed);
    }

    private static Object executeWithPlatform(String[] args, CliConfig config) throws Exception {
        return switch (args[0]) {
            case "mfa-setup" -> {
                require(args, 2, "mfa-setup <conta> [emissor]");
                String secret = Totp.generateSecret();
                yield Map.of("secret", secret, "account", args[1],
                    "provisioningUri", Totp.provisioningUri(secret, args[1], args.length > 2 ? args[2] : "SwissKnife"));
            }
            case "mfa-verify" -> {
                require(args, 3, "mfa-verify <segredo> <codigo>");
                yield Map.of("valid", Totp.verify(args[1], args[2]));
            }
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
                require(args, 2, "pipeline <arquivo> [--parallel]");
                long started = System.nanoTime();
                List<List<String>> commands = CliTools.pipeline(path(args[1]));
                List<Map<String, Object>> results;
                if (Arrays.asList(args).contains("--parallel")) {
                    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                    List<java.util.concurrent.Future<Map<String, Object>>> futures = new ArrayList<>();
                    for (var command : commands) futures.add(executor.submit(() -> runPipelineStep(command, config)));
                    results = new ArrayList<>();
                    for (var future : futures) results.add(future.get());
                    executor.shutdown();
                } else {
                    results = new ArrayList<>();
                    for (var command : commands) {
                        Map<String, Object> stepResult = runPipelineStep(command, config);
                        results.add(stepResult);
                        if (!Boolean.TRUE.equals(stepResult.get("success")) && !config.bool("pipeline.continueOnError", false)) break;
                    }
                }
                yield Map.of("success", results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success"))),
                    "durationMs", (System.nanoTime() - started) / 1_000_000, "steps", results);
            }
            default -> cachedExecute(args, config);
        };
    }

    private static Map<String, Object> runPipelineStep(List<String> command, CliConfig config) {
        long stepStarted = System.nanoTime();
        try {
            Object output = executeWithPlatform(command.toArray(String[]::new), config);
            return new LinkedHashMap<>(Map.of("command", command, "success", true,
                "durationMs", (System.nanoTime() - stepStarted) / 1_000_000, "result", output));
        } catch (Exception e) {
            return new LinkedHashMap<>(Map.of("command", command, "success", false,
                "durationMs", (System.nanoTime() - stepStarted) / 1_000_000, "error", String.valueOf(e.getMessage())));
        }
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
                require(args, 3, "docs <diretório-fonte> <arquivo-saída> [--include-private]");
                boolean includePrivate = Arrays.asList(args).contains("--include-private");
                yield new DocumentationGenerator().generate(path(args[1]), path(args[2]), includePrivate);
            }
            case "docs-site" -> {
                require(args, 3, "docs-site <diretório-fonte> <diretório-saída> [--include-private]");
                boolean includePrivate = Arrays.asList(args).contains("--include-private");
                yield new DocumentationGenerator().generateSite(path(args[1]), path(args[2]), includePrivate);
            }
            case "docs-diff" -> {
                require(args, 3, "docs-diff <fontes-anteriores> <fontes-atuais>");
                yield new DocumentationGenerator().compare(path(args[1]), path(args[2]));
            }
            case "docs-uml" -> {
                require(args, 3, "docs-uml <diretório-fonte> <saída.mmd>");
                var generator = new DocumentationGenerator();
                FilesEx.write(path(args[2]), generator.umlClassDiagram(path(args[1])));
                yield Map.of("output", path(args[2]));
            }
            case "docs-package-diagram" -> {
                require(args, 3, "docs-package-diagram <diretório-fonte> <saída.mmd>");
                var generator = new DocumentationGenerator();
                FilesEx.write(path(args[2]), generator.packageDiagram(path(args[1])));
                yield Map.of("output", path(args[2]));
            }
            case "docs-mkdocs" -> {
                require(args, 3, "docs-mkdocs <diretório-fonte> <diretório-saída>");
                yield Map.of("output", new DocumentationGenerator().exportMkDocs(path(args[1]), path(args[2])));
            }
            case "docs-changelog" -> {
                require(args, 4, "docs-changelog <fontes-anteriores> <fontes-atuais> <saída.md>");
                var generator = new DocumentationGenerator();
                var diff = generator.compare(path(args[1]), path(args[2]));
                String content = generator.changelog(diff, option(args, "--from", args[1]), option(args, "--to", args[2]));
                FilesEx.write(path(args[3]), content);
                yield Map.of("output", path(args[3]), "changes", diff.changes().size(), "breaking", diff.breaking());
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
            case "deps-rules" -> {
                require(args, 3, "deps-rules <diretório-serviços> <regras.properties>");
                var visualizer = new DependencyVisualizer();
                var graph = visualizer.analyze(path(args[1]));
                var rules = DependencyVisualizer.loadDomainRules(path(args[2]));
                var violations = visualizer.validateDomainRules(graph, rules);
                yield Map.of("rules", rules.size(), "violations", violations, "passed", violations.isEmpty());
            }
            case "deps-timeline" -> {
                require(args, 4, "deps-timeline <saída.html> <rotulo1=diretorio1> <rotulo2=diretorio2> [...]");
                var visualizer = new DependencyVisualizer();
                List<String> labels = new ArrayList<>(); List<DependencyVisualizer.Graph> graphs = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    int eq = args[i].indexOf('=');
                    if (eq < 0) throw new IllegalArgumentException("Formato esperado rotulo=diretorio: " + args[i]);
                    labels.add(args[i].substring(0, eq));
                    graphs.add(visualizer.analyze(path(args[i].substring(eq + 1))));
                }
                FilesEx.write(path(args[1]), visualizer.timeline(labels, graphs));
                yield Map.of("output", path(args[1]), "snapshots", graphs.size());
            }
            case "deps-compare" -> {
                require(args, 3, "deps-compare <diretório-anterior> <diretório-atual>");
                var visualizer = new DependencyVisualizer();
                yield visualizer.compareGraphs(visualizer.analyze(path(args[1])), visualizer.analyze(path(args[2])));
            }
            case "deps-export" -> {
                require(args, 4, "deps-export <diretório> <saída> <mermaid|plantuml|dot|json|html>");
                var visualizer = new DependencyVisualizer();
                var graph = visualizer.analyze(path(args[1]));
                visualizer.write(graph, path(args[2]), args[3]);
                yield Map.of("services", graph.services().size(), "dependencies", graph.edges().size(),
                    "format", args[3], "output", args[2], "analysis", graph);
            }
            case "sql-parse" -> {
                require(args, 2, "sql-parse <sql>");
                yield SqlParser.parseSelect(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
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
            case "slow-query-report" -> {
                require(args, 3, "slow-query-report <arquivo.sql> <saída.html>");
                var analyzer = new SlowQueryAnalyzer();
                var batch = analyzer.analyzeBatch(Files.readString(path(args[1])));
                FilesEx.write(path(args[2]), analyzer.html(batch));
                yield Map.of("output", path(args[2]), "statements", batch.statements(),
                    "maximumRisk", batch.maximumRisk());
            }
            case "slow-query-index-script" -> {
                require(args, 3, "slow-query-index-script <arquivo.sql> <saída.sql>");
                var analyzer = new SlowQueryAnalyzer();
                var batch = analyzer.analyzeBatch(Files.readString(path(args[1])));
                String script = analyzer.indexMigrationScript(batch);
                FilesEx.write(path(args[2]), script);
                yield Map.of("output", path(args[2]), "statements", batch.statements());
            }
            case "slow-query-plan" -> {
                require(args, 3, "slow-query-plan <dialeto> <arquivo-plano>");
                var dialect = ExplainPlanAnalyzer.Dialect.valueOf(args[1].toUpperCase(Locale.ROOT));
                yield new ExplainPlanAnalyzer().analyzePlan(Files.readString(path(args[2])), dialect);
            }
            case "slow-query-explain" -> {
                require(args, 7, "slow-query-explain <dialeto> <jdbc-url> <usuario> <senha-env> <analyze:true|false> <sql>");
                var dialect = ExplainPlanAnalyzer.Dialect.valueOf(args[1].toUpperCase(Locale.ROOT));
                var config = new ExplainPlanAnalyzer.Config(args[2], args[3], env(args[4]), dialect,
                    Boolean.parseBoolean(args[5]), 15);
                yield new ExplainPlanAnalyzer().explain(config,
                    String.join(" ", Arrays.copyOfRange(args, 6, args.length)));
            }
            case "schema-introspect" -> {
                require(args, 3, "schema-introspect <jdbc-url> <usuario> [senha-env]");
                String password = args.length > 3 ? env(args[3]) : "";
                try (Connection connection = DriverManager.getConnection(args[1], args[2], password)) {
                    yield new SchemaComparator().introspect(connection);
                }
            }
            case "schema-diff-live" -> {
                require(args, 5, "schema-diff-live <schema-desejado.sql> <jdbc-url> <usuario> <senha-env> [--ignore tabela,...]");
                String password = env(args[4]);
                var comparator = new SchemaComparator();
                Set<String> ignored = optionSet(args, "--ignore");
                try (Connection connection = DriverManager.getConnection(args[2], args[3], password)) {
                    yield comparator.compare(comparator.parse(path(args[1])), comparator.introspect(connection), ignored);
                }
            }
            case "schema-diff" -> {
                require(args, 3, "schema-diff <schema-desejado.sql> <schema-atual.sql> [--ignore tabela,tabela.coluna]");
                var comparator = new SchemaComparator();
                Set<String> ignored = optionSet(args, "--ignore");
                yield comparator.compare(comparator.parse(path(args[1])), comparator.parse(path(args[2])), ignored);
            }
            case "schema-diff-html" -> {
                require(args, 4, "schema-diff-html <schema-desejado.sql> <schema-atual.sql> <saída.html> [--ignore tabela,tabela.coluna]");
                var comparator = new SchemaComparator();
                Set<String> ignored = optionSet(args, "--ignore");
                var diff = comparator.compare(comparator.parse(path(args[1])), comparator.parse(path(args[2])), ignored);
                FilesEx.write(path(args[3]), comparator.html(diff));
                yield Map.of("output", path(args[3]), "changes", diff.changes().size(),
                    "destructive", diff.destructiveChanges(), "possibleRenames", diff.possibleRenames());
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
            case "anonymize-preview" -> {
                require(args, 3, "anonymize-preview <entrada.csv> <política.properties> [linhas]");
                int sampleRows = args.length > 3 ? Integer.parseInt(args[3]) : 5;
                yield Map.of("sample", new DataAnonymizer().preview(path(args[1]), path(args[2]), sampleRows));
            }
            case "detect-pii" -> {
                require(args, 2, "detect-pii <entrada.csv>");
                yield new DataAnonymizer().detect(path(args[1]));
            }
            case "contract-mock" -> {
                require(args, 2, "contract-mock <rotas.json> [porta]");
                int port = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                var mock = MockServer.start(path(args[1]), port);
                System.out.println("Mock server em http://127.0.0.1:" + mock.port());
                awaitShutdown("Mock server"); yield null;
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
            case "debt-report-html" -> {
                require(args, 3, "debt-report-html <diretório> <saída.html>");
                var tracker = new TechnicalDebtTracker();
                var report = tracker.scan(path(args[1]));
                FilesEx.write(path(args[2]), tracker.html(report));
                yield Map.of("output", path(args[2]), "items", report.items().size(), "score", report.score());
            }
            case "dependency-audit", "sbom" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                Set<String> licenses = optionSet(args, "--allowed-licenses");
                Set<String> repositories = optionSet(args, "--allowed-repositories");
                yield new DependencyAuditor().analyze(root, licenses, repositories);
            }
            case "dependency-lockfile" -> {
                require(args, 3, "dependency-lockfile <diretório> <saída>");
                var auditor = new DependencyAuditor();
                var report = auditor.analyze(path(args[1]), Set.of(), Set.of());
                FilesEx.write(path(args[2]), auditor.lockfile(report));
                yield Map.of("output", path(args[2]), "dependencies", report.dependencies().size());
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
            case "changed-files" -> {
                Path root = path(args.length > 1 ? args[1] : ".");
                var changed = FilesEx.changedFiles(root).stream().map(root::relativize).map(Path::toString).sorted().toList();
                yield Map.of("root", root, "files", changed, "count", changed.size());
            }
            case "test-scaffold" -> {
                require(args, 3, "test-scaffold <fonte.java> <saída-teste.java>");
                yield new TestProductivityAnalyzer().scaffold(path(args[1]), path(args[2]));
            }
            case "test-rank-slow" -> {
                require(args, 2, "test-rank-slow <relatorio-junit.xml> [top]");
                int top = args.length > 2 ? Integer.parseInt(args[2]) : 10;
                yield new TestProductivityAnalyzer().rankSlowTests(path(args[1]), top);
            }
            case "test-flaky-detect" -> {
                require(args, 3, "test-flaky-detect <relatorio1.xml> <relatorio2.xml> [relatorio3.xml ...]");
                List<Path> reports = new ArrayList<>();
                for (int i = 1; i < args.length; i++) reports.add(path(args[i]));
                yield new TestProductivityAnalyzer().detectFlaky(reports);
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
            case "jvm-diagnose-compare" -> {
                require(args, 3, "jvm-diagnose-compare <thread-dump-antes> <thread-dump-depois>");
                var diagnostics = new JvmDiagnostics();
                yield diagnostics.compareThreadDumps(diagnostics.analyze(path(args[1])), diagnostics.analyze(path(args[2])));
            }
            case "jvm-processes" -> new JvmDiagnostics().listJavaProcesses();
            case "jvm-bundle" -> {
                require(args, 3, "jvm-bundle <saída.zip> <arquivo1> [arquivo2 ...]");
                List<Path> files = new ArrayList<>();
                for (int i = 2; i < args.length; i++) files.add(path(args[i]));
                yield Map.of("output", new JvmDiagnostics().bundle(files, path(args[1])), "files", files.size());
            }
            case "integrate" -> {
                require(args, 3, "integrate <config.properties> <payload.json> [--send]");
                boolean send = Arrays.asList(args).contains("--send");
                yield new IntegrationDispatcher().dispatch(path(args[1]), path(args[2]), !send);
            }
            case "integrate-batch" -> {
                require(args, 3, "integrate-batch <config.properties> <findings.json> [--send]");
                boolean send = Arrays.asList(args).contains("--send");
                yield new IntegrationDispatcher().dispatchBatch(path(args[1]), path(args[2]), !send);
            }
            case "ai-assist" -> {
                require(args, 4, "ai-assist <config.properties> <tarefa> <entrada> [--send --consent] [--question \"...\"]");
                boolean send=Arrays.asList(args).contains("--send"),consent=Arrays.asList(args).contains("--consent");
                String question=option(args,"--question","");
                yield new AiAssistant().execute(path(args[1]),args[2],path(args[3]),send,consent,question);
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
            case "vuln-bulk-transition" -> {
                require(args, 5, "vuln-bulk-transition <database.db> <id1,id2,...> <status> <ator> [comentário]");
                yield new GovernanceDataManager().vulnerabilityBulkTransition(path(args[1]),
                    List.of(args[2].split(",")), args[3], args[4],
                    args.length > 5 ? String.join(" ", Arrays.copyOfRange(args, 5, args.length)) : "");
            }
            case "vuln-delete" -> {
                require(args, 4, "vuln-delete <database.db> <id> <ator>");
                yield new GovernanceDataManager().vulnerabilitySoftDelete(path(args[1]), args[2], args[3]);
            }
            case "vuln-restore" -> {
                require(args, 3, "vuln-restore <database.db> <id>");
                yield new GovernanceDataManager().vulnerabilityRestore(path(args[1]), args[2]);
            }
            case "vuln-review-accepted" -> {
                require(args, 2, "vuln-review-accepted <database.db>");
                yield new GovernanceDataManager().reviewAcceptedRisk(path(args[1]));
            }
            case "vuln-merge" -> {
                require(args, 5, "vuln-merge <database.db> <id-manter> <id-mesclar> <ator>");
                yield new GovernanceDataManager().vulnerabilityMerge(path(args[1]), args[2], args[3], args[4]);
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
            case "itam-bulk-transition" -> {
                require(args, 5, "itam-bulk-transition <database.db> <id1,id2,...> <ação> <ator> [detalhes]");
                yield new GovernanceDataManager().assetBulkTransition(path(args[1]),
                    List.of(args[2].split(",")), args[3], args[4],
                    args.length > 5 ? String.join(" ", Arrays.copyOfRange(args, 5, args.length)) : "");
            }
            case "itam-delete" -> {
                require(args, 4, "itam-delete <database.db> <id> <ator>");
                yield new GovernanceDataManager().assetSoftDelete(path(args[1]), args[2], args[3]);
            }
            case "itam-restore" -> {
                require(args, 3, "itam-restore <database.db> <id>");
                yield new GovernanceDataManager().assetRestore(path(args[1]), args[2]);
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
            case "migrate-verify" -> {
                require(args, 2, "migrate-verify <arquivo.properties>");
                var migrator = new DatabaseMigrator();
                yield migrator.verify(migrator.load(path(args[1])));
            }
            case "vuln-server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : 8081;
                Path database = path(args.length > 2 ? args[2] : "data/vulnerabilities.db");
                var server = new VulnerabilityServer(port, database); server.start();
                System.out.println("Vulnerability API em http://127.0.0.1:" + server.port());
                awaitShutdown("Vulnerability API"); yield null;
            }
            case "itam-server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : 8082;
                Path database = path(args.length > 2 ? args[2] : "data/assets.db");
                var server = new AssetServer(port, database); server.start();
                System.out.println("ITAM API em http://127.0.0.1:" + server.port());
                awaitShutdown("ITAM API"); yield null;
            }
            case "portal-server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
                String vuln = option(args, "--vulnerability-url", "http://127.0.0.1:8081");
                String itam = option(args, "--itam-url", "http://127.0.0.1:8082");
                var server = new PortalServer(port, vuln, itam); server.start();
                System.out.println("Portal em http://127.0.0.1:" + server.port());
                awaitShutdown("Portal"); yield null;
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
            default -> executePlugin(args);
        };
    }

    private static PluginRegistry pluginRegistry;
    private static Object executePlugin(String[] args) throws Exception {
        if (pluginRegistry == null)
            pluginRegistry = PluginRegistry.load(path(".swissknife/plugins"));
        if (!pluginRegistry.has(args[0])) throw new IllegalArgumentException("Comando desconhecido: " + args[0]);
        return pluginRegistry.execute(args, CliConfig.load(Path.of("").toAbsolutePath(), null, null, Map.of()));
    }

    private static void awaitShutdown(String serverName) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println(serverName + ": encerrando com segurança (Ctrl+C)...");
            latch.countDown();
        }, "swissknife-shutdown-hook"));
        latch.await();
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
        StringBuilder out = new StringBuilder();
        out.append("SwissKnife Javanist\n");
        out.append("Uso: swissknife <comando> [argumentos]\n\n");
        for (var command : CommandCatalog.COMMANDS)
            out.append("  ").append(pad(command.name(), 18)).append(command.description()).append("\n");
        out.append("""

            Opções globais:
              --config <arquivo>  Configuração YAML explícita
              --profile <nome>    Perfil de configuração
              --format <formato>  json, text, yaml, csv, xml, html, markdown, asciidoc, sarif, junit
              --output <arquivo>  Grava a resposta; use - para stdout
              --quiet             Não imprime a resposta
              --no-fail           Não altera o código de saída por findings/políticas
              --verbose           Exibe informações adicionais e tempo de execução
              --debug             Exibe stack traces
              --set chave=valor   Sobrescreve uma chave de configuração
              --dry-run           Simula a operação sem alterar arquivos/bancos (por comando)

            Variáveis de ambiente:
              NO_COLOR            Desativa cores mesmo com output.color=auto/always
              SWISSKNIFE_DEBUG    Equivalente a --debug
              SWISSKNIFE_*        Sobrescreve qualquer chave de configuração
            """);
        System.out.println(out);
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value + " " : value + " ".repeat(width - value.length());
    }

    private static String version() { return Version.current(); }

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
