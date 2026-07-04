package dev.swissknife.docs;

import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class DocumentationGenerator {
    private static final Pattern TYPE = Pattern.compile(
        "(?m)^\\s*(?:public\\s+)?(?:abstract\\s+|final\\s+|sealed\\s+)?(class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern METHOD = Pattern.compile(
        "(?m)^\\s*public\\s+(?:static\\s+)?(?:<[^>]+>\\s+)?([\\w<>?,.\\[\\] ]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");

    public Report generate(Path source, Path output) throws IOException {
        var files = FilesEx.walk(source, p -> p.toString().endsWith(".java"));
        var markdown = new StringBuilder("# Referência do código\n\n");
        int types = 0, methods = 0;
        for (var file : files) {
            String code = Files.readString(file);
            var type = TYPE.matcher(code);
            if (!type.find()) continue;
            types++;
            String packageName = packageName(code);
            markdown.append("## `").append(packageName.isBlank() ? "" : packageName + ".")
                .append(type.group(2)).append("`\n\n");
            var description = leadingJavadoc(code, type.start());
            if (!description.isBlank()) markdown.append(description).append("\n\n");
            markdown.append("- Tipo: ").append(type.group(1)).append("\n");
            markdown.append("- Arquivo: `").append(source.relativize(file)).append("`\n\n");
            var method = METHOD.matcher(code);
            boolean heading = false;
            while (method.find()) {
                if (!heading) { markdown.append("### Métodos públicos\n\n"); heading = true; }
                methods++;
                markdown.append("- `").append(method.group(1).trim()).append(" ")
                    .append(method.group(2)).append("(").append(method.group(3).trim()).append(")`");
                var doc = leadingJavadoc(code, method.start());
                if (!doc.isBlank()) markdown.append(" — ").append(doc.replace("\n", " "));
                markdown.append("\n");
            }
            markdown.append("\n");
        }
        FilesEx.write(output, markdown.toString());
        return new Report(files.size(), types, methods, output);
    }

    private String packageName(String code) {
        var m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(code);
        return m.find() ? m.group(1) : "";
    }

    private String leadingJavadoc(String code, int before) {
        var prefix = code.substring(0, before);
        int end = prefix.lastIndexOf("*/");
        int start = prefix.lastIndexOf("/**");
        if (start < 0 || end < start || !prefix.substring(end + 2).isBlank()) return "";
        return prefix.substring(start + 3, end).lines()
            .map(s -> s.replaceFirst("^\\s*\\*\\s?", "").trim())
            .filter(s -> !s.startsWith("@"))
            .reduce((a, b) -> a + "\n" + b).orElse("").trim();
    }

    public record Report(int files, int types, int methods, Path output) {}
}
