package dev.swissknife;

import dev.swissknife.util.Json;
import java.util.*;

public final class JsonTest {
    public static void run() {
        var json = Json.stringify(Map.of("name", "José", "active", true, "items", List.of(1, 2)));
        var parsed = Json.object(json);
        TestSupport.equal("José", parsed.get("name"));
        TestSupport.equal(true, parsed.get("active"));
        TestSupport.equal(2, ((List<?>) parsed.get("items")).size());
        TestSupport.equal("ok", Json.object("\uFEFF{\"status\":\"ok\"}").get("status"));
    }
}
