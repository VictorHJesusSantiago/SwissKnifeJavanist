# CLAUDE.md — SwissKnife Javanist

## Arquitetura

Suíte de governança Java distribuída como **uma CLI sem dependências externas** (`dev.swissknife.Main`
despacha ~70 subcomandos por `switch`) mais **dois backends Spring Boot opcionais** em
`spring-backends/`. O núcleo em `src/main/java` compila apenas com o JDK 21 — HTTP é
`com.sun.net.httpserver`, JSON é `dev.swissknife.util.Json`, testes são um runner próprio
(`AllTests`). Persistência local é o `JsonStore`: um JSONL reescrito por inteiro a cada gravação, com
trilha de auditoria encadeada por SHA-256 num arquivo irmão. O `PortalServer` expõe um painel web
(HTML/CSS/JS embutidos em text blocks) que executa uma **allowlist** de análises como jobs assíncronos
em virtual threads, reentrando em `Main.execute` dentro do mesmo processo.

## Convenções não óbvias (preserve)

- **Zero dependências externas em `src/main/java`.** `build.ps1`/`build.sh` chamam `javac` direto; não
  há Maven/Gradle no núcleo. Adicionar uma dependência quebra o build e o Dockerfile.
- **Idioma:** identificadores em inglês, comentários e mensagens ao usuário em **PT-BR**. Mantenha.
- **Compila sem warnings** sob `-Xlint:all`. Trate warning como erro.
- **Todo comando novo** precisa entrar em `CommandCatalog` (help/completion) e, se for análise
  somente-leitura, na allowlist de `JobManager.ALLOWED`.
- **`Main.execute` é reentrante e roda concorrente** (jobs do portal, `pipeline --parallel`). Não
  introduza estado estático mutável no caminho dos comandos.
- Records são o default para DTOs/relatórios; `Json.stringify` serializa records por reflexão.
- Testes: adicione o método em uma classe `*Test` com `public static void run()` e registre em
  `AllTests`. Regressões de auditoria vão em `AuditRegressionTest`, um teste por defeito.

## Watchlist de anti-padrões (NUNCA faça aqui)

1. **Nunca interpole valor não confiável em HTML sem escapar.** `SqlTokenizer` devolve identificadores
   entre aspas com o texto bruto — `FROM "<img src=x onerror=...>"` chega intacto em
   `Analysis.table()`. Todo gerador de relatório HTML precisa de `escape()` no sink. (Foi XSS
   armazenado real em `SlowQueryAnalyzer.html`.)
2. **Nunca use `hashCode()` como identidade, fingerprint, ETag ou hash de corpo.** São 32 bits; uma
   colisão aqui funde vulnerabilidades distintas ou devolve a resposta de outra requisição. Use
   SHA-256 (há helpers em `HttpSupport`, `JsonStore`, `GovernanceDataManager`).
3. **Nunca use `java.util.Random` em caminho de anonimização/segurança.** Use `SecureRandom` — é
   inclusive a regra `INSECURE_RANDOM` do `SecurityScanner` deste repositório.
4. **Nunca abra um `JsonStore` dentro de um laço.** Cada construção relê o banco inteiro e todo o log
   de auditoria. Operações em lote abrem um store e o reutilizam.
5. **Nunca adicione um endpoint de escrita sem `HttpSupport.requireScope(exchange, "write", "admin")`**,
   inclusive no portal — submeter job é escrita (grava artefatos em disco).
6. **Nunca escreva as palavras `TODO`/`FIXME` em prosa portuguesa** dentro de `src/`: o
   `TechnicalDebtTracker` casa substring e passa a reportar o próprio comentário como dívida.
7. **Nunca retome uma migração (`resume=true`) sem `orderBy`.** Retomar pula N linhas de um SELECT;
   sem `ORDER BY` a ordem não é estável entre execuções e a retomada duplica e perde registros.

## Inventário de dívida técnica (auditoria de 2026-07-23)

Corrigido nesta auditoria: escape de caracteres de controle em `Json`, limite de profundidade do
parser JSON, escopo do `SWISSKNIFE_API_TOKEN`, NPE no DELETE de coleção, cadeia de auditoria após
rotação, `requireScope` no portal, `SecureRandom` no shuffle, fingerprint SHA-256, lotes O(n²),
XSS no relatório de queries lentas, paginação nos dois serviços Spring.

Aberto, em ordem de prioridade:

1. **`JsonStore` reescreve o arquivo inteiro a cada `save()`** e mantém tudo em memória. É O(n) por
   gravação e não sobrevive a volumes de produção. É o gargalo estrutural do núcleo.
2. **Dinheiro como `double`** em `GovernanceDataManager` (`purchaseValue`, `maintenanceCostTotal`,
   depreciação). Os serviços Spring já usam `BigDecimal` — o núcleo diverge.
3. **`SecurityScanner` tem alta taxa de falso positivo** e não se exclui: das 19 ocorrências neste
   repositório, 5 são as próprias regex de regra e a maioria do resto são identificadores que
   *referenciam* segredos, não literais. O CI publica isso no GitHub code scanning.
4. **Injeção de fórmula em CSV** (`Csv.escape`, `OutputFormatter.csvCell`): valores iniciados por
   `= + - @` executam ao abrir no Excel. Não foi corrigido de propósito — prefixar aspas corromperia
   números negativos legítimos no `migrate`/`anonymize`. Corrija apenas nos sinks de relatório.
5. **`Specification.where(null)`** está deprecado no Spring Data 3.5 (único warning dos módulos Spring).
6. **`safeWhere`/`safeOrderBy` são allowlist sintática, não parametrização.** Aceitável para uma
   ferramenta de operador sobre o próprio banco; não exponha isso a entrada de usuário final.
7. **Sem cobertura de teste para o caminho autenticado.** `ServerTest` só exercita o modo aberto
   (sem token), que é justamente por onde passou a regressão de escopo.

## Pegadinhas para quem chega agora

- `SWISSKNIFE_*` vira chave de configuração automaticamente em `CliConfig.load` (exceto
  `SWISSKNIFE_API_TOKEN`). Uma variável de ambiente nova pode sobrescrever configuração sem querer.
- Servidores escutam só em `127.0.0.1` e, **sem token configurado, o escopo efetivo é `admin`** —
  aberto por padrão em uso local, por decisão de projeto.
- `JobManager` usa `newFixedThreadPool` com fábrica de virtual threads: funciona, mas o idioma correto
  seria `newVirtualThreadPerTaskExecutor` + `Semaphore` para limitar concorrência.
- `build/` e `data/` contêm artefatos versionados; não confie neles como fonte da verdade.
- Códigos de saída: `0` limpo, `2` alertas, `3` política reprovou. `--no-fail` desliga.
