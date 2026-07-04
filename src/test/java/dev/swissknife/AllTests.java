package dev.swissknife;

import java.util.*;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        Map<String, CheckedRunnable> tests = new LinkedHashMap<>();
        tests.put("JsonTest", JsonTest::run);
        tests.put("AnalyzerTest", AnalyzerTest::run);
        tests.put("SchemaTest", SchemaTest::run);
        tests.put("DocumentationTest", DocumentationTest::run);
        tests.put("DependencyTest", DependencyTest::run);
        tests.put("AnonymizerTest", AnonymizerTest::run);
        tests.put("GatlingTest", GatlingTest::run);
        tests.put("ServerTest", ServerTest::run);
        int passed = 0;
        for (var test : tests.entrySet()) {
            try {
                test.getValue().run();
                System.out.println("OK  " + test.getKey());
                passed++;
            } catch (Throwable e) {
                System.err.println("FALHA " + test.getKey() + ": " + e);
                throw e;
            }
        }
        System.out.println("\n" + passed + "/" + tests.size() + " testes aprovados.");
    }
    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }
}
