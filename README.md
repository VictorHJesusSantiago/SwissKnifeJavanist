# SwissKnife Javanist

Uma suíte de produtividade e governança para times Java, distribuída como uma
CLI única e dois backends HTTP.

## Ferramentas incluídas

1. `docs` — gera documentação Markdown a partir de código Java.
2. `deps` — visualiza dependências entre microsserviços em Mermaid.
3. `slow-query` — analisa SQL e sugere índices.
4. `schema-diff` — compara e gera plano de sincronização de schemas.
5. `anonymize` — anonimiza CSV com políticas configuráveis.
6. `contract-test` — executa contratos HTTP entre serviços.
7. `vuln-server` — backend de gestão de vulnerabilidades.
8. `gatling` — gera cenários de carga Gatling.
9. `debt` — rastreia TODOs, FIXMEs e outros sinais de débito técnico.
10. `migrate` — migra dados entre bancos JDBC heterogêneos.
11. `itam-server` — backend de gestão de ativos de TI.
12. `intellij-plugin/` — plugin IntelliJ que integra ações da suíte.
13. `dependency-audit`/`sbom` — inventaria Maven/Gradle e gera CycloneDX/SPDX.
14. `quality` — métricas, problemas de qualidade e ciclos entre pacotes.
15. `security-scan` — segredos, configurações inseguras e certificados.
16. `spring-audit` — catálogo de componentes/endpoints e políticas Spring.
17. `modernize` — plano de modernização para Java/Jakarta/JUnit.
18. `jvm-diagnose` — análise offline de thread dumps, GC e logs.
19. `portal-server` — painel web local, responsivo e instalável.

As versões corporativas dos backends em Spring Boot, com JPA, Flyway, H2,
Actuator e testes MockMvc, ficam em `spring-backends/`.

## Requisitos

- JDK 21 ou superior
- PowerShell 7+ no Windows

## Compilar e testar

```powershell
./build.cmd
./test.cmd
```

## Uso

```powershell
./swissknife.cmd help
./swissknife.cmd docs ./src ./docs/API.md
./swissknife.cmd deps ./examples/services ./docs/services.mmd
./swissknife.cmd slow-query "SELECT * FROM orders WHERE customer_id = ? ORDER BY created_at"
./swissknife.cmd slow-query-plan postgresql ./explain.txt
./swissknife.cmd slow-query-explain postgresql jdbc:postgresql://localhost/app app DB_PASSWORD false "SELECT * FROM orders"
./swissknife.cmd schema-diff examples/schema/source.sql examples/schema/target.sql
./swissknife.cmd anonymize examples/data/people.csv examples/data/policy.properties ./out.csv
./swissknife.cmd debt ./src
./swissknife.cmd vuln-server 8081 ./data/vulnerabilities.db
./swissknife.cmd itam-server 8082 ./data/assets.db
./swissknife.cmd dependency-audit . --format json
./swissknife.cmd quality src --format sarif --output build/quality.sarif
./swissknife.cmd security-scan . --format sarif --output build/security.sarif
./swissknife.cmd spring-audit . --format html --output build/spring.html
./swissknife.cmd modernize . --target 21
./swissknife.cmd jvm-diagnose ./logs/gc.log
./swissknife.cmd portal-server 8080
./swissknife.cmd docs-site ./src/main/java ./build/docs-site
./swissknife.cmd docs-diff ./versao-anterior/src ./src
./swissknife.cmd deps-export . ./build/architecture.html html
./swissknife.cmd schema-script desired.sql current.sql migration.sql flyway
./swissknife.cmd gatling-project examples/load/orders.json ./build/load-project
./swissknife.cmd migrate-config examples/migration/migration.properties
./swissknife.cmd integrate examples/integrations/github.properties examples/integrations/finding.json
./swissknife.cmd test-audit .
./swissknife.cmd config-audit ./config/dev ./config/prod
./swissknife.cmd release-readiness .
./swissknife.cmd vuln-import ./data/vulnerabilities.db ./results.sarif sarif
./swissknife.cmd vuln-report ./data/vulnerabilities.db
./swissknife.cmd itam-import ./data/assets.db ./inventory.csv
./swissknife.cmd itam-transition ./data/assets.db <id> checkout usuario
./swissknife.cmd store-admin ./data/assets.db backup ./backup/assets.db
./swissknife.cmd ai-assist examples/ai/local-ollama.properties explain ./report.json
```

