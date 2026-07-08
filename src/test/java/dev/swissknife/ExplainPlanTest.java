package dev.swissknife;

import dev.swissknife.sql.ExplainPlanAnalyzer;

public final class ExplainPlanTest {
    public static void run() {
        var analyzer = new ExplainPlanAnalyzer();
        var plan = analyzer.analyzePlan("""
            Seq Scan on orders  (cost=0.00..1550.00 rows=50000 width=32)
              Filter: (customer_id = 10)
              Sort Method: external merge  Disk: 2048kB
              actual time=1.000..125.500 rows=25 loops=1
            """, ExplainPlanAnalyzer.Dialect.POSTGRESQL);
        TestSupport.truth(plan.findings().stream().anyMatch(f -> f.code().equals("SEQUENTIAL_SCAN")), "Seq scan não detectado");
        TestSupport.truth(plan.findings().stream().anyMatch(f -> f.code().equals("DISK_SORT")), "Sort em disco não detectado");
        TestSupport.truth(plan.findings().stream().anyMatch(f -> f.code().equals("CARDINALITY_MISMATCH")), "Cardinalidade não detectada");
        TestSupport.equal(50000L, plan.estimatedRows());
        boolean rejected = false;
        try { analyzer.validateQuery("DELETE FROM orders"); } catch (IllegalArgumentException e) { rejected = true; }
        TestSupport.truth(rejected, "DML deveria ser recusado");
        analyzer.validateQuery("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent");
    }
}
