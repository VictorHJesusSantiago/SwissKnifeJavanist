package dev.swissknife.docs;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.swissknife.util.FilesEx;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;

/** Gera referência documental a partir da AST oficial do compilador Java. */
public final class DocumentationGenerator {
    private boolean includePrivate = false;

    public Report generate(Path source, Path output) throws IOException {
        return generate(source, output, formatFrom(output), false);
    }

    public Report generate(Path source, Path output, boolean includePrivate) throws IOException {
        return generate(source, output, formatFrom(output), includePrivate);
    }

    public Report generate(Path source, Path output, String format) throws IOException {
        return generate(source, output, format, false);
    }

    public Report generate(Path source, Path output, String format, boolean includePrivate) throws IOException {
        this.includePrivate = includePrivate;
        Model model = analyze(source);
        String content = switch (format.toLowerCase(Locale.ROOT)) {
            case "markdown", "md" -> markdown(model, source);
            case "html" -> html(model, source);
            case "asciidoc", "adoc" -> asciidoc(model, source);
            case "json" -> dev.swissknife.util.Json.stringify(model);
            default -> throw new IllegalArgumentException("Formato de documentação inválido: " + format);
        };
        FilesEx.write(output, content);
        return report(model, output, format);
    }

    public Report generateSite(Path source, Path directory) throws IOException {
        return generateSite(source, directory, false);
    }

