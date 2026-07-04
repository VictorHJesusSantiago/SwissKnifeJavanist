# Guia operacional

## Backup

Pare o servidor e copie o arquivo `.db` configurado. Cada linha é um documento
JSON independente. Escritas usam arquivo temporário e substituição atômica.

## Observabilidade

Use `/health` para liveness. Erros inesperados são escritos em stderr. Defina
`SWISSKNIFE_DEBUG=1` para stack traces na CLI.

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
