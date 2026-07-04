package dev.swissknife;

import dev.swissknife.sql.SlowQueryAnalyzer;

public final class AnalyzerTest {
    public static void run() {
        var result = new SlowQueryAnalyzer().analyze(
            "SELECT * FROM orders WHERE customer_id = ? ORDER BY created_at");
        TestSupport.equal("orders", result.table());
        TestSupport.equal(2, result.columns().size());
        TestSupport.truth(result.suggestedIndexes().getFirst().contains("customer_id, created_at"), "Índice composto esperado");
        TestSupport.truth(!result.warnings().isEmpty(), "SELECT * deveria produzir alerta");
    }
}
