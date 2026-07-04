package dev.swissknife;

import dev.swissknife.docs.DocumentationGenerator;
import java.nio.file.*;

public final class DocumentationTest {
    public static void run() throws Exception {
        var root = Files.createTempDirectory("docs-test");
        Files.writeString(root.resolve("Example.java"), """
            package sample;
            /** Exemplo documentado. */
            public class Example {
                /** Retorna saudação. */
                public String hello(String name) { return name; }
            }
            """);
        var output = root.resolve("api.md");
        var report = new DocumentationGenerator().generate(root, output);
        TestSupport.equal(1, report.types());
        TestSupport.truth(Files.readString(output).contains("hello(String name)"), "Método não documentado");
    }
}
