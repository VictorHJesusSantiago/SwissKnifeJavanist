# Análise de EXPLAIN

O comando `slow-query-explain` abre uma conexão JDBC em modo somente leitura e
aceita exclusivamente consultas iniciadas por `SELECT` ou `WITH`. Senhas são
lidas de variáveis de ambiente.

```powershell
$env:DB_PASSWORD = "segredo"
.\swissknife.cmd slow-query-explain postgresql `
  jdbc:postgresql://localhost:5432/app app DB_PASSWORD false `
  "SELECT * FROM orders WHERE customer_id = 10"
```

Dialetos: `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `H2` e `GENERIC`.
Para SQL Server, exporte o plano textual/XML e use `slow-query-plan`, pois
`SHOWPLAN_XML` altera o estado da sessão.

Use `analyze=false` em produção. `EXPLAIN ANALYZE` realmente executa a consulta
e só deve ser habilitado conscientemente em ambiente controlado.

O analisador sinaliza varredura completa, ordenação em disco, nested/hash joins,
tabelas temporárias, ramos não executados e divergência de cardinalidade.
