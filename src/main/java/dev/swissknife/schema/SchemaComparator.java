package dev.swissknife.schema;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Compara DDL portátil, preserva dependências e produz migração e rollback. */
public final class SchemaComparator {
    private static final Pattern CREATE = Pattern.compile(
        "(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\"`]+)\\s*\\((.*?)\\)\\s*;");
    private static final Pattern INDEX = Pattern.compile(
        "(?is)create\\s+(unique\\s+)?index\\s+([\\w.\"`]+)\\s+on\\s+([\\w.\"`]+)\\s*\\((.*?)\\)\\s*;");
    private static final Pattern SEQUENCE = Pattern.compile(
        "(?is)create\\s+sequence\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\"`]+)(.*?);");
    private static final Pattern VIEW = Pattern.compile(
        "(?is)create\\s+(materialized\\s+)?view\\s+([\\w.\"`]+)\\s+as\\s+(.*?);");
    private static final Pattern COLUMN = Pattern.compile(
        "^([\\w.\"`]+)\\s+([\\w.]+(?:\\s*\\([^)]*\\))?(?:\\s+with(?:out)?\\s+time\\s+zone)?)(.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public Schema parse(Path ddl) throws IOException { return parse(Files.readString(ddl)); }

    public Schema parse(String ddl) {
        String cleaned = ddl.replaceAll("(?m)--.*$", " ").replaceAll("(?s)/\\*.*?\\*/", " ");
        Map<String, MutableTable> mutable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Matcher matcher = CREATE.matcher(cleaned);
        while (matcher.find()) {
            String tableName = normalizeIdentifier(matcher.group(1));
            MutableTable table = new MutableTable(tableName);
            for (String raw : splitDefinitions(matcher.group(2))) {
                String definition = raw.trim();
                if (definition.isEmpty()) continue;
                if (isConstraint(definition)) parseConstraint(table, definition);
                else parseColumn(table, definition);
            }
            mutable.put(tableName, table);
        }
        Matcher indexes = INDEX.matcher(cleaned);
        while (indexes.find()) {
            String tableName = normalizeIdentifier(indexes.group(3));
            MutableTable table = mutable.get(tableName);
            if (table != null) {
                String name = normalizeIdentifier(indexes.group(2));
                table.indexes.put(name, new Index(name, splitNames(indexes.group(4)),
                    indexes.group(1) != null, normalizeSpace(indexes.group())));
            }
        }
        Map<String, Sequence> sequences = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Matcher sequenceMatcher = SEQUENCE.matcher(cleaned);
        while (sequenceMatcher.find()) {
            String name = normalizeIdentifier(sequenceMatcher.group(1));
            sequences.put(name, new Sequence(name, normalizeSpace(sequenceMatcher.group())));
        }
        Map<String, View> views = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Matcher viewMatcher = VIEW.matcher(cleaned);
        while (viewMatcher.find()) {
            String name = normalizeIdentifier(viewMatcher.group(2));
            views.put(name, new View(name, viewMatcher.group(1) != null,
                normalizeSpace(viewMatcher.group(3))));
        }
        Map<String, Table> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        mutable.forEach((name, table) -> tables.put(name, table.freeze()));
        return new Schema(tables, sequences, views);
    }

    /** Introspecta um banco vivo via JDBC (DatabaseMetaData) e monta o mesmo modelo Schema usado pelo parser de DDL. */
    public Schema introspect(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        Map<String, MutableTable> mutable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String catalog = connection.getCatalog();
        String schemaPattern = connection.getSchema();
        try (ResultSet tables = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (tables.next()) mutable.put(tables.getString("TABLE_NAME"), new MutableTable(tables.getString("TABLE_NAME")));
        }
        for (MutableTable table : mutable.values()) {
            Set<String> primaryKeyColumns = new LinkedHashSet<>();
            try (ResultSet pk = meta.getPrimaryKeys(catalog, schemaPattern, table.name)) {
                while (pk.next()) primaryKeyColumns.add(pk.getString("COLUMN_NAME"));
            }
            try (ResultSet columns = meta.getColumns(catalog, schemaPattern, table.name, "%")) {
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    String type = columns.getString("TYPE_NAME");
                    boolean nullable = columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                    String defaultValue = columns.getString("COLUMN_DEF");
                    boolean identity = "YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT"));
                    boolean primary = primaryKeyColumns.contains(name);
                    table.columns.put(name, new Column(name, type, !nullable, defaultValue, primary, false, identity, null));
                }
            }
            if (!primaryKeyColumns.isEmpty())
                table.constraints.put("pk_" + table.name, new Constraint("pk_" + table.name, "PRIMARY_KEY",
                    List.copyOf(primaryKeyColumns), null, null, ""));
            try (ResultSet fk = meta.getImportedKeys(catalog, schemaPattern, table.name)) {
                while (fk.next()) {
                    String name = fk.getString("FK_NAME") != null ? fk.getString("FK_NAME") : "fk_" + table.name + "_" + fk.getString("FKCOLUMN_NAME");
                    table.constraints.put(name, new Constraint(name, "FOREIGN_KEY", List.of(fk.getString("FKCOLUMN_NAME")),
                        fk.getString("PKTABLE_NAME"), List.of(fk.getString("PKCOLUMN_NAME")), ""));
                }
            }
            try (ResultSet indexes = meta.getIndexInfo(catalog, schemaPattern, table.name, false, true)) {
                Map<String, List<String>> byName = new LinkedHashMap<>();
                Map<String, Boolean> uniqueByName = new LinkedHashMap<>();
                while (indexes.next()) {
                    String indexName = indexes.getString("INDEX_NAME");
                    String column = indexes.getString("COLUMN_NAME");
                    if (indexName == null || column == null) continue;
                    byName.computeIfAbsent(indexName, k -> new ArrayList<>()).add(column);
                    uniqueByName.put(indexName, !indexes.getBoolean("NON_UNIQUE"));
                }
                byName.forEach((name, cols) -> {
                    if (name.toLowerCase(Locale.ROOT).startsWith("pk_") || name.equalsIgnoreCase("pk_" + table.name)) return;
                    table.indexes.put(name, new Index(name, cols, uniqueByName.getOrDefault(name, false),
                        "CREATE " + (uniqueByName.getOrDefault(name, false) ? "UNIQUE " : "") + "INDEX " + name +
                            " ON " + table.name + " (" + String.join(", ", cols) + ");"));
                });
            }
        }
        Map<String, Table> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        mutable.forEach((name, table) -> tables.put(name, table.freeze()));
        return new Schema(tables, Map.of(), Map.of());
    }

    public Diff compare(Schema desired, Schema actual) { return compare(desired, actual, Set.of()); }

    public Diff compare(Schema desired, Schema actual, Set<String> ignoredObjects) {
        List<Change> changes = new ArrayList<>();
        desired.tables().forEach((name, table) -> {
            if (ignored(ignoredObjects, name)) return;
            Table current = actual.tables().get(name);
            if (current == null) {
                changes.add(change("CREATE_TABLE", name, create(table), "DROP TABLE " + name + ";", false, "Estrutura ausente"));
                return;
            }
            table.columns().forEach((columnName, column) -> {
                if (!ignored(ignoredObjects, name + "." + columnName))
                    compareColumn(name, column, current.columns().get(columnName), changes);
            });
            compareConstraints(name, table.constraints(), current.constraints(), changes);
            compareIndexes(name, table.indexes(), current.indexes(), changes);
        });
        actual.tables().forEach((name, table) -> {
            if (ignored(ignoredObjects, name)) return;
            if (!desired.tables().containsKey(name))
                changes.add(change("DROP_TABLE", name, "DROP TABLE " + name + ";", create(table), true, "Tabela excedente"));
            else table.columns().forEach((column, old) -> {
                if (!desired.tables().get(name).columns().containsKey(column) && !ignored(ignoredObjects, name + "." + column))
                    changes.add(change("DROP_COLUMN", name + "." + column,
                        "ALTER TABLE " + name + " DROP COLUMN " + column + ";",
                        "ALTER TABLE " + name + " ADD COLUMN " + definition(old) + ";", true,
                        "Dados da coluna podem ser perdidos"));
            });
        });
        compareSequences(desired, actual, changes);
        compareViews(desired, actual, changes);
        List<Change> ordered = order(changes);
        List<String> renames = detectRenames(desired, actual, ordered);
        return new Diff(ordered, script(ordered, false), script(ordered, true),
            ordered.stream().filter(Change::destructive).count(), Instant.now().toString(), renames);
    }

    private boolean ignored(Set<String> ignoredObjects, String object) {
        return ignoredObjects.stream().anyMatch(pattern -> pattern.equalsIgnoreCase(object));
    }

    /**
     * Heurística de rename: quando uma tabela é criada e outra removida com o mesmo conjunto
     * de colunas (ou uma coluna é adicionada/removida no mesmo tipo), sugere possível rename
     * em vez de tratar como perda de dados.
     */
    private List<String> detectRenames(Schema desired, Schema actual, List<Change> changes) {
        List<String> suggestions = new ArrayList<>();
        List<String> createdTables = changes.stream().filter(c -> c.kind().equals("CREATE_TABLE")).map(Change::object).toList();
        List<String> droppedTables = changes.stream().filter(c -> c.kind().equals("DROP_TABLE")).map(Change::object).toList();
        for (String created : createdTables)
            for (String dropped : droppedTables) {
                Table newTable = desired.tables().get(created);
                Table oldTable = actual.tables().get(dropped);
                if (newTable != null && oldTable != null && sameColumnShape(newTable, oldTable))
                    suggestions.add("Possível rename de tabela: '" + dropped + "' -> '" + created + "' (mesma estrutura de colunas)");
            }
        List<String> addedColumns = changes.stream().filter(c -> c.kind().equals("ADD_COLUMN")).map(Change::object).toList();
        List<String> droppedColumns = changes.stream().filter(c -> c.kind().equals("DROP_COLUMN")).map(Change::object).toList();
        for (String added : addedColumns)
            for (String dropped : droppedColumns) {
                String addedTable = added.substring(0, added.indexOf('.'));
                String droppedTable = dropped.substring(0, dropped.indexOf('.'));
                if (!addedTable.equals(droppedTable)) continue;
                Column newColumn = desired.tables().get(addedTable).columns().get(added.substring(added.indexOf('.') + 1));
                Column oldColumn = actual.tables().get(droppedTable).columns().get(dropped.substring(dropped.indexOf('.') + 1));
                if (newColumn != null && oldColumn != null && canonicalType(newColumn.type()).equals(canonicalType(oldColumn.type())))
                    suggestions.add("Possível rename de coluna em '" + addedTable + "': '" + dropped.substring(dropped.indexOf('.') + 1) +
                        "' -> '" + newColumn.name() + "' (mesmo tipo " + canonicalType(newColumn.type()) + ")");
            }
        return suggestions;
    }

    private boolean sameColumnShape(Table a, Table b) {
        if (a.columns().size() != b.columns().size() || a.columns().isEmpty()) return false;
        List<String> typesA = a.columns().values().stream().map(c -> canonicalType(c.type())).sorted().toList();
        List<String> typesB = b.columns().values().stream().map(c -> canonicalType(c.type())).sorted().toList();
        return typesA.equals(typesB);
    }

    /** Gera um relatório HTML navegável do diff (destrutivo em destaque). */
    public String html(Diff diff) {
        StringBuilder rows = new StringBuilder();
        diff.changes().forEach(c -> rows.append("<tr class=\"").append(c.destructive() ? "destructive" : "").append("\"><td>")
            .append(c.kind()).append("</td><td>").append(escapeXml(c.object())).append("</td><td>")
            .append(escapeXml(c.reason())).append("</td><td>").append(c.destructive() ? "SIM" : "não").append("</td></tr>"));
        StringBuilder renames = new StringBuilder();
        diff.possibleRenames().forEach(r -> renames.append("<li>").append(escapeXml(r)).append("</li>"));
        return """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Diff de schema</title>
            <style>:root{color-scheme:light dark}body{font:15px system-ui;max-width:1100px;margin:auto;padding:2rem}
            table{border-collapse:collapse;width:100%%}th,td{padding:.5rem;border-bottom:1px solid #8885;text-align:left}
            .destructive{color:#c0392b;font-weight:bold}</style></head><body><h1>Diff de schema</h1>
            <p>%d mudança(s) · %d destrutiva(s) · gerado em %s</p>
            <ul>%s</ul>
            <table><thead><tr><th>Tipo</th><th>Objeto</th><th>Motivo</th><th>Destrutivo</th></tr></thead><tbody>%s</tbody></table>
            </body></html>
            """.formatted(diff.changes().size(), diff.destructiveChanges(), diff.generatedAt(), renames, rows);
    }

    public String flyway(Diff diff, String description) {
        return "-- Flyway: V" + System.currentTimeMillis() + "__" +
            description.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_") + ".sql\n" +
            diff.migrationSql();
    }

    public String liquibase(Diff diff, String id, String author) {
        StringBuilder xml = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
              <changeSet id="%s" author="%s">
            """.formatted(escapeXml(id), escapeXml(author)));
        diff.changes().forEach(c -> xml.append("    <sql splitStatements=\"true\"><![CDATA[")
            .append(c.sql()).append("]]></sql>\n"));
        return xml.append("  </changeSet>\n</databaseChangeLog>\n").toString();
    }

    private void parseColumn(MutableTable table, String definition) {
        Matcher matcher = COLUMN.matcher(definition);
        if (!matcher.matches()) return;
        String name = normalizeIdentifier(matcher.group(1));
        String type = normalizeSpace(matcher.group(2)).toUpperCase(Locale.ROOT);
        String options = matcher.group(3).trim();
        Matcher defaultMatcher = Pattern.compile("(?is)\\bdefault\\s+(.+?)(?=\\s+(?:not\\s+null|null|primary\\s+key|unique|references|check)\\b|$)")
            .matcher(options);
        String defaultValue = defaultMatcher.find() ? defaultMatcher.group(1).trim() : null;
        boolean primary = matches(options, "\\bprimary\\s+key\\b");
        boolean unique = matches(options, "\\bunique\\b");
        boolean identity = matches(options, "\\b(identity|auto_increment|serial)\\b") || type.matches("(BIG)?SERIAL");
        String references = null;
        Matcher reference = Pattern.compile("(?is)\\breferences\\s+([\\w.\"`]+)\\s*\\(([^)]+)\\)").matcher(options);
        if (reference.find()) references = normalizeIdentifier(reference.group(1)) + "(" + normalizeIdentifier(reference.group(2).trim()) + ")";
        Column column = new Column(name, type, matches(options, "\\bnot\\s+null\\b"), defaultValue,
            primary, unique, identity, references);
        table.columns.put(name, column);
        if (primary) table.constraints.put("pk_" + table.name,
            new Constraint("pk_" + table.name, "PRIMARY_KEY", List.of(name), null, null, ""));
        if (unique) table.constraints.put("uk_" + table.name + "_" + name,
            new Constraint("uk_" + table.name + "_" + name, "UNIQUE", List.of(name), null, null, ""));
        if (references != null) table.constraints.put("fk_" + table.name + "_" + name,
            new Constraint("fk_" + table.name + "_" + name, "FOREIGN_KEY", List.of(name),
                references.substring(0, references.indexOf('(')),
                List.of(references.substring(references.indexOf('(') + 1, references.length() - 1)), ""));
    }

    private void parseConstraint(MutableTable table, String definition) {
        Matcher named = Pattern.compile("(?is)^constraint\\s+([\\w.\"`]+)\\s+(.*)$").matcher(definition);
        String name = null, body = definition;
        if (named.matches()) { name = normalizeIdentifier(named.group(1)); body = named.group(2).trim(); }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.startsWith("primary key")) {
            List<String> columns = parenthesized(body);
            name = name == null ? "pk_" + table.name : name;
            table.constraints.put(name, new Constraint(name, "PRIMARY_KEY", columns, null, null, ""));
        } else if (lower.startsWith("unique")) {
            List<String> columns = parenthesized(body);
            name = name == null ? "uk_" + table.name + "_" + String.join("_", columns) : name;
            table.constraints.put(name, new Constraint(name, "UNIQUE", columns, null, null, ""));
        } else if (lower.startsWith("foreign key")) {
            Matcher fk = Pattern.compile("(?is)foreign\\s+key\\s*\\(([^)]+)\\)\\s+references\\s+([\\w.\"`]+)\\s*\\(([^)]+)\\)(.*)")
                .matcher(body);
            if (fk.matches()) {
                List<String> columns = splitNames(fk.group(1));
                name = name == null ? "fk_" + table.name + "_" + String.join("_", columns) : name;
                table.constraints.put(name, new Constraint(name, "FOREIGN_KEY", columns,
                    normalizeIdentifier(fk.group(2)), splitNames(fk.group(3)), normalizeSpace(fk.group(4))));
            }
        } else if (lower.startsWith("check")) {
            name = name == null ? "ck_" + table.name + "_" + Math.abs(body.hashCode()) : name;
            table.constraints.put(name, new Constraint(name, "CHECK", List.of(), null, null, normalizeSpace(body)));
        }
    }

    private void compareColumn(String table, Column wanted, Column old, List<Change> changes) {
        if (old == null) {
            changes.add(change("ADD_COLUMN", table + "." + wanted.name(),
                "ALTER TABLE " + table + " ADD COLUMN " + definition(wanted) + ";",
                "ALTER TABLE " + table + " DROP COLUMN " + wanted.name() + ";", false, "Coluna ausente"));
            return;
        }
        if (!canonicalType(old.type()).equals(canonicalType(wanted.type())) || old.notNull() != wanted.notNull()
            || !Objects.equals(normalizeDefault(old.defaultValue()), normalizeDefault(wanted.defaultValue()))) {
            boolean destructive = narrowing(old.type(), wanted.type()) || (!old.notNull() && wanted.notNull());
            changes.add(change("ALTER_COLUMN", table + "." + wanted.name(),
                "-- Adapte ao dialeto: ALTER TABLE " + table + " ALTER COLUMN " + definition(wanted) + ";",
                "-- Adapte ao dialeto: ALTER TABLE " + table + " ALTER COLUMN " + definition(old) + ";",
                destructive, destructive ? "Pode haver perda de dados ou falha por NULL" : "Tipo/default divergente"));
        }
    }

    private void compareConstraints(String table, Map<String, Constraint> desired,
                                    Map<String, Constraint> actual, List<Change> changes) {
        desired.forEach((name, constraint) -> {
            Constraint old = actual.get(name);
            if (old == null || !equivalent(constraint, old)) {
                if (old != null) changes.add(change("DROP_CONSTRAINT", table + "." + name,
                    "ALTER TABLE " + table + " DROP CONSTRAINT " + name + ";",
                    addConstraint(table, old), true, "Constraint divergente"));
                changes.add(change("ADD_CONSTRAINT", table + "." + name, addConstraint(table, constraint),
                    "ALTER TABLE " + table + " DROP CONSTRAINT " + name + ";", false, "Constraint ausente"));
            }
        });
        actual.forEach((name, constraint) -> {
            if (!desired.containsKey(name)) changes.add(change("DROP_CONSTRAINT", table + "." + name,
                "ALTER TABLE " + table + " DROP CONSTRAINT " + name + ";", addConstraint(table, constraint),
                true, "Constraint excedente"));
        });
    }

    private void compareIndexes(String table, Map<String, Index> desired,
                                Map<String, Index> actual, List<Change> changes) {
        desired.forEach((name, index) -> {
            Index old = actual.get(name);
            if (old == null) changes.add(change("CREATE_INDEX", table + "." + name, index.sql(),
                "DROP INDEX " + name + ";", false, "Índice ausente"));
            else if (!equivalent(index, old)) {
                changes.add(change("RECREATE_INDEX", table + "." + name,
                    "DROP INDEX " + name + ";\n" + index.sql(), "DROP INDEX " + name + ";\n" + old.sql(),
                    false, "Índice divergente"));
            }
        });
        actual.forEach((name, index) -> {
            if (!desired.containsKey(name)) changes.add(change("DROP_INDEX", table + "." + name,
                "DROP INDEX " + name + ";", index.sql(), false, "Índice excedente"));
        });
    }

    private void compareSequences(Schema desired, Schema actual, List<Change> changes) {
        desired.sequences().forEach((name, sequence) -> {
            if (!actual.sequences().containsKey(name)) changes.add(change("CREATE_SEQUENCE", name,
                sequence.sql(), "DROP SEQUENCE " + name + ";", false, "Sequence ausente"));
        });
        actual.sequences().forEach((name, sequence) -> {
            if (!desired.sequences().containsKey(name)) changes.add(change("DROP_SEQUENCE", name,
                "DROP SEQUENCE " + name + ";", sequence.sql(), true, "Sequence excedente"));
        });
    }

    private void compareViews(Schema desired, Schema actual, List<Change> changes) {
        desired.views().forEach((name, view) -> {
            View old = actual.views().get(name);
            if (old == null || !normalizeSpace(old.query()).equalsIgnoreCase(normalizeSpace(view.query())))
                changes.add(change(old == null ? "CREATE_VIEW" : "REPLACE_VIEW", name,
                    (old == null ? "CREATE " : "CREATE OR REPLACE ") + (view.materialized() ? "MATERIALIZED " : "") +
                        "VIEW " + name + " AS " + view.query() + ";",
                    old == null ? "DROP VIEW " + name + ";" : "CREATE OR REPLACE VIEW " + name + " AS " + old.query() + ";",
                    false, old == null ? "View ausente" : "Definição divergente"));
        });
        actual.views().forEach((name, view) -> {
            if (!desired.views().containsKey(name)) changes.add(change("DROP_VIEW", name,
                "DROP " + (view.materialized() ? "MATERIALIZED " : "") + "VIEW " + name + ";",
                "CREATE " + (view.materialized() ? "MATERIALIZED " : "") + "VIEW " + name + " AS " + view.query() + ";",
                true, "View excedente"));
        });
    }

    private List<Change> order(List<Change> changes) {
        Map<String, Integer> order = Map.ofEntries(
            Map.entry("CREATE_SEQUENCE", 10), Map.entry("CREATE_TABLE", 20),
            Map.entry("ADD_COLUMN", 30), Map.entry("ALTER_COLUMN", 40),
            Map.entry("ADD_CONSTRAINT", 50), Map.entry("CREATE_INDEX", 60),
            Map.entry("RECREATE_INDEX", 61), Map.entry("CREATE_VIEW", 70),
            Map.entry("REPLACE_VIEW", 71), Map.entry("DROP_VIEW", 80),
            Map.entry("DROP_INDEX", 90), Map.entry("DROP_CONSTRAINT", 100),
            Map.entry("DROP_COLUMN", 110), Map.entry("DROP_TABLE", 120),
            Map.entry("DROP_SEQUENCE", 130));
        return changes.stream().sorted(Comparator.comparingInt(c -> order.getOrDefault(c.kind(), 65))).toList();
    }

    private Change change(String kind, String object, String sql, String rollback,
                          boolean destructive, String reason) {
        return new Change(kind, object, ensureSemicolon(sql), ensureSemicolon(rollback), destructive, reason);
    }
    private String script(List<Change> changes, boolean rollback) {
        StringBuilder out = new StringBuilder("-- Gerado pelo SwissKnife Javanist\n");
        List<Change> source = rollback ? new ArrayList<>(changes) : changes;
        if (rollback) Collections.reverse(source);
        for (Change change : source) out.append("\n-- ").append(change.kind()).append(" ")
            .append(change.object()).append(change.destructive() ? " [DESTRUTIVO]" : "").append("\n")
            .append(rollback ? change.rollbackSql() : change.sql()).append("\n");
        return out.toString();
    }
    private String create(Table table) {
        List<String> definitions = new ArrayList<>(table.columns().values().stream().map(this::definition).toList());
        definitions.addAll(table.constraints().values().stream().map(this::constraintDefinition).toList());
        return "CREATE TABLE " + table.name() + " (\n  " + String.join(",\n  ", definitions) + "\n);";
    }
    private String definition(Column column) {
        return column.name() + " " + column.type() + (column.identity() ? " GENERATED BY DEFAULT AS IDENTITY" : "") +
            (column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue()) +
            (column.notNull() ? " NOT NULL" : "");
    }
    private String constraintDefinition(Constraint c) {
        String prefix = "CONSTRAINT " + c.name() + " ";
        return prefix + switch (c.kind()) {
            case "PRIMARY_KEY" -> "PRIMARY KEY (" + String.join(", ", c.columns()) + ")";
            case "UNIQUE" -> "UNIQUE (" + String.join(", ", c.columns()) + ")";
            case "FOREIGN_KEY" -> "FOREIGN KEY (" + String.join(", ", c.columns()) + ") REFERENCES " +
                c.referencedTable() + " (" + String.join(", ", c.referencedColumns()) + ")" +
                (c.expression().isBlank() ? "" : " " + c.expression());
            default -> c.expression();
        };
    }
    private String addConstraint(String table, Constraint constraint) {
        return "ALTER TABLE " + table + " ADD " + constraintDefinition(constraint) + ";";
    }
    private boolean equivalent(Constraint a, Constraint b) {
        return a.kind().equals(b.kind()) && a.columns().equals(b.columns()) &&
            Objects.equals(a.referencedTable(), b.referencedTable()) &&
            Objects.equals(a.referencedColumns(), b.referencedColumns()) &&
            normalizeSpace(a.expression()).equalsIgnoreCase(normalizeSpace(b.expression()));
    }
    private boolean equivalent(Index a, Index b) {
        return a.unique() == b.unique() && a.columns().equals(b.columns());
    }
    private boolean narrowing(String oldType, String newType) {
        Matcher oldSize = Pattern.compile("\\((\\d+)").matcher(oldType);
        Matcher newSize = Pattern.compile("\\((\\d+)").matcher(newType);
        return oldSize.find() && newSize.find() && Integer.parseInt(newSize.group(1)) < Integer.parseInt(oldSize.group(1));
    }
    private String canonicalType(String type) {
        return normalizeSpace(type).toUpperCase(Locale.ROOT).replace("CHARACTER VARYING", "VARCHAR")
            .replace("INT8", "BIGINT").replace("INT4", "INTEGER").replace("BOOL", "BOOLEAN");
    }
    private String normalizeDefault(String value) {
        return value == null ? null : normalizeSpace(value).replaceAll("::[\\w.]+$", "").toLowerCase(Locale.ROOT);
    }
    private boolean isConstraint(String definition) {
        return definition.toLowerCase(Locale.ROOT).matches("^(constraint\\s+\\S+\\s+)?(primary\\s+key|foreign\\s+key|unique|check).*");
    }
    private boolean matches(String value, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value).find();
    }
    private List<String> parenthesized(String value) {
        Matcher matcher = Pattern.compile("\\((.*)\\)", Pattern.DOTALL).matcher(value);
        return matcher.find() ? splitNames(matcher.group(1)) : List.of();
    }
    private List<String> splitNames(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).map(this::normalizeIdentifier).toList();
    }
    private List<String> splitDefinitions(String body) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        boolean single = false, dbl = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (!single && !dbl && c == '(') depth++;
            else if (!single && !dbl && c == ')') depth--;
            else if (!single && !dbl && c == ',' && depth == 0) {
                result.add(body.substring(start, i)); start = i + 1;
            }
        }
        result.add(body.substring(start));
        return result;
    }
    private String normalizeIdentifier(String value) { return value.replace("\"", "").replace("`", "").trim(); }
    private String normalizeSpace(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private String ensureSemicolon(String value) {
        String trimmed = value.stripTrailing();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }
    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class MutableTable {
        final String name;
        final Map<String, Column> columns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Map<String, Constraint> constraints = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Map<String, Index> indexes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        MutableTable(String name) { this.name = name; }
        Table freeze() {
            return new Table(name, caseInsensitiveCopy(columns), caseInsensitiveCopy(constraints), caseInsensitiveCopy(indexes));
        }
        private static <V> Map<String, V> caseInsensitiveCopy(Map<String, V> source) {
            Map<String, V> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            copy.putAll(source);
            return Collections.unmodifiableMap(copy);
        }
    }
    public record Column(String name, String type, boolean notNull, String defaultValue,
                         boolean primaryKey, boolean unique, boolean identity, String references) {}
    public record Constraint(String name, String kind, List<String> columns, String referencedTable,
                             List<String> referencedColumns, String expression) {}
    public record Index(String name, List<String> columns, boolean unique, String sql) {}
    public record Table(String name, Map<String, Column> columns,
                        Map<String, Constraint> constraints, Map<String, Index> indexes) {}
    public record Sequence(String name, String sql) {}
    public record View(String name, boolean materialized, String query) {}
    public record Schema(Map<String, Table> tables, Map<String, Sequence> sequences, Map<String, View> views) {}
    public record Change(String kind, String object, String sql, String rollbackSql,
                         boolean destructive, String reason) {}
    public record Diff(List<Change> changes, String migrationSql, String rollbackSql,
                       long destructiveChanges, String generatedAt, List<String> possibleRenames) {
        public boolean synchronizedAlready() { return changes.isEmpty(); }
    }
}
