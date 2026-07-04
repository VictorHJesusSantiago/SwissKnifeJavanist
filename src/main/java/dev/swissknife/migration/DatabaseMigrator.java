package dev.swissknife.migration;

import java.sql.*;
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
    public record Config(String sourceUrl, String sourceUser, String sourcePassword, String sourceTable,
                         String targetUrl, String targetUser, String targetPassword, String targetTable,
                         int batchSize) {
        public Config {
            if (batchSize < 1) batchSize = 500;
        }
    }
    public record Report(int copiedRows, int columns, String sourceTable, String targetTable) {}
}
