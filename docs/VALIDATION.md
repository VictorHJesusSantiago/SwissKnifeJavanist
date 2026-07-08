# Matriz de validação funcional

Esta matriz vincula cada produto solicitado a uma implementação executável e à
evidência automatizada disponível no repositório.

| Produto | Implementação principal | Evidência |
|---|---|---|
| Plugin IntelliJ | `intellij-plugin/` com ações de documentação, arquitetura, qualidade, segurança e Spring | `buildPlugin` e Plugin Verifier contra IC 2025.1 |
| Gerador de documentação | `DocumentationGenerator`, AST do compilador, site HTML e diff de API | `DocumentationTest`, `AdvancedFeaturesTest` |
| Dependências de microsserviços | Descoberta de projetos/configurações, ciclos, impacto e exportação | `DependencyTest`, `AdvancedFeaturesTest` |
| Queries lentas | Lote, logs, heurísticas, índices, reescritas e EXPLAIN JDBC somente leitura | `AnalyzerTest`, `ExplainPlanTest`, `FunctionalIntegrationTest` |
| Comparação de schema | Tabelas, colunas, constraints, índices, sequences, views, migration e rollback | `SchemaTest`, `AdvancedFeaturesTest` |
| Anonimização | CSV/JSON, políticas, pseudonimização e detecção de PII | `AnonymizerTest`, `AdvancedFeaturesTest` |
| Contract testing | Suites HTTP, autenticação, headers, JSONPath, extração, retry e SLA | `FunctionalIntegrationTest` |
| Vulnerabilidades | API leve e serviço Spring Boot/JPA/Flyway com workflow, filtros, dashboard e OpenAPI | `ServerTest`, `VulnerabilityApiTest` |
| Gatling | Simulation Java e projeto Maven Gatling completo | `GatlingTest`, `AdvancedFeaturesTest` |
| Débito técnico | Marcadores configuráveis, owners, tickets, vencimento e quality gates | `AdvancedFeaturesTest` |
| Migração heterogênea | JDBC, planejamento, mapeamento, transformação, batches, rollback, checksum e rejeitados | `FunctionalIntegrationTest` |
| ITAM | API leve e serviço Spring Boot/JPA/Flyway com lifecycle, filtros, inventário e OpenAPI | `ServerTest`, `AssetApiTest` |

## Validação local

Suíte sem dependências externas:

```powershell
.\test.cmd
```

Backends Spring Boot:

```powershell
cd spring-backends
mvn test
```

Os testes Spring usam H2 em memória e exercitam persistência, HTTP, validação,
filtros e transições válidas e inválidas. Integrações com bancos externos
dependem do driver JDBC e das credenciais do ambiente de destino; o software não
embute credenciais nem simula a existência desses sistemas.
