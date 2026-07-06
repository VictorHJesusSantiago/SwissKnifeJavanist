package dev.swissknife;

import com.sun.net.httpserver.HttpServer;
import dev.swissknife.contract.ContractTester;
import dev.swissknife.ai.AiAssistant;
import dev.swissknife.integrations.IntegrationDispatcher;
import dev.swissknife.migration.DatabaseMigrator;
import dev.swissknife.management.GovernanceDataManager;
import dev.swissknife.server.JsonStore;
import dev.swissknife.portal.PortalServer;
import dev.swissknife.util.Json;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class FunctionalIntegrationTest {
    public static void run() throws Exception {
        contracts();
        integrations();
        migrationConfiguration();
        storeOperations();
        governanceWorkflows();
        aiDryRun();
        portalJobs();
    }

    private static void contracts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] body = "{\"id\":\"abc\",\"status\":\"READY\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Service", "test");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/items", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = ("{\"path\":\"" + path + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Path file = Files.createTempFile("contracts", ".json");
            Files.writeString(file, """
                {"variables":{"base":"http://127.0.0.1:%d"},"contracts":[
                  {"url":"${base}/token","expectedStatus":200,"expectedHeaders":{"X-Service":"test"},
                   "jsonPath":{"$.status":"READY"},"extract":{"itemId":"$.id"},"maxResponseTimeMs":5000},
                  {"url":"${base}/items/${itemId}","bodyMatches":"abc","expectedStatus":200}
                ]}
                """.formatted(server.getAddress().getPort()));
            var result = (ContractTester.SuiteResult) new ContractTester().executeAny(file);
            TestSupport.truth(result.passed(), "Suíte de contratos falhou: " + result.results());
            TestSupport.equal(2, result.total());
        } finally { server.stop(0); }
    }

    private static void integrations() throws Exception {
        Path root = Files.createTempDirectory("integration-dry-run");
        Path config = root.resolve("integration.properties");
        Path payload = root.resolve("finding.json");
        Files.writeString(config, "provider=github\nurl=https://example.invalid/issues\n");
        Files.writeString(payload, "{\"title\":\"Finding\",\"description\":\"Detalhe\",\"severity\":\"HIGH\"}");
        var result = new IntegrationDispatcher().dispatch(config, payload, true);
        TestSupport.truth(result.success(), "Dry-run deveria ser bem-sucedido");
        TestSupport.truth(result.requestPreview().contains("Finding"), "Payload não foi adaptado");
    }

    private static void migrationConfiguration() throws Exception {
        Path file = Files.createTempFile("migration", ".properties");
        Files.writeString(file, """
            source.url=jdbc:test:source
            source.table=source_table
            target.url=jdbc:test:target
            target.table=target_table
            dryRun=true
            map.old_name=new_name
            transform.email=lowercase
            """);
        var config = new DatabaseMigrator().load(file);
        TestSupport.equal("new_name", config.columnMappings().get("old_name"));
        TestSupport.equal("lowercase", config.transforms().get("email"));
        TestSupport.truth(config.dryRun(), "Dry-run não carregado");
    }

    private static void storeOperations() throws Exception {
        Path root = Files.createTempDirectory("store-operations");
        Path database = root.resolve("data.db");
        JsonStore store = new JsonStore(database);
        store.save(new java.util.LinkedHashMap<>(java.util.Map.of("id","one","name","Primeiro")));
        store.save(new java.util.LinkedHashMap<>(java.util.Map.of("id","two","name","Segundo")));
        TestSupport.truth(store.verify().valid(), "Store deveria ser válido");
        Path backup = root.resolve("backup.db");
        TestSupport.equal(2, store.backup(backup).records());
        store.delete("one");
        TestSupport.equal(1, store.all().size());
        TestSupport.truth(store.restore(backup).valid(), "Restore inválido");
        TestSupport.equal(2, store.all().size());
        TestSupport.truth(store.compact().afterBytes() > 0, "Compactação não persistiu");
        TestSupport.truth(store.auditTrail(100).size() >= 5, "Auditoria incompleta");
    }

    private static void governanceWorkflows() throws Exception {
        Path root=Files.createTempDirectory("governance-workflows");
        Path vulnerabilities=root.resolve("vulnerabilities.db");
        Path importFile=root.resolve("findings.json");
        Files.writeString(importFile,"""
            [{"title":"CVE-X","cve":"CVE-2026-1","component":"api","severity":"CRITICAL",
              "project":"payments","dueAt":"2020-01-01T00:00:00Z"}]
            """);
        GovernanceDataManager manager=new GovernanceDataManager();
        var imported=manager.importVulnerabilities(vulnerabilities,importFile,"generic");
        TestSupport.equal(1,imported.created());
        String id=new JsonStore(vulnerabilities).all().getFirst().get("id").toString();
        manager.vulnerabilityTransition(vulnerabilities,id,"TRIAGED","ana","validada");
        manager.vulnerabilityTransition(vulnerabilities,id,"IN_PROGRESS","ana","corrigindo");
        manager.vulnerabilityTransition(vulnerabilities,id,"RESOLVED","ana","corrigida");
        TestSupport.truth(manager.vulnerabilityReport(vulnerabilities).mttrHours()>=0,"MTTR inválido");

        Path assets=root.resolve("assets.db"),csv=root.resolve("assets.csv");
        Files.writeString(csv,"tag,name,type,status,purchaseValue,department\nNB-1,Notebook,COMPUTER,IN_STOCK,5000,TI\n");
        TestSupport.equal(1,manager.importAssets(assets,csv).created());
        String assetId=new JsonStore(assets).all().getFirst().get("id").toString();
        manager.assetTransition(assets,assetId,"checkout","joao","uso remoto");
        TestSupport.equal(1,manager.assetReport(assets).total());
    }

    private static void aiDryRun() throws Exception {
        Path root=Files.createTempDirectory("ai-dry-run"),config=root.resolve("ai.properties"),input=root.resolve("input.txt");
        Files.writeString(config,"provider=local\nurl=http://127.0.0.1:11434/api/chat\nmodel=test-model\n");
        Files.writeString(input,"Explique password=segredo-super-secreto e token: abcdefghijklmnop");
        var result=new AiAssistant().execute(config,"explain",input,false,false);
        TestSupport.truth(result.dryRun(),"IA deveria permanecer em dry-run");
        TestSupport.truth(!result.requestPreview().contains("segredo-super-secreto"),"Senha vazou na prévia");
        TestSupport.truth(!result.requestPreview().contains("abcdefghijklmnop"),"Token vazou na prévia");
    }

    private static void portalJobs() throws Exception {
        Path root=Files.createTempDirectory("portal-jobs");
        Files.writeString(root.resolve("Sample.java"),"public class Sample { public int value(){ return 1; } }");
        PortalServer portal=new PortalServer(0,"http://127.0.0.1:1","http://127.0.0.1:2",root.resolve("jobs.db"));
        portal.start();
        try{
            HttpClient client=HttpClient.newHttpClient();
            URI base=URI.create("http://127.0.0.1:"+portal.port());
            String payload=Json.stringify(java.util.Map.of("command",java.util.List.of("quality",root.toString())));
            HttpResponse<String> created=client.send(HttpRequest.newBuilder(base.resolve("/api/jobs"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
            TestSupport.equal(202,created.statusCode());
            String id=String.valueOf(Json.object(created.body()).get("id"));
            String state="";
            for(int attempt=0;attempt<50&&!java.util.Set.of("COMPLETED","FAILED").contains(state);attempt++){
                Thread.sleep(50);
                var response=client.send(HttpRequest.newBuilder(base.resolve("/api/jobs/"+id)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                state=String.valueOf(Json.object(response.body()).get("state"));
            }
            TestSupport.equal("COMPLETED",state);
            var metrics=client.send(HttpRequest.newBuilder(base.resolve("/api/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            TestSupport.equal(200,metrics.statusCode());
            TestSupport.truth(((Number)Json.object(metrics.body()).get("completed")).intValue()>=1,"Métrica de job ausente");
            String forbidden=Json.stringify(java.util.Map.of("command",java.util.List.of("store-admin",root.resolve("x.db").toString(),"restore")));
            var rejected=client.send(HttpRequest.newBuilder(base.resolve("/api/jobs")).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(forbidden)).build(),HttpResponse.BodyHandlers.ofString());
            TestSupport.equal(400,rejected.statusCode());
        }finally{portal.stop();}
    }
}
