# Arquitetura

O SwissKnife Javanist usa uma arquitetura modular sem dependências obrigatórias.
Cada ferramenta possui um pacote próprio e é orquestrada por
`dev.swissknife.Main`.

## Fluxo

```mermaid
flowchart LR
  CLI[CLI / IntelliJ] --> CORE[SwissKnife Core]
  CORE --> STATIC[Análises estáticas]
  CORE --> FILES[Geradores e arquivos]
  CORE --> JDBC[Migração JDBC]
  CORE --> HTTP[Contract testing]
  CORE --> GOV[Qualidade / Segurança / SBOM]
  CORE --> JVM[Diagnóstico JVM]
  CORE --> INTEGRATIONS[Adaptadores externos]
  HTTP --> EXT[Serviços externos]
  VULN[Vulnerability API] --> VDB[(JSON store)]
  ITAM[ITAM API] --> IDB[(JSON store)]
```

## Decisões

- Java 21 é o baseline; virtual threads atendem as APIs.
- O armazenamento JSON Lines usa troca atômica de arquivo e lock de leitura/escrita.
- Os servidores escutam somente no loopback e aceitam autenticação por token.
- A migração usa apenas JDBC, podendo receber qualquer driver no classpath.
- O plugin IntelliJ é um projeto isolado, pois depende do SDK da JetBrains.
- A documentação Java usa `JavacTask`/`DocTrees`, evitando parser por regex.
- Análises puras usam cache por fingerprint de comando, tamanho e data dos
  arquivos de entrada.
- Integrações externas são adapters opt-in e executam dry-run por padrão.

## Limites conscientes

O analisador SQL e o parser DDL cobrem SQL portável. Dialetos proprietários podem
exigir adaptação. Alterações destrutivas de schema são marcadas e nunca executadas
automaticamente.
