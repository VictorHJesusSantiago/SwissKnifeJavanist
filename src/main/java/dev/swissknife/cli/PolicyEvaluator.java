package dev.swissknife.cli;

import dev.swissknife.util.Json;
import java.util.*;

/** Converte findings e resultados em códigos de saída estáveis para CI. */
public final class PolicyEvaluator {
    private PolicyEvaluator() {}
    public static Evaluation evaluate(Object result, CliConfig config) {
        Object tree = Json.parse(Json.stringify(result));
        Counter counter = new Counter();
        scan(tree, counter);
        String failOn = config.get("policy.failOn", "error").toLowerCase(Locale.ROOT);
        boolean policyFailed = Boolean.FALSE.equals(counter.explicitPassed) || switch (failOn) {
            case "none", "off" -> false;
            case "info", "note", "low" -> counter.low + counter.medium + counter.high > 0;
            case "warning", "warn", "medium" -> counter.medium + counter.high > 0;
            default -> counter.high > 0;
        };
        int exitCode = policyFailed ? 3 : counter.medium + counter.high > 0 ? 2 : 0;
        return new Evaluation(exitCode, policyFailed, counter.high, counter.medium, counter.low);
    }
    private static void scan(Object value, Counter counter) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("passed") && map.get("passed") instanceof Boolean passed && !passed)
                counter.explicitPassed = false;
            Object severity = map.get("severity");
            if (severity != null) switch (String.valueOf(severity).toUpperCase(Locale.ROOT)) {
                case "CRITICAL", "HIGH", "ERROR" -> counter.high++;
                case "MEDIUM", "WARNING", "WARN" -> counter.medium++;
                default -> counter.low++;
            }
            map.values().forEach(item -> scan(item, counter));
        } else if (value instanceof List<?> list) list.forEach(item -> scan(item, counter));
    }
    private static final class Counter {
        int high, medium, low;
        Boolean explicitPassed = true;
    }
    public record Evaluation(int exitCode, boolean policyFailed,
                             int highFindings, int mediumFindings, int lowFindings) {}
}
