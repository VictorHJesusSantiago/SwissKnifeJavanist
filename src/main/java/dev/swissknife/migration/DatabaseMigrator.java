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
        int resumedFrom = config.resume() ? readCheckpoint(config.checkpointFile()) : 0;
        int toSkip = resumedFrom;
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
                if (config.createTarget() && !config.dryRun())
                    createTargetIfMissing(source, target, config);
                if (config.truncateTarget() && !config.dryRun() && resumedFrom == 0)
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
                        while (toSkip > 0 && rows.next()) toSkip--;
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
                                        BatchResult result = executeBatchWithRetry(insert, config, read, errors);
                                        written += result.written(); skipped += result.skipped(); batches++;
                                        checkpoint(config, resumedFrom + read, written, digest);
                                    }
                                } catch (Exception e) {
                                    if (config.errorPolicy().equalsIgnoreCase("FAIL")) throw e;
                                    errors.add(new RowError(resumedFrom + read, e.getMessage())); skipped++;
                                }
                            }
                            BatchResult result = executeBatchWithRetry(insert, config, read, errors);
                            written += result.written(); skipped += result.skipped(); batches++;
                            target.commit();
                        } catch (Exception e) {
                            target.rollback();
                            if (e instanceof SQLException sql) throw sql;
                            throw new SQLException("Falha ao transformar linha " + (resumedFrom + read), e);
                        }
                    }
                }
            }
            checksum = HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        if (config.rejectFile() != null && !errors.isEmpty())
            FilesEx.write(config.rejectFile(), errors.stream().map(error -> Json.stringify(error))
                .reduce((a,b)->a+"\n"+b).orElse("") + "\n");
        return new AdvancedReport(resumedFrom + read, written, skipped, batches, config.columnMappings().size(),
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
            path(p.getProperty("checkpointFile")), path(p.getProperty("rejectFile")),
            Boolean.parseBoolean(p.getProperty("resume", "false")),
            Boolean.parseBoolean(p.getProperty("createTarget", "false")), integer(p, "retries", 0));
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
        // Bancos como H2/Oracle/DB2 reportam nomes de coluna em MAIÚSCULAS por padrão via JDBC,
        // mesmo quando o usuário digitou o mapeamento/transformação em minúsculas na configuração;
        // por isso a busca precisa ser case-insensitive, nunca getOrDefault() direto.
        Map<String, String> columnMappings = caseInsensitive(config.columnMappings());
        Map<String, String> transforms = caseInsensitive(config.transforms());
        List<Mapping> result = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String source = metadata.getColumnLabel(i);
            String target = columnMappings.getOrDefault(source, source);
            if (target.equals("-") || target.equalsIgnoreCase("ignore")) continue;
            validateIdentifier(target);
            result.add(new Mapping(i, source, target, transforms.getOrDefault(source, "keep")));
        }
        return result;
    }
    private Map<String, String> caseInsensitive(Map<String, String> source) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        result.putAll(source);
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
    private BatchResult executeBatchWithRetry(PreparedStatement statement, AdvancedConfig config, int row,
                                              List<RowError> errors) throws SQLException {
        int attempts = Math.max(1, config.retries() + 1);
        SQLException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return executeBatch(statement, config.conflictPolicy(), row, errors);
            } catch (SQLTransientException | SQLRecoverableException e) {
                last = e;
                if (attempt < attempts) {
                    try { Thread.sleep(Math.min(2_000, 100L << attempt)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw e; }
                }
            }
        }
        throw last;
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
    private int readCheckpoint(Path checkpointFile) {
        if (checkpointFile == null || !Files.isRegularFile(checkpointFile)) return 0;
        try {
            Object parsed = Json.parse(Files.readString(checkpointFile));
            if (parsed instanceof Map<?, ?> map && map.get("read") instanceof Number n) return n.intValue();
        } catch (Exception ignored) { /* checkpoint corrompido é tratado como ausente */ }
        return 0;
    }
    private void createTargetIfMissing(Connection source, Connection target, AdvancedConfig config) throws SQLException {
        if (resolveTableName(target.getMetaData(), config.targetTable()) != null) return;
        String sourceTableActual = resolveTableName(source.getMetaData(), config.sourceTable());
        if (sourceTableActual == null)
            throw new IllegalArgumentException("Tabela de origem não encontrada no banco: " + config.sourceTable());
        Map<String, String> columnMappings = caseInsensitive(config.columnMappings());
        try (ResultSet sourceColumns = source.getMetaData().getColumns(null, null, sourceTableActual, null)) {
            List<String> definitions = new ArrayList<>();
            while (sourceColumns.next()) {
                String sourceName = sourceColumns.getString("COLUMN_NAME");
                String targetName = columnMappings.getOrDefault(sourceName, sourceName);
                if (targetName.equals("-") || targetName.equalsIgnoreCase("ignore")) continue;
                validateIdentifier(targetName);
                definitions.add(targetName + " " + genericSqlType(sourceColumns.getInt("DATA_TYPE"), sourceColumns.getInt("COLUMN_SIZE")));
            }
            if (definitions.isEmpty()) throw new IllegalArgumentException("Não foi possível ler colunas de " + config.sourceTable());
            try (Statement statement = target.createStatement()) {
                statement.executeUpdate("CREATE TABLE " + config.targetTable() + " (" + String.join(", ", definitions) + ")");
            }
        }
    }
    /**
     * Resolve o nome real da tabela nos metadados JDBC tentando o nome exato, depois MAIÚSCULO e
     * minúsculo — necessário porque muitos bancos (H2, Oracle) armazenam identificadores não citados
     * em maiúsculas, enquanto o usuário costuma digitar o nome em minúsculas na configuração.
     */
    private String resolveTableName(DatabaseMetaData meta, String requestedName) throws SQLException {
        for (String candidate : List.of(requestedName, requestedName.toUpperCase(Locale.ROOT), requestedName.toLowerCase(Locale.ROOT))) {
            try (ResultSet tables = meta.getTables(null, null, candidate, null)) {
                if (tables.next()) return candidate;
            }
        }
        return null;
    }
    private String genericSqlType(int jdbcType, int size) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.DECIMAL, Types.NUMERIC -> "DECIMAL";
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> "DOUBLE";
            case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.CLOB, Types.LONGVARCHAR -> "CLOB";
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "BLOB";
            default -> "VARCHAR(" + Math.max(1, Math.min(size, 4000)) + ")";
        };
    }
    /** Compara a contagem de linhas entre origem e destino após uma migração. */
    public VerifyReport verify(AdvancedConfig config) throws SQLException {
        try (Connection source = DriverManager.getConnection(config.sourceUrl(), config.sourceUser(), config.sourcePassword());
             Connection target = DriverManager.getConnection(config.targetUrl(), config.targetUser(), config.targetPassword())) {
            String where = config.whereClause().isBlank() ? "" : " WHERE " + safeWhere(config.whereClause());
            long sourceRows = count(source, config.sourceTable(), where);
            long targetRows = count(target, config.targetTable(), "");
            return new VerifyReport(sourceRows, targetRows, sourceRows == targetRows, sourceRows - targetRows);
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
    private static final Set<String> FORBIDDEN_WHERE_TOKENS = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "UNION", "INTO", "DROP", "ALTER", "CREATE",
        "TRUNCATE", "EXEC", "EXECUTE", "CALL", "GRANT", "REVOKE", "MERGE");
    /**
     * Tokeniza o WHERE com o mesmo parser usado para SQL arbitrário (SqlTokenizer), em vez de uma
     * blacklist de substrings — isso rejeita subqueries e statements encadeados mesmo quando ofuscados
     * com comentários/espaços, e evita falsos positivos para esses termos dentro de literais de string.
     */
    private String safeWhere(String where) {
        for (var token : new dev.swissknife.sql.SqlTokenizer().tokenize(where)) {
            String text = token.text().toUpperCase(Locale.ROOT);
            boolean forbiddenWord = (token.type() == dev.swissknife.sql.SqlTokenizer.Type.KEYWORD
                || token.type() == dev.swissknife.sql.SqlTokenizer.Type.IDENTIFIER) && FORBIDDEN_WHERE_TOKENS.contains(text);
            boolean statementSeparator = token.type() == dev.swissknife.sql.SqlTokenizer.Type.PUNCTUATION && text.equals(";");
            if (forbiddenWord || statementSeparator)
                throw new IllegalArgumentException("WHERE contém tokens não permitidos: " + token.text());
        }
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
                                 boolean dryRun, Path checkpointFile, Path rejectFile,
                                 boolean resume, boolean createTarget, int retries) {
        public AdvancedConfig {
            if (batchSize < 1) batchSize = 500;
            if (fetchSize < 1) fetchSize = batchSize;
            if (whereClause == null) whereClause = "";
            if (retries < 0) retries = 0;
            columnMappings = columnMappings == null ? Map.of() : Map.copyOf(columnMappings);
            transforms = transforms == null ? Map.of() : Map.copyOf(transforms);
        }
        public AdvancedConfig(String sourceUrl, String sourceUser, String sourcePassword, String sourceTable,
                              String targetUrl, String targetUser, String targetPassword, String targetTable,
                              int batchSize, int fetchSize, int limit, String whereClause,
                              Map<String, String> columnMappings, Map<String, String> transforms,
                              String conflictPolicy, String errorPolicy, boolean truncateTarget,
                              boolean dryRun, Path checkpointFile, Path rejectFile) {
            this(sourceUrl, sourceUser, sourcePassword, sourceTable, targetUrl, targetUser, targetPassword,
                targetTable, batchSize, fetchSize, limit, whereClause, columnMappings, transforms,
                conflictPolicy, errorPolicy, truncateTarget, dryRun, checkpointFile, rejectFile, false, false, 0);
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
    public record VerifyReport(long sourceRows, long targetRows, boolean matches, long divergence) {}
}
