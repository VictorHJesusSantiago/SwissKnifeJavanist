package dev.swissknife.schema;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class SchemaComparator {
    private static final Pattern CREATE = Pattern.compile(
        "(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.]+)\\s*\\((.*?)\\)\\s*;");

    public Schema parse(Path ddl) throws IOException { return parse(Files.readString(ddl)); }
    public Schema parse(String ddl) {
        Map<String, Table> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        var matcher = CREATE.matcher(ddl);
        while (matcher.find()) {
            Map<String, Column> columns = new LinkedHashMap<>();
            for (var definition : splitDefinitions(matcher.group(2))) {
                var bits = definition.trim().split("\\s+", 3);
                if (bits.length < 2 || Set.of("primary", "foreign", "unique", "constraint", "check").contains(bits[0].toLowerCase())) continue;
                columns.put(bits[0], new Column(bits[0], bits[1], definition.toLowerCase().contains("not null")));
            }
            tables.put(matcher.group(1), new Table(matcher.group(1), columns));
        }
        return new Schema(tables);
    }

    public Diff compare(Schema desired, Schema actual) {
        List<Change> changes = new ArrayList<>();
        desired.tables().forEach((name, table) -> {
            var current = actual.tables().get(name);
            if (current == null) {
                changes.add(new Change("CREATE_TABLE", name, create(table), false));
                return;
            }
            table.columns().forEach((columnName, column) -> {
                var old = current.columns().get(columnName);
                if (old == null) changes.add(new Change("ADD_COLUMN", name + "." + columnName,
                    "ALTER TABLE " + name + " ADD COLUMN " + definition(column) + ";", false));
                else if (!old.type().equalsIgnoreCase(column.type()) || old.notNull() != column.notNull())
                    changes.add(new Change("ALTER_COLUMN", name + "." + columnName,
                        "-- Revise para seu SGBD: ALTER TABLE " + name + " ALTER COLUMN " + definition(column) + ";", true));
            });
        });
        actual.tables().forEach((name, table) -> {
            if (!desired.tables().containsKey(name))
                changes.add(new Change("DROP_TABLE", name, "DROP TABLE " + name + ";", true));
            else table.columns().forEach((column, ignored) -> {
                if (!desired.tables().get(name).columns().containsKey(column))
                    changes.add(new Change("DROP_COLUMN", name + "." + column,
                        "ALTER TABLE " + name + " DROP COLUMN " + column + ";", true));
            });
        });
        return new Diff(changes);
    }

    private List<String> splitDefinitions(String body) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '(') depth++;
            else if (body.charAt(i) == ')') depth--;
            else if (body.charAt(i) == ',' && depth == 0) { result.add(body.substring(start, i)); start = i + 1; }
        }
        result.add(body.substring(start));
        return result;
    }
    private String create(Table t) { return "CREATE TABLE " + t.name() + " (" + t.columns().values().stream().map(this::definition).reduce((a,b)->a+", "+b).orElse("") + ");"; }
    private String definition(Column c) { return c.name() + " " + c.type() + (c.notNull() ? " NOT NULL" : ""); }
    public record Column(String name, String type, boolean notNull) {}
    public record Table(String name, Map<String, Column> columns) {}
    public record Schema(Map<String, Table> tables) {}
    public record Change(String kind, String object, String sql, boolean destructive) {}
    public record Diff(List<Change> changes) { public boolean synchronizedAlready() { return changes.isEmpty(); } }
}