    public Report generateSite(Path source, Path directory, boolean includePrivate) throws IOException {
        this.includePrivate = includePrivate;
        Model model = analyze(source);
        Files.createDirectories(directory);
        FilesEx.write(directory.resolve("index.html"), html(model, source));
        FilesEx.write(directory.resolve("search-index.json"), dev.swissknife.util.Json.stringify(
            model.types().stream().map(type -> Map.of("name", type.qualifiedName(), "package", type.packageName(),
                "kind", type.kind(), "description", type.description())).toList()));
        FilesEx.write(directory.resolve("reference.md"), markdown(model, source));
        Path packagesDir = directory.resolve("packages");
        Files.createDirectories(packagesDir);
        Map<String, List<TypeDoc>> byPackage = new TreeMap<>();
        model.types().forEach(type -> byPackage.computeIfAbsent(type.packageName(), k -> new ArrayList<>()).add(type));
        byPackage.forEach((pkg, types) -> {
            try {
                FilesEx.write(packagesDir.resolve(packagePageName(pkg)), packagePage(pkg, types));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        return report(model, directory.resolve("index.html"), "site");
    }

    private String packagePageName(String pkg) {
        return (pkg.isBlank() ? "default-package" : pkg.replace('.', '-')) + ".html";
    }

    private String packagePage(String pkg, List<TypeDoc> types) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(pkg.isBlank() ? "(pacote padrão)" : pkg)).append("</h1><ul>");
        for (TypeDoc type : types)
            body.append("<li><code>").append(escape(type.qualifiedName())).append("</code> — ")
                .append(escape(type.kind())).append(type.description().isBlank() ? "" : " — " + escape(firstLine(type.description()))).append("</li>");
        body.append("</ul><p><a href=\"../index.html\">&larr; Índice geral</a></p>");
        return """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>%s</title>
            <style>:root{color-scheme:light dark}body{font:15px system-ui;max-width:900px;margin:auto;padding:2rem}
            code{background:#8882;padding:.15rem .35rem;border-radius:.3rem}</style></head><body>%s</body></html>
            """.formatted(escape(pkg), body);
    }

    /** Gera um diagrama de classes UML (sintaxe Mermaid classDiagram) a partir do modelo analisado. */
    public String umlClassDiagram(Path source) throws IOException {
        Model model = analyze(source);
        StringBuilder out = new StringBuilder("classDiagram\n");
        for (TypeDoc type : model.types()) {
            String name = mermaidId(type.qualifiedName());
            out.append("  class ").append(name).append(" {\n");
            if (!type.kind().isBlank()) out.append("    <<").append(type.kind()).append(">>\n");
            for (MemberDoc member : type.members()) {
                if (!documentable(member)) continue;
                String visibility = member.modifiers().contains("private") ? "-" :
                    member.modifiers().contains("protected") ? "#" : "+";
                if (Set.of("FIELD", "CONSTANT", "RECORD_COMPONENT").contains(member.kind()))
                    out.append("    ").append(visibility).append(member.type()).append(" ").append(member.name()).append("\n");
                else if (!member.kind().equals("CONSTRUCTOR"))
                    out.append("    ").append(visibility).append(member.name()).append("() ").append(member.type()).append("\n");
            }
            out.append("  }\n");
            if (!type.extendsType().isBlank())
                out.append("  ").append(mermaidId(baseName(type.extendsType()))).append(" <|-- ").append(name).append("\n");
            for (String implemented : type.implementsTypes())
                out.append("  ").append(mermaidId(baseName(implemented))).append(" <|.. ").append(name).append("\n");
        }
        return out.toString();
    }

    /** Gera um diagrama de dependências entre pacotes (Mermaid flowchart) a partir dos imports observados. */
    public String packageDiagram(Path source) throws IOException {
        Model model = analyze(source);
        Map<String, Set<String>> packageTypes = new TreeMap<>();
        model.types().forEach(type -> packageTypes.computeIfAbsent(type.packageName(), k -> new TreeSet<>()).add(type.name()));
        StringBuilder out = new StringBuilder("flowchart TD\n");
        packageTypes.forEach((pkg, types) -> out.append("  ").append(mermaidId(pkg.isBlank() ? "default" : pkg))
            .append("[\"").append(pkg.isBlank() ? "(pacote padrão)" : pkg).append("\\n").append(types.size()).append(" tipo(s)\"]\n"));
        Set<String> edges = new LinkedHashSet<>();
        for (TypeDoc type : model.types()) {
            String from = type.packageName();
            for (String candidate : referencedTypeNames(type))
                for (TypeDoc other : model.types())
                    if (other.name().equals(candidate) && !other.packageName().equals(from))
                        edges.add(mermaidId(from.isBlank() ? "default" : from) + " --> " + mermaidId(other.packageName()));
        }
        edges.forEach(edge -> out.append("  ").append(edge).append("\n"));
        return out.toString();
    }

    private List<String> referencedTypeNames(TypeDoc type) {
        List<String> names = new ArrayList<>();
        if (!type.extendsType().isBlank()) names.add(baseName(type.extendsType()));
        type.implementsTypes().forEach(i -> names.add(baseName(i)));
        return names;
    }
    private String baseName(String typeExpression) {
        String cleaned = typeExpression.replaceAll("<.*>", "").trim();
        int dot = cleaned.lastIndexOf('.');
        return dot < 0 ? cleaned : cleaned.substring(dot + 1);
    }
    private String mermaidId(String value) { return value.replaceAll("[^A-Za-z0-9_]", "_"); }

    /** Exporta a documentação como um site MkDocs pronto para `mkdocs build` (mkdocs.yml + docs/*.md). */
    public Path exportMkDocs(Path source, Path directory) throws IOException {
        Model model = analyze(source);
        Files.createDirectories(directory.resolve("docs"));
        FilesEx.write(directory.resolve("docs/index.md"), markdown(model, source));
        FilesEx.write(directory.resolve("docs/uml.md"), "# Diagrama de classes\n\n```mermaid\n" + umlClassDiagram(source) + "```\n");
        FilesEx.write(directory.resolve("docs/packages.md"), "# Dependências entre pacotes\n\n```mermaid\n" + packageDiagram(source) + "```\n");
        FilesEx.write(directory.resolve("mkdocs.yml"), """
            site_name: Referência do código
            theme:
              name: material
            markdown_extensions:
              - pymdownx.superfences:
                  custom_fences:
                    - name: mermaid
                      class: mermaid
                      format: !!python/name:pymdownx.superfences.fence_code_format
            nav:
              - Referência: index.md
              - Diagrama de classes: uml.md
              - Pacotes: packages.md
            """);
        return directory;
    }

    private String firstLine(String description) {
        String[] lines = description.lines().toArray(String[]::new);
        return lines.length == 0 ? "" : lines[0];
    }

    public ApiDiff compare(Path previous, Path current) throws IOException {
        Model oldModel = analyze(previous), newModel = analyze(current);
        Map<String, TypeDoc> oldTypes = index(oldModel), newTypes = index(newModel);
        List<ApiChange> changes = new ArrayList<>();
        oldTypes.forEach((name, type) -> {
            if (!newTypes.containsKey(name)) changes.add(new ApiChange("REMOVED_TYPE", name, true, "Tipo público removido"));
            else compareMembers(type, newTypes.get(name), changes);
        });
        newTypes.keySet().stream().filter(name -> !oldTypes.containsKey(name))
            .forEach(name -> changes.add(new ApiChange("ADDED_TYPE", name, false, "Novo tipo público")));
        return new ApiDiff(changes, changes.stream().anyMatch(ApiChange::breaking),
            changes.stream().filter(ApiChange::breaking).count());
    }

    private Model analyze(Path source) throws IOException {
        List<Path> files = FilesEx.walk(source, p -> p.toString().endsWith(".java"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK completo é necessário para documentar fontes Java");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<TypeDoc> types = new ArrayList<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none", "-Xlint:none"), null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            DocTrees docs = DocTrees.instance(task);
            for (CompilationUnitTree unit : parsed) {
                String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                Path file = Path.of(unit.getSourceFile().toUri());
                new TreePathScanner<Void, Void>() {
                    private final Deque<String> nesting = new ArrayDeque<>();
                    @Override public Void visitClass(ClassTree tree, Void unused) {
                        String simple = tree.getSimpleName().toString();
                        if (simple.isBlank()) return super.visitClass(tree, unused);
                        nesting.addLast(simple);
                        TreePath path = getCurrentPath();
                        String qualified = (packageName.isBlank() ? "" : packageName + ".") + String.join(".", nesting);
                        List<MemberDoc> members = new ArrayList<>();
                        if (tree.getKind() == Tree.Kind.RECORD)
                            members.addAll(recordComponents(unit, tree, docs, file));
                        members.addAll(members(tree, path, docs, unit));
                        List<String> annotations = tree.getModifiers().getAnnotations().stream().map(Object::toString).toList();
                        String kind = tree.getKind().name().replace("_", " ").toLowerCase(Locale.ROOT);
                        String description = documentation(docs, path);
                        long line = line(unit, tree, docs);
                        types.add(new TypeDoc(packageName, simple, qualified, kind,
                            modifiers(tree.getModifiers()), annotations, description,
                            tree.getExtendsClause() == null ? "" : tree.getExtendsClause().toString(),
                            tree.getImplementsClause().stream().map(Object::toString).toList(),
                            members, source.relativize(file).toString(), line,
                            annotations.stream().anyMatch(a -> a.contains("Deprecated")) ||
                                description.toLowerCase(Locale.ROOT).contains("@deprecated")));
                        Void result = super.visitClass(tree, unused);
                        nesting.removeLast();
                        return result;
                    }
                }.scan(unit, null);
            }
        }
        List<DiagnosticInfo> problems = diagnostics.getDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> new DiagnosticInfo(d.getSource() == null ? "" : d.getSource().getName(),
                d.getLineNumber(), d.getMessage(Locale.ROOT))).toList();
        types.sort(Comparator.comparing(TypeDoc::qualifiedName));
        return new Model(files.size(), types, problems);
    }

