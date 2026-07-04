package dev.swissknife.contract;

import dev.swissknife.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class ContractTester {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Result execute(Path contractFile) throws IOException, InterruptedException {
        var contract = Json.object(Files.readString(contractFile));
        String method = text(contract, "method", "GET");
        String url = text(contract, "url", null);
        if (url == null) throw new IllegalArgumentException("url é obrigatória");
        String body = text(contract, "body", "");
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int expected = ((Number) contract.getOrDefault("expectedStatus", 200L)).intValue();
        List<String> failures = new ArrayList<>();
        if (response.statusCode() != expected) failures.add("Status esperado " + expected + ", recebido " + response.statusCode());
        Object contains = contract.get("bodyContains");
        if (contains instanceof List<?> list) {
            for (var value : list) if (!response.body().contains(String.valueOf(value)))
                failures.add("Corpo não contém: " + value);
        }
        return new Result(failures.isEmpty(), response.statusCode(), response.body(), failures);
    }

    private String text(Map<String, Object> map, String key, String defaultValue) {
        var value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
    public record Result(boolean passed, int actualStatus, String responseBody, List<String> failures) {}
}
