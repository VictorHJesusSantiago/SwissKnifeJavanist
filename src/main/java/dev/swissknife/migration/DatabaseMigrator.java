package dev.swissknife.migration;

import dev.swissknife.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class DatabaseMigrator {
    public Report migrate(Config config) throws SQLException {
        Objects.requireNonNull(config.sourceUrl());
        Objects.requireNonNull(config.targetUrl());
        validateIdentifier(config.sourceTable());
        validateIdentifier(config.targetTable());
        int copied = 0;
        try (var source = DriverManager.getConnection(config.sourceUrl(), config.sourceUser(), config.sourcePassword());
             var target = DriverManager.getConnection(config.targetUrl(), config.targetUser(), config.targetPassword())) {
            target.setAutoCommit(false);
            try (var select = source.createStatement().executeQuery("SELECT * FROM " + config.sourceTable())) {
                var metadata = select.getMetaData();
                int columns = metadata.getColumnCount();
                String names = columnNames(metadata);
                String placeholders = String.join(",", Collections.nCopies(columns, "?"));
                String insertSql = "INSERT INTO " + config.targetTable() + " (" + names + ") VALUES (" + placeholders + ")";
                try (var insert = target.prepareStatement(insertSql)) {
                    while (select.next()) {
                        for (int i = 1; i <= columns; i++) insert.setObject(i, select.getObject(i));
                        insert.addBatch();
                        copied++;
                        if (copied % config.batchSize() == 0) insert.executeBatch();
                    }
                    insert.executeBatch();
                    target.commit();
                } catch (SQLException e) {
                    target.rollback();
                    throw e;
                }
                return new Report(copied, columns, config.sourceTable(), config.targetTable());
            }
        }
    }

    public Plan plan(Config config) throws SQLException {
        validateIdentifier(config.sourceTable()); validateIdentifier(config.targetTable());
        try (Connection source = DriverManager.getConnection(config.sourceUrl(), config.sourceUser(), config.sourcePassword());
             Connection target = DriverManager.getConnection(config.targetUrl(), config.targetUser(), config.targetPassword())) {
            List<ColumnInfo> sourceColumns = columns(source, config.sourceTable());
            List<ColumnInfo> targetColumns = columns(target, config.targetTable());
            Set<String> targetNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            targetColumns.forEach(column -> targetNames.add(column.name()));
            List<String> missing = sourceColumns.stream().map(ColumnInfo::name).filter(name -> !targetNames.contains(name)).toList();
            long sourceRows = count(source, config.sourceTable(), "");
            long targetRows = count(target, config.targetTable(), "");
            return new Plan(sourceColumns, targetColumns, missing, sourceRows, targetRows,
                missing.isEmpty(), "SELECT * FROM " + config.sourceTable(),
                "INSERT INTO " + config.targetTable() + " (" +
                    sourceColumns.stream().map(ColumnInfo::name).filter(targetNames::contains)
                        .reduce((a,b)->a+","+b).orElse("") + ") VALUES (...)");
        }
    }

    public AdvancedReport migrate(AdvancedConfig config) throws SQLException, IOException {
        validateIdentifier(config.sourceTable()); validateIdentifier(config.targetTable());
        config.columnMappings().forEach((from, to) -> { validateIdentifier(from); validateIdentifier(to); });
        long started = System.nanoTime();
        List<RowError> errors = new ArrayList<>();
        int read = 0, written = 0, skipped = 0, batches = 0;
        String checksum;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Connection source = DriverManager.getConnection(config.sourceUrl(), config.sourceUser(), config.sourcePassword());
                 Connection target = DriverManager.getConnection(config.targetUrl(), config.targetUser(), config.targetPassword())) {
                String selectSql = "SELECT * FROM " + config.sourceTable() +
                    (config.whereClause().isBlank() ? "" : " WHERE " + safeWhere(config.whereClause()));
                if (config.limit() > 0) source.setReadOnly(true);
                target.setAutoCommit(false);
                if (config.truncateTarget() && !config.dryRun())
                    try (Statement statement = target.createStatement()) { statement.executeUpdate("DELETE FROM " + config.targetTable()); }
                try (Statement statement = source.createStatement()) {
                    statement.setFetchSize(config.fetchSize());
                    if (config.limit() > 0) statement.setMaxRows(config.limit());
                    try (ResultSet rows = statement.executeQuery(selectSql)) {
                        ResultSetMetaData metadata = rows.getMetaData();
                        List<Mapping> mappings = mappings(metadata, config);
                        String insertSql = insertSql(config.targetTable(), mappings);
                        if (config.dryRun()) {
                            return new AdvancedReport(0, 0, 0, 0, mappings.size(), 0, List.of(),
                                HexFormat.of().formatHex(digest.digest()), true, selectSql, insertSql, 0);
                        }
                        try (PreparedStatement insert = target.prepareStatement(insertSql)) {
                            while (rows.next()) {
                                read++;
                                try {
                                    for (int i = 0; i < mappings.size(); i++) {
                                        Object value = transform(rows.getObject(mappings.get(i).sourceIndex()), mappings.get(i), config);
                                        insert.setObject(i + 1, value);
                                        updateDigest(digest, value);
                                    }
                                    insert.addBatch();
                                    if (read % config.batchSize() == 0) {
                                        BatchResult result = executeBatch(insert, config.conflictPolicy(), read, errors);
                                        written += result.written(); skipped += result.skipped(); batches++;
                                        checkpoint(config, read, written, digest);
                                    }
                                } catch (Exception e) {
                                    if (config.errorPolicy().equalsIgnoreCase("FAIL")) throw e;
                                    errors.add(new RowError(read, e.getMessage())); skipped++;
                                }
                            }
                            BatchResult result = executeBatch(insert, config.conflictPolicy(), read, errors);
                            written += result.written(); skipped += result.skipped(); batches++;
                            target.commit();
                        } catch (Exception e) {
                            target.rollback();
                            if (e instanceof SQLException sql) throw sql;
                            throw new SQLException("Falha ao transformar linha " + read, e);
                        }
                    }
                }
            }
            checksum = HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        if (config.rejectFile() != null && !errors.isEmpty())
            FilesEx.write(config.rejectFile(), errors.stream().map(error -> Json.stringify(error))
                .reduce((a,b)->a+"\n"+b).orElse("") + "\n");
        return new AdvancedReport(read, written, skipped, batches, config.columnMappings().size(),
            Duration.ofNanos(System.nanoTime() - started).toMillis(), errors, checksum, false,
            "SELECT", "INSERT", written == 0 ? 0 : written * 1000.0 /
                Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis()));
    }

    public AdvancedConfig load(Path propertiesFile) throws IOException {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(propertiesFile)) { p.load(reader); }
        Map<String, String> mappings = new LinkedHashMap<>(), transforms = new LinkedHashMap<>();
        p.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (name.startsWith("map.")) mappings.put(name.substring(4), String.valueOf(value));
            if (name.startsWith("transform.")) transforms.put(name.substring(10), String.valueOf(value));
        });
        return new AdvancedConfig(required(p, "source.url"), p.getProperty("source.user", ""),
            envOrValue(p, "source.password"), required(p, "source.table"),
            required(p, "target.url"), p.getProperty("target.user", ""),
            envOrValue(p, "target.password"), required(p, "target.table"),
            integer(p, "batchSize", 500), integer(p, "fetchSize", 500), integer(p, "limit", 0),
            p.getProperty("where", ""), mappings, transforms, p.getProperty("conflictPolicy", "FAIL"),
            p.getProperty("errorPolicy", "FAIL"), Boolean.parseBoolean(p.getProperty("truncateTarget", "false")),
            Boolean.parseBoolean(p.getProperty("dryRun", "false")),
            path(p.getProperty("checkpointFile")), path(p.getProperty("rejectFile")));
    }

    public ExportReport export(ConnectionConfig connection, String table, String where,
                               Path output, String format) throws SQLException, IOException {
        validateIdentifier(table);
        int rows = 0, columnCount = 0;
        try (Connection database = DriverManager.getConnection(connection.url(), connection.user(), connection.password());
             Statement statement = database.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table +
                 (where == null || where.isBlank() ? "" : " WHERE " + safeWhere(where)))) {
            ResultSetMetaData metadata = result.getMetaData(); columnCount = metadata.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) columns.add(metadata.getColumnLabel(i));
            StringBuilder content = new StringBuilder();
            if (format.equalsIgnoreCase("csv")) content.append(Csv.line(columns)).append("\n");
            else if (format.equalsIgnoreCase("json")) content.append("[");
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) row.put(columns.get(i - 1), result.getObject(i));
                if (format.equalsIgnoreCase("csv")) content.append(Csv.line(row.values().stream().map(String::valueOf).toList())).append("\n");
                else if (format.equalsIgnoreCase("jsonl")) content.append(Json.stringify(row)).append("\n");
                else if (format.equalsIgnoreCase("json")) content.append(rows++ > 0 ? "," : "").append(Json.stringify(row));
                else throw new IllegalArgumentException("Formato de exportação: csv, json ou jsonl");
                if (!format.equalsIgnoreCase("json")) rows++;
            }
            if (format.equalsIgnoreCase("json")) content.append("]");
            FilesEx.write(output, content.toString());
        }
        return new ExportReport(rows, columnCount, output, format);
    }

    private String columnNames(ResultSetMetaData metadata) throws SQLException {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            var name = metadata.getColumnLabel(i);
            validateIdentifier(name);
            names.add(name);
        }
        return String.join(",", names);
    }
    private void validateIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_$.]*"))
            throw new IllegalArgumentException("Identificador SQL inválido: " + value);
    }

    private List<ColumnInfo> columns(Connection connection, String table) throws SQLException {
        List<ColumnInfo> result = new ArrayList<>();
        String schema = null, name = table;
        int dot = table.indexOf('.');
        if (dot > 0) { schema = table.substring(0, dot); name = table.substring(dot + 1); }
        try (ResultSet columns = connection.getMetaData().getColumns(null, schema, name, null)) {
            while (columns.next()) result.add(new ColumnInfo(columns.getString("COLUMN_NAME"),
                columns.getString("TYPE_NAME"), columns.getInt("DATA_TYPE"), columns.getInt("COLUMN_SIZE"),
                columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
        }
        return result;
    }
    private long count(Connection connection, String table, String where) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table + where)) {
            result.next(); return result.getLong(1);
        }
    }
    private List<Mapping> mappings(ResultSetMetaData metadata, AdvancedConfig config) throws SQLException {
        List<Mapping> result = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String source = metadata.getColumnLabel(i);
            String target = config.columnMappings().getOrDefault(source, source);
            if (target.equals("-") || target.equalsIgnoreCase("ignore")) continue;
            validateIdentifier(target);
            result.add(new Mapping(i, source, target, config.transforms().getOrDefault(source, "keep")));
        }
        return result;
    }
    private String insertSql(String table, List<Mapping> mappings) {
        return "INSERT INTO " + table + " (" + String.join(",", mappings.stream().map(Mapping::target).toList()) +
            ") VALUES (" + String.join(",", Collections.nCopies(mappings.size(), "?")) + ")";
    }
    private Object transform(Object value, Mapping mapping, AdvancedConfig config) {
        if (value == null) return null;
        String strategy = mapping.transform().toLowerCase(Locale.ROOT);
        if (strategy.equals("keep")) return value;
        if (strategy.equals("string")) return String.valueOf(value);
        if (strategy.equals("uppercase")) return String.valueOf(value).toUpperCase(Locale.ROOT);
        if (strategy.equals("lowercase")) return String.valueOf(value).toLowerCase(Locale.ROOT);
        if (strategy.equals("trim")) return String.valueOf(value).trim();
        if (strategy.equals("null")) return null;
        if (strategy.startsWith("prefix:")) return mapping.transform().substring(7) + value;
        if (strategy.startsWith("constant:")) return mapping.transform().substring(9);
        throw new IllegalArgumentException("Transformação desconhecida em " + mapping.source() + ": " + mapping.transform());
    }
    private BatchResult executeBatch(PreparedStatement statement, String conflictPolicy, int row,
                                     List<RowError> errors) throws SQLException {
        try {
            int[] updates = statement.executeBatch();
            int written = (int) Arrays.stream(updates).filter(value -> value != Statement.EXECUTE_FAILED).count();
            return new BatchResult(written, updates.length - written);
        } catch (BatchUpdateException e) {
            if (!conflictPolicy.equalsIgnoreCase("SKIP")) throw e;
            errors.add(new RowError(row, "Lote ignorado por conflito: " + e.getMessage()));
            statement.clearBatch();
            return new BatchResult(0, Math.max(1, e.getUpdateCounts().length));
        }
    }
    private void checkpoint(AdvancedConfig config, int read, int written, MessageDigest digest) throws IOException {
        if (config.checkpointFile() == null) return;
        FilesEx.write(config.checkpointFile(), Json.stringify(Map.of("read", read, "written", written,
            "table", config.sourceTable(), "updatedAt", Instant.now().toString())) + "\n");
    }
    private void updateDigest(MessageDigest digest, Object value) {
        digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }
    private String safeWhere(String where) {
        if (where.contains(";") || where.contains("--") || where.contains("/*"))
            throw new IllegalArgumentException("WHERE contém tokens não permitidos");
        return where;
    }
    private String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " é obrigatório");
        return value;
    }
    private String envOrValue(Properties p, String prefix) {
        String env = p.getProperty(prefix + "Env");
        if (env != null && !env.isBlank()) {
            String value = System.getenv(env);
            if (value == null) throw new IllegalArgumentException("Variável ausente: " + env);
            return value;
        }
        return p.getProperty(prefix, "");
    }
    private int integer(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(fallback))); }
        catch (Exception e) { return fallback; }
    }
    private Path path(String value) { return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize(); }
    public record Config(String sourceUrl, String sourceUser, String sourcePassword, String sourceTable,
                         String targetUrl, String targetUser, String targetPassword, String targetTable,
                         int batchSize) {
        public Config {
            if (batchSize < 1) batchSize = 500;
        }
    }
    public record Report(int copiedRows, int columns, String sourceTable, String targetTable) {}
    public record ColumnInfo(String name, String typeName, int jdbcType, int size, boolean nullable) {}
    public record Plan(List<ColumnInfo> sourceColumns, List<ColumnInfo> targetColumns,
                       List<String> missingTargetColumns, long sourceRows, long targetRows,
                       boolean compatible, String selectSql, String insertPreview) {}
    public record ConnectionConfig(String url, String user, String password) {}
    public record AdvancedConfig(String sourceUrl, String sourceUser, String sourcePassword, String sourceTable,
                                 String targetUrl, String targetUser, String targetPassword, String targetTable,
                                 int batchSize, int fetchSize, int limit, String whereClause,
                                 Map<String, String> columnMappings, Map<String, String> transforms,
                                 String conflictPolicy, String errorPolicy, boolean truncateTarget,
                                 boolean dryRun, Path checkpointFile, Path rejectFile) {
        public AdvancedConfig {
            if (batchSize < 1) batchSize = 500;
            if (fetchSize < 1) fetchSize = batchSize;
            if (whereClause == null) whereClause = "";
            columnMappings = columnMappings == null ? Map.of() : Map.copyOf(columnMappings);
            transforms = transforms == null ? Map.of() : Map.copyOf(transforms);
        }
    }
    private record Mapping(int sourceIndex, String source, String target, String transform) {}
    private record BatchResult(int written, int skipped) {}
    public record RowError(int row, String error) {}
    public record AdvancedReport(int readRows, int writtenRows, int skippedRows, int batches,
                                 int mappedColumns, long durationMs, List<RowError> errors,
                                 String checksum, boolean dryRun, String selectSql,
                                 String insertSql, double rowsPerSecond) {}
    public record ExportReport(int rows, int columns, Path output, String format) {}
}
