# Guia operacional

## Backup

Pare o servidor e copie o arquivo `.db` configurado. Cada linha é um documento
JSON independente. Escritas usam arquivo temporário e substituição atômica.

## Observabilidade

Use `/health` para liveness. Erros inesperados são escritos em stderr. Defina
`SWISSKNIFE_DEBUG=1` para stack traces na CLI.

Execute `swissknife doctor` antes de uma operação para validar Java, diretório,
configuração e drivers JDBC. Os comandos podem produzir SARIF para IDE/CI,
JUnit XML para pipelines e HTML autocontido para arquivamento.

Quality gates retornam código `3`; findings abaixo do limite retornam `2`.
Automação que apenas coleta relatórios pode usar `--no-fail`. A telemetria é
desativada por padrão e, quando habilitada, grava somente comando, instante e
versão Java em `.swissknife/telemetry.jsonl`.

## Cache

O cache incremental não armazena credenciais. Para inspecionar e limpar:

```powershell
./swissknife.cmd cache-status
./swissknife.cmd cache-clear
```

## Integrações

`integrate` nunca envia no modo padrão. Revise a prévia e só então utilize
`--send`. Tokens são obtidos de variáveis indicadas por `tokenEnv`; não os
grave nos arquivos `.properties`.

## Backup e integridade

```powershell
./swissknife.cmd store-admin ./data/assets.db verify
./swissknife.cmd store-admin ./data/assets.db backup ./backup/assets.db
./swissknife.cmd store-admin ./data/assets.db restore ./backup/assets.db --confirm
./swissknife.cmd store-admin ./data/assets.db compact
./swissknife.cmd store-admin ./data/assets.db audit --limit 100
```

Cada mutação gera um evento com hash do registro, hash anterior e hash do
evento. A verificação detecta truncamento ou alteração da cadeia.

## Assistência por IA

O comando é opcional e não envia dados por padrão. Para um provedor externo:

```powershell
./swissknife.cmd ai-assist ./ai.properties summarize ./report.json
# revise requestPreview
./swissknife.cmd ai-assist ./ai.properties summarize ./report.json --send --consent
```

Modelo, tarefa, estimativa de tokens e arquivo de origem são auditados
localmente; o conteúdo não é gravado no audit log.

## Portal

```powershell
./swissknife.cmd portal-server 8080 `
  --vulnerability-url http://127.0.0.1:8081 `
  --itam-url http://127.0.0.1:8082
```

O portal não usa CDN ou telemetria e aplica Content Security Policy.

Jobs são executados em fila com limite definido por
`SWISSKNIFE_JOB_CONCURRENCY` (padrão 4). O histórico persistente usa
`SWISSKNIFE_JOBS_DB` e mantém até `SWISSKNIFE_JOB_HISTORY` registros
(padrão 500). Reinicializações marcam jobs interrompidos como falhos.

## Segurança

- Nunca passe senhas JDBC diretamente na linha de comando; passe o nome da
  variável de ambiente.
- Execute os servidores atrás de um proxy TLS caso necessite acesso remoto.
- Use token longo e aleatório em `SWISSKNIFE_API_TOKEN`.
- Revise todo SQL destrutivo retornado por `schema-diff`.

## Migração JDBC

Adicione os drivers dos bancos ao classpath e execute a classe principal. A
migração preserva os nomes de coluna, usa batches e faz rollback no destino em
caso de falha.
