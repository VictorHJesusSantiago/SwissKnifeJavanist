package dev.swissknife;

import dev.swissknife.itam.AssetServer;
import dev.swissknife.vulnerability.VulnerabilityServer;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

public final class ServerTest {
    public static void run() throws Exception {
        var client = HttpClient.newHttpClient();
        var temp = Files.createTempDirectory("server-test");
        var vuln = new VulnerabilityServer(0, temp.resolve("vuln.db"));
        vuln.start();
        try {
            var post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + vuln.port() + "/api/v1/vulnerabilities"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"CVE\",\"component\":\"api\",\"severity\":\"HIGH\"}")).build();
            TestSupport.equal(201, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { vuln.stop(); }
        var itam = new AssetServer(0, temp.resolve("itam.db"));
        itam.start();
        try {
            var post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + itam.port() + "/api/v1/assets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"tag\":\"NB-1\",\"name\":\"Notebook\",\"type\":\"COMPUTER\"}")).build();
            TestSupport.equal(201, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { itam.stop(); }
    }
}
