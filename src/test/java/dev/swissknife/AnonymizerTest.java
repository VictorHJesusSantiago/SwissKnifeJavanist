package dev.swissknife;

import dev.swissknife.anonymize.DataAnonymizer;
import java.nio.file.*;

public final class AnonymizerTest {
    public static void run() throws Exception {
        var root = Files.createTempDirectory("anon-test");
        var input = root.resolve("input.csv");
        var policy = root.resolve("policy.properties");
        var output = root.resolve("output.csv");
        Files.writeString(input, "\uFEFFname,email,city\nAna,ana@real.com,Recife\n");
        Files.writeString(policy, "name=name\nemail=email\n");
        new DataAnonymizer().anonymize(input, policy, output);
        var result = Files.readString(output);
        TestSupport.truth(result.contains("Pessoa 1,usuario1@exemplo.test,Recife"), "Anonimização incorreta");
    }
}
