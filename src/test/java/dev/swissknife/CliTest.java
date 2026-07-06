package dev.swissknife;

import dev.swissknife.cli.*;
import java.nio.file.*;
import java.util.*;

public final class CliTest {
    public static void run() throws Exception {
        var root = Files.createTempDirectory("cli-test");
        var configFile = root.resolve(".swissknife.yml");
        CliConfig.initialize(configFile, false);
        var config = CliConfig.load(root, null, "production", Map.of("output.format", "json"));
        TestSupport.equal("production", config.get("profile", ""));
        TestSupport.equal("docs", config.alias("doc"));
        var rendered = OutputFormatter.format(Map.of("ok", true, "count", 2), "xml");
        TestSupport.truth(rendered.contains("<ok>true</ok>"), "XML inválido");
        TestSupport.equal(List.of("docs", "src main", "out.md"),
            CliTools.tokenize("docs \"src main\" out.md"));
        TestSupport.truth(CliTools.completion("bash").contains("complete -F"), "Completion Bash inválido");
    }
}
