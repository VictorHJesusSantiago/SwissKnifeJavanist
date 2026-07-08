package dev.swissknife.cli;

import java.util.List;

/**
 * Lista única de comandos da CLI (nome + descrição curta).
 * Usada por Main.help() e por CliTools.completion() para evitar duas listas divergentes.
 */
public final class CommandCatalog {
    private CommandCatalog() {}

    public record Command(String name, String description) {}

    public static final List<Command> COMMANDS = List.of(
        new Command("init", "Cria .swissknife.yml"),
        new Command("doctor", "Diagnostica o ambiente e a configuração"),
        new Command("cache-status", "Exibe uso do cache incremental"),
        new Command("cache-clear", "Limpa entradas geradas pelo cache"),
        new Command("version", "Exibe versões da suíte e do Java"),
        new Command("mfa-setup", "Gera segredo TOTP (RFC 6238) e URI de provisionamento para MFA"),
        new Command("mfa-verify", "Verifica um código TOTP de 6 dígitos contra o segredo"),
        new Command("completion", "Gera autocompletar do shell"),
        new Command("pipeline", "Executa uma sequência de comandos"),
        new Command("changed-files", "Lista arquivos alterados/novos segundo o Git (modo incremental de CI)"),
        new Command("docs", "Gera documentação Markdown de código Java"),
        new Command("docs-site", "Gera site HTML pesquisável e índice"),
        new Command("docs-diff", "Detecta breaking changes na API Java"),
        new Command("docs-changelog", "Gera changelog de API pública entre duas versões"),
        new Command("docs-uml", "Gera diagrama de classes UML (Mermaid)"),
        new Command("docs-package-diagram", "Gera diagrama de dependências entre pacotes (Mermaid)"),
        new Command("docs-mkdocs", "Exporta site MkDocs pronto para build"),
        new Command("deps", "Gera grafo Mermaid de microsserviços"),
        new Command("deps-export", "Exporta arquitetura em Mermaid/PlantUML/DOT/JSON/HTML/C4 (c4-context, c4-container)"),
        new Command("deps-rules", "Valida regras de domínio no grafo de dependências"),
        new Command("deps-compare", "Compara o grafo entre duas versões/branches"),
        new Command("deps-timeline", "Gera linha do tempo HTML da evolução arquitetural (N snapshots)"),
        new Command("sql-parse", "Exibe a AST de um SELECT via parser SQL real (não regex)"),
        new Command("slow-query", "Analisa SQL e sugere índices (parser real quando possível)"),
        new Command("slow-query-file", "Analisa lote de comandos SQL"),
        new Command("slow-query-log", "Extrai e analisa SQL de logs (com detecção de N+1)"),
        new Command("slow-query-report", "Gera relatório HTML consolidado de um lote de queries"),
        new Command("slow-query-index-script", "Gera script SQL com os índices sugeridos"),
        new Command("slow-query-plan", "Analisa plano EXPLAIN fornecido"),
        new Command("slow-query-explain", "Executa EXPLAIN via JDBC somente leitura"),
        new Command("schema-introspect", "Introspecta um banco vivo via JDBC (DatabaseMetaData)"),
        new Command("schema-diff-live", "Compara DDL desejado contra um banco vivo via JDBC"),
        new Command("schema-diff", "Compara dois arquivos DDL (com detecção de rename e --ignore)"),
        new Command("schema-diff-html", "Gera relatório HTML navegável do diff de schema"),
        new Command("schema-script", "Gera migration/rollback/Flyway/Liquibase"),
        new Command("anonymize", "Anonimiza dados CSV"),
        new Command("anonymize-json", "Anonimiza documentos JSON"),
        new Command("anonymize-preview", "Mostra amostra das transformações sem gravar o arquivo"),
        new Command("detect-pii", "Detecta possíveis dados pessoais em CSV"),
        new Command("contract-test", "Valida um contrato HTTP JSON (JSON Schema, cookies, form, paralelo)"),
        new Command("contract-mock", "Inicia um mock server local de stubs HTTP para testes offline"),
        new Command("gatling", "Gera uma Simulation Gatling"),
        new Command("gatling-project", "Gera projeto Maven Gatling executável"),
        new Command("debt", "Localiza débito técnico (com autor/idade via git blame)"),
        new Command("debt-report-html", "Gera relatório HTML de dívida técnica com links de arquivo"),
        new Command("dependency-audit", "Inventaria dependências, licenças e gera SBOM"),
        new Command("dependency-lockfile", "Gera catálogo de versões (lockfile) recomendado"),
        new Command("sbom", "Alias de dependency-audit"),
        new Command("quality", "Analisa qualidade e arquitetura Java"),
        new Command("security-scan", "Localiza segredos e configurações inseguras"),
        new Command("spring-audit", "Cataloga e valida aplicações Spring"),
        new Command("test-audit", "Detecta testes frágeis, lentos e sem assertions"),
        new Command("test-scaffold", "Gera esqueleto JUnit 5 a partir de uma classe Java"),
        new Command("test-rank-slow", "Ranking dos testes mais lentos a partir de um relatório JUnit XML"),
        new Command("test-flaky-detect", "Detecta testes flaky comparando execuções repetidas (JUnit XML)"),
        new Command("config-audit", "Compara ambientes e verifica configuração"),
        new Command("release-readiness", "Consolida quality gates de uma release"),
        new Command("modernize", "Planeja modernização de Java"),
        new Command("jvm-diagnose", "Analisa thread dumps, GC e logs (com trace ID e startup)"),
        new Command("jvm-diagnose-compare", "Compara dois thread dumps (antes/depois)"),
        new Command("jvm-processes", "Lista processos Java em execução localmente"),
        new Command("jvm-bundle", "Empacota arquivos de diagnóstico em um ZIP"),
        new Command("integrate", "Envia findings a webhooks e plataformas externas (com assinatura HMAC opcional)"),
        new Command("integrate-batch", "Envia um array de findings em lote ao mesmo destino"),
        new Command("ai-assist", "Explica/prioriza findings com IA opcional e redigida"),
        new Command("vuln-import", "Importa SARIF/CycloneDX/Semgrep com deduplicação"),
        new Command("vuln-transition", "Aplica workflow e histórico de vulnerabilidade"),
        new Command("vuln-report", "Gera aging (buckets), SLA e MTTR de vulnerabilidades"),
        new Command("vuln-bulk-transition", "Aplica workflow a vários IDs de uma vez"),
        new Command("vuln-delete", "Exclusão lógica de uma vulnerabilidade"),
        new Command("vuln-restore", "Restaura vulnerabilidade excluída logicamente"),
        new Command("vuln-review-accepted", "Reverte para OPEN riscos aceitos com prazo expirado"),
        new Command("vuln-merge", "Mescla duas vulnerabilidades duplicadas"),
        new Command("itam-import", "Importa e reconcilia inventário CSV"),
        new Command("itam-transition", "Registra checkout/devolução/manutenção/descarte"),
        new Command("itam-report", "Gera indicadores financeiros e operacionais (com depreciação)"),
        new Command("itam-bulk-transition", "Aplica a mesma ação a vários ativos de uma vez"),
        new Command("itam-delete", "Exclusão lógica de um ativo"),
        new Command("itam-restore", "Restaura ativo excluído logicamente"),
        new Command("migrate", "Migra uma tabela entre conexões JDBC"),
        new Command("migrate-plan", "Valida compatibilidade e volume antes da migração"),
        new Command("migrate-config", "Executa migração avançada configurada (com resume/createTarget/retries)"),
        new Command("migrate-verify", "Compara contagem de linhas entre origem e destino"),
        new Command("vuln-server", "Inicia API de vulnerabilidades"),
        new Command("itam-server", "Inicia API de ativos de TI"),
        new Command("portal-server", "Inicia o portal web local"),
        new Command("store-admin", "Verifica, copia, restaura e compacta JSON store")
    );

    public static List<String> names() { return COMMANDS.stream().map(Command::name).toList(); }

    /** Sugere o comando mais próximo por distância de Levenshtein, útil para mensagens de erro. */
    public static String suggest(String typed) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : names()) {
            int distance = levenshtein(typed, name);
            if (distance < bestDistance) { bestDistance = distance; best = name; }
        }
        return (best != null && bestDistance <= Math.max(2, typed.length() / 2)) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[a.length()][b.length()];
    }
}