    private List<MemberDoc> recordComponents(CompilationUnitTree unit, ClassTree tree, DocTrees docs, Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int startOffset = (int) docs.getSourcePositions().getStartPosition(unit, tree);
            if (startOffset < 0 || startOffset >= text.length()) return List.of();
            int braceIndex = text.indexOf('{', startOffset);
            if (braceIndex < 0) return List.of();
            String header = text.substring(startOffset, braceIndex);
            Matcher matcher = Pattern.compile("\\brecord\\s+\\w+\\s*(?:<[^{]*?>)?\\s*\\(([^)]*)\\)").matcher(header);
            if (!matcher.find()) return List.of();
            String paramsText = matcher.group(1).trim();
            if (paramsText.isEmpty()) return List.of();
            long line = line(unit, tree, docs);
            List<MemberDoc> result = new ArrayList<>();
            for (String part : splitTopLevel(paramsText)) {
                String piece = part.strip();
                if (piece.isEmpty()) continue;
                int lastSpace = lastTopLevelSpace(piece);
                if (lastSpace < 0) continue;
                String componentType = piece.substring(0, lastSpace).strip();
                String name = piece.substring(lastSpace + 1).strip();
                if (name.isEmpty()) continue;
                result.add(new MemberDoc("RECORD_COMPONENT", name, componentType, List.of(),
                    new LinkedHashSet<>(Set.of("public")), List.of(), List.of(), "", line, ""));
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> splitTopLevel(String value) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '<' || c == '[') depth++;
            else if (c == '>' || c == ']') depth--;
            if (c == ',' && depth == 0) { parts.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    private int lastTopLevelSpace(String value) {
        int depth = 0;
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (c == '>' || c == ']') depth++;
            else if (c == '<' || c == '[') depth--;
            else if (c == ' ' && depth == 0) return i;
        }
        return -1;
    }

    private List<MemberDoc> members(ClassTree type, TreePath typePath, DocTrees docs, CompilationUnitTree unit) {
        List<MemberDoc> members = new ArrayList<>();
        for (Tree member : type.getMembers()) {
            TreePath path = new TreePath(typePath, member);
            if (member instanceof MethodTree method) {
                boolean constructor = method.getReturnType() == null;
                List<ParameterDoc> parameters = method.getParameters().stream()
                    .map(p -> new ParameterDoc(p.getName().toString(), p.getType().toString(),
                        p.getModifiers().getAnnotations().stream().map(Object::toString).toList())).toList();
                members.add(new MemberDoc(constructor ? "CONSTRUCTOR" : "METHOD",
                    constructor ? type.getSimpleName().toString() : method.getName().toString(),
                    constructor ? "" : method.getReturnType().toString(), parameters,
                    modifiers(method.getModifiers()), method.getModifiers().getAnnotations().stream().map(Object::toString).toList(),
                    method.getThrows().stream().map(Object::toString).toList(), documentation(docs, path),
                    line(unit, member, docs), method.getDefaultValue() == null ? "" : method.getDefaultValue().toString()));
            } else if (member instanceof VariableTree field) {
                members.add(new MemberDoc(field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)
                    && field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.FINAL) ? "CONSTANT" : "FIELD",
                    field.getName().toString(), field.getType().toString(), List.of(), modifiers(field.getModifiers()),
                    field.getModifiers().getAnnotations().stream().map(Object::toString).toList(), List.of(),
                    documentation(docs, path), line(unit, member, docs),
                    field.getInitializer() == null ? "" : field.getInitializer().toString()));
            }
        }
        return members;
    }

    private String markdown(Model model, Path source) {
        StringBuilder out = new StringBuilder("# Referência do código\n\n");
        out.append("Gerada a partir da AST Java. ").append(model.types().size()).append(" tipos em ")
            .append(model.files()).append(" arquivos.\n\n");
        Map<String, List<TypeDoc>> packages = new TreeMap<>();
        model.types().forEach(type -> packages.computeIfAbsent(type.packageName(), ignored -> new ArrayList<>()).add(type));
        out.append("## Índice\n\n");
        packages.forEach((pkg, types) -> {
            out.append("- **").append(pkg.isBlank() ? "(pacote padrão)" : pkg).append("**\n");
            types.forEach(type -> out.append("  - [`").append(type.qualifiedName()).append("`](#")
                .append(anchor(type.qualifiedName())).append(")\n"));
        });
        out.append("\n");
        for (TypeDoc type : model.types()) {
            out.append("<a id=\"").append(anchor(type.qualifiedName())).append("\"></a>\n\n## `")
                .append(type.qualifiedName()).append("`\n\n");
            if (!type.description().isBlank()) out.append(cleanDoc(type.description())).append("\n\n");
            out.append("- Tipo: ").append(type.kind()).append("\n- Modificadores: ")
                .append(String.join(" ", type.modifiers())).append("\n- Arquivo: `").append(type.file())
                .append(":").append(type.line()).append("`\n");
            if (!type.extendsType().isBlank()) out.append("- Estende: `").append(type.extendsType()).append("`\n");
            if (!type.implementsTypes().isEmpty()) out.append("- Implementa: `")
                .append(String.join("`, `", type.implementsTypes())).append("`\n");
            if (!type.annotations().isEmpty()) out.append("- Anotações: `")
                .append(String.join("`, `", type.annotations())).append("`\n");
            appendMembers(out, type);
        }
        Coverage coverage = coverage(model);
        out.append("\n## Cobertura documental\n\n- Tipos documentados: ").append(coverage.documentedTypes())
            .append("/").append(coverage.totalTypes()).append("\n- Membros públicos documentados: ")
            .append(coverage.documentedPublicMembers()).append("/").append(coverage.publicMembers())
            .append("\n- Cobertura: ").append(String.format(Locale.ROOT, "%.1f%%", coverage.percentage())).append("\n");
        List<String> missingParams = missingParamDocs(model);
        if (!missingParams.isEmpty()) {
            out.append("\n## Parâmetros sem @param\n\n");
            missingParams.forEach(issue -> out.append("- ").append(issue).append("\n"));
        }
        return out.toString();
    }

    private List<String> missingParamDocs(Model model) {
        List<String> issues = new ArrayList<>();
        for (TypeDoc type : model.types())
            for (MemberDoc member : type.members()) {
                if (!Set.of("METHOD", "CONSTRUCTOR").contains(member.kind())) continue;
                if (member.description().isBlank() || member.parameters().isEmpty()) continue;
                Set<String> documented = new LinkedHashSet<>();
                Matcher matcher = Pattern.compile("@param\\s+(\\w+)").matcher(member.description());
                while (matcher.find()) documented.add(matcher.group(1));
                for (ParameterDoc parameter : member.parameters())
                    if (!documented.contains(parameter.name()))
                        issues.add(type.qualifiedName() + "#" + member.name() + ": parâmetro `" +
                            parameter.name() + "` sem @param (linha " + member.line() + ")");
            }
        return issues;
    }

    private void appendMembers(StringBuilder out, TypeDoc type) {
        Map<String, List<MemberDoc>> grouped = new LinkedHashMap<>();
        type.members().stream().filter(this::documentable).forEach(member ->
            grouped.computeIfAbsent(member.kind(), ignored -> new ArrayList<>()).add(member));
        grouped.forEach((kind, members) -> {
            out.append("\n### ").append(switch (kind) {
                case "CONSTRUCTOR" -> "Construtores"; case "METHOD" -> "Métodos";
                case "CONSTANT" -> "Constantes"; case "RECORD_COMPONENT" -> "Componentes do record";
                default -> "Campos";
            }).append("\n\n");
            for (MemberDoc member : members) {
                out.append("- `").append(signature(member)).append("`");
                if (!member.description().isBlank()) out.append(" — ").append(cleanDoc(member.description()).replace("\n", " "));
                out.append(" _(linha ").append(member.line()).append(")_\n");
            }
        });
    }

    private String html(Model model, Path source) {
        String markdown = markdown(model, source);
        String body = markdown.lines().map(line -> {
            if (line.startsWith("## ")) return "<h2>" + escape(line.substring(3)) + "</h2>";
            if (line.startsWith("### ")) return "<h3>" + escape(line.substring(4)) + "</h3>";
            if (line.startsWith("# ")) return "<h1>" + escape(line.substring(2)) + "</h1>";
            if (line.startsWith("- ")) return "<div class=\"item\">" + inline(line.substring(2)) + "</div>";
            if (line.isBlank()) return "";
            return "<p>" + inline(line) + "</p>";
        }).reduce("", (a, b) -> a + b + "\n");
        return """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>Referência Java</title><style>:root{color-scheme:light dark}body{font:15px system-ui;max-width:1100px;margin:auto;padding:2rem}
            #q{width:100%%;padding:.8rem;position:sticky;top:.5rem}h2{margin-top:3rem;border-bottom:1px solid #8885;padding-bottom:.5rem}
            code{background:#8882;padding:.15rem .35rem;border-radius:.3rem}.item{margin:.45rem 0}</style></head><body>
            <input id="q" placeholder="Pesquisar na documentação" aria-label="Pesquisar"><main id="content">%s</main>
            <script>q.oninput=()=>{for(const e of content.querySelectorAll('h2,h3,.item,p'))e.hidden=!e.innerText.toLowerCase().includes(q.value.toLowerCase())}</script>
            </body></html>
            """.formatted(body);
    }

    private String asciidoc(Model model, Path source) {
        return markdown(model, source).replaceFirst("^# ", "= ").replaceAll("(?m)^## ", "== ")
            .replaceAll("(?m)^### ", "=== ").replace("`", "`");
    }

    private Report report(Model model, Path output, String format) {
        Coverage coverage = coverage(model);
        int methods = model.types().stream().mapToInt(type -> (int) type.members().stream()
            .filter(member -> member.kind().equals("METHOD")).count()).sum();
        int constructors = model.types().stream().mapToInt(type -> (int) type.members().stream()
            .filter(member -> member.kind().equals("CONSTRUCTOR")).count()).sum();
        int fields = model.types().stream().mapToInt(type -> (int) type.members().stream()
            .filter(member -> Set.of("FIELD", "CONSTANT").contains(member.kind())).count()).sum();
        return new Report(model.files(), model.types().size(), methods, constructors, fields,
            coverage.percentage(), model.diagnostics().size(), format, output, missingParamDocs(model));
    }

    private Coverage coverage(Model model) {
        int totalTypes = model.types().size();
        long documentedTypes = model.types().stream().filter(type -> !type.description().isBlank()).count();
        List<MemberDoc> publicMembers = model.types().stream().flatMap(type -> type.members().stream())
            .filter(this::documentable).toList();
        long documentedMembers = publicMembers.stream().filter(member -> !member.description().isBlank()).count();
        double percentage = totalTypes + publicMembers.size() == 0 ? 100 :
            100.0 * (documentedTypes + documentedMembers) / (totalTypes + publicMembers.size());
        return new Coverage(totalTypes, documentedTypes, publicMembers.size(), documentedMembers, percentage);
    }
    private boolean documentable(MemberDoc member) {
        return includePrivate || member.modifiers().contains("public") || member.modifiers().contains("protected");
    }
    private String signature(MemberDoc member) {
        if (member.kind().equals("RECORD_COMPONENT")) return member.type() + " " + member.name();
        if (member.kind().equals("FIELD") || member.kind().equals("CONSTANT"))
            return String.join(" ", member.modifiers()) + " " + member.type() + " " + member.name();
        return (member.kind().equals("CONSTRUCTOR") ? "" : member.type() + " ") + member.name() + "(" +
            member.parameters().stream().map(p -> p.type() + " " + p.name()).reduce((a,b)->a+", "+b).orElse("") + ")" +
            (member.throwsTypes().isEmpty() ? "" : " throws " + String.join(", ", member.throwsTypes()));
    }
    private String documentation(DocTrees docs, TreePath path) {
        DocCommentTree tree = docs.getDocCommentTree(path);
        return tree == null ? "" : tree.toString().trim();
    }
    private long line(CompilationUnitTree unit, Tree tree, DocTrees docs) {
        long position = docs.getSourcePositions().getStartPosition(unit, tree);
        return position < 0 || unit.getLineMap() == null ? 1 : unit.getLineMap().getLineNumber(position);
    }
    private Set<String> modifiers(ModifiersTree modifiers) {
        Set<String> result = new LinkedHashSet<>();
        modifiers.getFlags().forEach(flag -> result.add(flag.toString().toLowerCase(Locale.ROOT)));
        return result;
    }
    private Map<String, TypeDoc> index(Model model) {
        Map<String, TypeDoc> result = new TreeMap<>(); model.types().forEach(type -> result.put(type.qualifiedName(), type)); return result;
    }
    private void compareMembers(TypeDoc oldType, TypeDoc newType, List<ApiChange> changes) {
        Set<String> oldMembers = new TreeSet<>(), newMembers = new TreeSet<>();
        oldType.members().stream().filter(this::documentable).map(this::signature).forEach(oldMembers::add);
        newType.members().stream().filter(this::documentable).map(this::signature).forEach(newMembers::add);
        oldMembers.stream().filter(member -> !newMembers.contains(member)).forEach(member ->
            changes.add(new ApiChange("REMOVED_MEMBER", oldType.qualifiedName() + "#" + member, true, "Membro público removido ou alterado")));
        newMembers.stream().filter(member -> !oldMembers.contains(member)).forEach(member ->
            changes.add(new ApiChange("ADDED_MEMBER", newType.qualifiedName() + "#" + member, false, "Novo membro público")));
    }
    private String cleanDoc(String value) { return value.replaceAll("(?m)^\\s*@(param|return|throws|since|deprecated)\\b", "\n- @$1"); }
    private String formatFrom(Path output) {
        String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "html";
        if (name.endsWith(".adoc") || name.endsWith(".asciidoc")) return "asciidoc";
        if (name.endsWith(".json")) return "json";
        return "markdown";
    }
    private String anchor(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""); }
    private String inline(String value) {
        String escaped = escape(value);
        return escaped.replaceAll("`([^`]+)`", "<code>$1</code>")
            .replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
    }
    private String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }

    public record ParameterDoc(String name, String type, List<String> annotations) {}
    public record MemberDoc(String kind, String name, String type, List<ParameterDoc> parameters,
                            Set<String> modifiers, List<String> annotations, List<String> throwsTypes,
                            String description, long line, String defaultValue) {}
    public record TypeDoc(String packageName, String name, String qualifiedName, String kind,
                          Set<String> modifiers, List<String> annotations, String description,
                          String extendsType, List<String> implementsTypes, List<MemberDoc> members,
                          String file, long line, boolean deprecated) {}
    public record DiagnosticInfo(String file, long line, String message) {}
    public record Model(int files, List<TypeDoc> types, List<DiagnosticInfo> diagnostics) {}
    public record Coverage(int totalTypes, long documentedTypes, int publicMembers,
                           long documentedPublicMembers, double percentage) {}
    public record ApiChange(String kind, String object, boolean breaking, String description) {}
    public record ApiDiff(List<ApiChange> changes, boolean breaking, long breakingChanges) {}
    public record Report(int files, int types, int methods, int constructors, int fields,
                         double coveragePercentage, int parseErrors, String format, Path output,
                         List<String> missingParamDocs) {}

    /** Formata um changelog de API pública a partir de um ApiDiff (docs-diff). */
    public String changelog(ApiDiff diff, String previousLabel, String currentLabel) {
        StringBuilder out = new StringBuilder("# Changelog de API pública\n\n");
        out.append(previousLabel).append(" → ").append(currentLabel).append("\n\n");
        List<ApiChange> added = diff.changes().stream().filter(c -> c.kind().startsWith("ADDED")).toList();
        List<ApiChange> removed = diff.changes().stream().filter(c -> c.kind().startsWith("REMOVED")).toList();
        if (diff.breaking()) out.append("⚠️ ").append(diff.breakingChanges()).append(" mudança(s) incompatível(is) (breaking change).\n\n");
        if (!added.isEmpty()) {
            out.append("## Adicionado\n\n");
            added.forEach(c -> out.append("- `").append(c.object()).append("` — ").append(c.description()).append("\n"));
            out.append("\n");
        }
        if (!removed.isEmpty()) {
            out.append("## Removido\n\n");
            removed.forEach(c -> out.append("- `").append(c.object()).append("`").append(c.breaking() ? " **(breaking)**" : "")
                .append(" — ").append(c.description()).append("\n"));
        }
        if (added.isEmpty() && removed.isEmpty()) out.append("Nenhuma mudança de API pública detectada.\n");
        return out.toString();
    }
}
