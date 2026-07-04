package dev.swissknife;

import dev.swissknife.schema.SchemaComparator;

public final class SchemaTest {
    public static void run() {
        var c = new SchemaComparator();
        var wanted = c.parse("CREATE TABLE users (id BIGINT NOT NULL, email VARCHAR(200));");
        var actual = c.parse("CREATE TABLE users (id BIGINT NOT NULL);");
        var diff = c.compare(wanted, actual);
        TestSupport.equal(1, diff.changes().size());
        TestSupport.equal("ADD_COLUMN", diff.changes().getFirst().kind());
    }
}