## Plataforma da CLI

A configuração é mesclada nesta ordem: `~/.swissknife.yml`, configuração do
projeto, `--config`, perfil, variáveis `SWISSKNIFE_*` e argumentos `--set`.

```powershell
./swissknife.cmd init
./swissknife.cmd doctor --format text
./swissknife.cmd completion powershell
./swissknife.cmd pipeline ./pipeline.txt --format html --output ./report.html
```

Formatos disponíveis: JSON, texto, YAML, CSV, XML, HTML, Markdown, SARIF e
JUnit XML. `--output -` mantém a saída no terminal e `--quiet` a suprime.

Os códigos de saída são `0` para execução limpa, `2` quando há alertas e `3`
quando uma política reprova o resultado. Use `--no-fail` para análises
exploratórias. O cache incremental fica em `.swissknife/cache`; consulte com
`cache-status` e limpe apenas seus artefatos com `cache-clear`.

Um pipeline possui um comando por linha:

```text
quality src/main/java
security-scan .
dependency-audit .
spring-audit spring-backends
```

## Recursos avançados

- `docs` usa a AST oficial do JDK e documenta tipos, membros, herança,
  annotations, exceções e cobertura documental.
- `schema-diff` compara colunas, defaults, PK/FK/UNIQUE/CHECK, índices,
  sequences e views, incluindo rollback.
- `deps-export` descobre Maven, Gradle, Docker, Kubernetes, Feign, HTTP, Kafka
  e RabbitMQ e calcula ciclos, impacto e caminho crítico.
- `debt` aceita `.swissknife-debt.properties` com marcadores, extensões,
  responsáveis, tickets, prazos e quality gates.
- `migrate-config` fornece dry-run, transformações, checkpoints, checksum,
  rejeitados e políticas de conflito.
- `integrate` opera em dry-run por padrão. Acrescente `--send` somente após
  revisar URL, headers e preview redigido.
- `ai-assist` também opera em dry-run. O envio exige simultaneamente `--send`
  e `--consent`; segredos são redigidos antes da montagem da requisição.
- `vuln-import` entende SARIF, CycloneDX, Semgrep e JSON genérico, deduplicando
  findings por fingerprint.
- `store-admin` mantém trilha de auditoria encadeada por SHA-256 e valida
  backups antes do restore.
- O portal executa uma allowlist de análises em jobs assíncronos, com
  histórico persistente, cancelamento, métricas e autenticação Bearer.

## Distribuição

- Windows: `./install.ps1`
- Linux/macOS: `chmod +x build.sh install.sh && ./install.sh`
- Docker: `docker build -t swissknife-javanist .`
- GitHub Actions: `uses: sua-organizacao/SwissKnifeJavanist@v1`

As APIs respondem JSON. Em ambos os servidores, `GET /health` fornece o estado
do processo. Consulte [docs/API.md](docs/API.md) para as rotas.
Consulte também [docs/VALIDATION.md](docs/VALIDATION.md) para a matriz que liga
cada produto à implementação e aos testes automatizados correspondentes.

## Estrutura

- `src/main/java` — código de produção sem dependências externas.
- `src/test/java` — testes executáveis pelo test runner interno.
- `examples` — entradas reproduzíveis para cada ferramenta.
- `intellij-plugin` — projeto Gradle independente para IntelliJ Platform.
- `docs` — arquitetura, API e guia operacional.

## Segurança

Os servidores escutam em `127.0.0.1` por padrão. Defina
`SWISSKNIFE_API_TOKEN` para exigir o header `Authorization: Bearer <token>`.
Credenciais JDBC devem ser passadas por variáveis de ambiente.
