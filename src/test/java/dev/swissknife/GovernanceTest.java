package dev.swissknife;

import dev.swissknife.governance.*;
import dev.swissknife.diagnostics.JvmDiagnostics;
import java.nio.file.*;
import java.util.*;

public final class GovernanceTest {
    public static void run() throws Exception {
        var root = Files.createTempDirectory("governance-test");
        Files.writeString(root.resolve("pom.xml"), """
            <project><dependencies>
              <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
            </dependencies></project>
            """);
        Files.writeString(root.resolve("Example.java"), """
            package sample;
            import javax.persistence.Entity;
            @org.springframework.web.bind.annotation.RestController
            public class Example {
              private String password = "super-secret-value";
              @org.springframework.web.bind.annotation.GetMapping("/hello")
              public String hello() { try { return "ok"; } catch (Exception e) { return ""; } }
            }
            """);
        var dependencies = new DependencyAuditor().analyze(root, Set.of(), Set.of());
        TestSupport.equal(1, dependencies.dependencies().size());
        TestSupport.truth(dependencies.cyclonedx().containsKey("components"), "CycloneDX ausente");
        var quality = new JavaQualityAnalyzer().analyze(root);
        TestSupport.equal(1, quality.files());
        TestSupport.truth(!new SecurityScanner().scan(root).findings().isEmpty(), "Segredo não detectado");
        TestSupport.truth(!new SpringGovernanceAnalyzer().analyze(root).endpoints().isEmpty(), "Endpoint não catalogado");
        TestSupport.truth(!new ModernizationAnalyzer().analyze(root, 21).findings().isEmpty(), "Migração javax não detectada");
        var log = root.resolve("gc.log");
        Files.writeString(log, "[1.0s][info][gc] GC(1) Pause Young 20.5ms\n");
        TestSupport.equal("GC_LOG", new JvmDiagnostics().analyze(log).type());
    }
}
