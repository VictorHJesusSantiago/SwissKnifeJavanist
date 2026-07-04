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
./swissknife.cmd schema-diff examples/schema/source.sql examples/schema/target.sql
./swissknife.cmd anonymize examples/data/people.csv examples/data/policy.properties ./out.csv
./swissknife.cmd debt ./src
./swissknife.cmd vuln-server 8081 ./data/vulnerabilities.db
./swissknife.cmd itam-server 8082 ./data/assets.db
```

As APIs respondem JSON. Em ambos os servidores, `GET /health` fornece o estado
do processo. Consulte [docs/API.md](docs/API.md) para as rotas.

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
