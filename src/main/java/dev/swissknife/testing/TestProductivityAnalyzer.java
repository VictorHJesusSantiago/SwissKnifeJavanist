package dev.swissknife.testing;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Geração de esqueletos de teste, ranking de lentidão e detecção de flakiness a partir de relatórios JUnit XML. */
public final class TestProductivityAnalyzer {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("\\b(?:public\\s+)?(?:final\\s+)?class\\s+(\\w+)");
    private static final Pattern METHOD = Pattern.compile(
        "(?m)^\\s*public\\s+(?!static\\s+void\\s+main\\b)[\\w<>?,.\\[\\]]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^\\{]+)?\\{");

    /** Gera um esqueleto JUnit 5 com um método @Test por método público da classe analisada. */
    public ScaffoldResult scaffold(Path javaSource, Path outputTestFile) throws IOException {
        String code = Files.readString(javaSource);
        Matcher packageMatcher = PACKAGE.matcher(code);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        Matcher typeMatcher = TYPE.matcher(code);
        if (!typeMatcher.find()) throw new IllegalArgumentException("Nenhuma classe pública encontrada em " + javaSource);
        String className = typeMatcher.group(1);
        List<String> methods = new ArrayList<>();
        Matcher methodMatcher = METHOD.matcher(code);
        while (methodMatcher.find()) methods.add(methodMatcher.group(1));
        StringBuilder out = new StringBuilder();
        if (!packageName.isBlank()) out.append("package ").append(packageName).append(";\n\n");
        out.append("import org.junit.jupiter.api.Test;\n")
            .append("import static org.junit.jupiter.api.Assertions.*;\n\n")
            .append("class ").append(className).append("Test {\n\n");
        if (methods.isEmpty()) {
            out.append("    @Test\n    void placeholder() {\n        fail(\"Nenhum método público detectado automaticamente; escreva o teste.\");\n    }\n");
        } else {
            LinkedHashSet<String> unique = new LinkedHashSet<>(methods);
            for (String method : unique) {
                out.append("    @Test\n    void ").append(method).append("_deveFuncionarCorretamente() {\n")
                    .append("        // TODO: instancie ").append(className).append(" e valide o comportamento de ").append(method).append("()\n")
                    .append("        fail(\"Teste ainda não implementado\");\n    }\n\n");
            }
        }
        out.append("}\n");
        FilesEx.write(outputTestFile, out.toString());
        return new ScaffoldResult(className, methods.size(), outputTestFile);
    }

    /** Extrai e ordena por duração os testcases de um relatório JUnit XML (mais lentos primeiro). */
    public List<SlowTest> rankSlowTests(Path junitXmlReport, int top) throws IOException {
        String xml = Files.readString(junitXmlReport);
        List<SlowTest> tests = new ArrayList<>();
        Matcher testcase = Pattern.compile(
            "<testcase\\s+[^>]*classname=\"([^\"]*)\"[^>]*name=\"([^\"]*)\"[^>]*time=\"([\\d.]+)\"[^>]*/?>").matcher(xml);
        while (testcase.find())
            tests.add(new SlowTest(testcase.group(1), testcase.group(2), Double.parseDouble(testcase.group(3))));
        return tests.stream().sorted(Comparator.comparingDouble(SlowTest::seconds).reversed())
            .limit(Math.max(1, top)).toList();
    }

    /**
     * Compara N relatórios JUnit XML da mesma suíte (execuções repetidas) e aponta testes cujo
     * resultado (passou/falhou) foi inconsistente entre execuções — candidatos a flaky.
     */
    public List<FlakyTest> detectFlaky(List<Path> junitXmlReports) throws IOException {
        if (junitXmlReports.size() < 2)
            throw new IllegalArgumentException("Informe ao menos 2 relatórios de execuções repetidas");
        Map<String, List<Boolean>> outcomes = new LinkedHashMap<>();
        for (Path report : junitXmlReports) {
            String xml = Files.readString(report);
            Matcher testcase = Pattern.compile(
                "<testcase\\s+[^>]*classname=\"([^\"]*)\"[^>]*name=\"([^\"]*)\"[^>]*(?:/>|>((?:(?!</testcase>).)*)</testcase>)",
                Pattern.DOTALL).matcher(xml);
            while (testcase.find()) {
                String key = testcase.group(1) + "#" + testcase.group(2);
                String body = testcase.group(3);
                boolean passed = body == null || (!body.contains("<failure") && !body.contains("<error"));
                outcomes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(passed);
            }
        }
        List<FlakyTest> flaky = new ArrayList<>();
        outcomes.forEach((key, results) -> {
            long passCount = results.stream().filter(Boolean::booleanValue).count();
            if (passCount > 0 && passCount < results.size())
                flaky.add(new FlakyTest(key, results.size(), (int) passCount, results.size() - (int) passCount));
        });
        return flaky;
    }

    public record ScaffoldResult(String className, int methodsCovered, Path output) {}
    public record SlowTest(String className, String name, double seconds) {}
    public record FlakyTest(String test, int runs, int passed, int failed) {}
}
