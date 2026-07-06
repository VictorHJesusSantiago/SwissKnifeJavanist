# APIs HTTP

## Autenticação

Quando `SWISSKNIFE_API_TOKEN` estiver definida, envie:

```http
Authorization: Bearer seu-token
```

## Vulnerabilidades — porta 8081

- `GET /health`
- `GET /ready`
- `GET /version`
- `GET /api/v1/vulnerabilities`
- `GET /api/v1/vulnerabilities/{id}`
- `POST /api/v1/vulnerabilities`
- `PUT /api/v1/vulnerabilities/{id}`
- `DELETE /api/v1/vulnerabilities/{id}`
- `GET /api/v1/dashboard`

Payload mínimo:

```json
{"title":"CVE-2026-0001","component":"payments-api","severity":"HIGH"}
```

Severidades aceitas: `LOW`, `MEDIUM`, `HIGH` e `CRITICAL`.

## ITAM — porta 8082

- `GET /health`
- `GET /ready`
- `GET /version`
- `GET /api/v1/assets`
- `GET /api/v1/assets/{id}`
- `POST /api/v1/assets`
- `PUT /api/v1/assets/{id}`
- `DELETE /api/v1/assets/{id}`
- `GET /api/v1/inventory`

Payload mínimo:

```json
{"tag":"NB-0042","name":"Notebook Financeiro","type":"COMPUTER","purchaseValue":7500}
```

## Consulta de coleções

As duas coleções aceitam:

- `q` para busca textual;
- filtros por nome de campo, como `status=OPEN`;
- `sort` e `direction=asc|desc`;
- `offset` e `limit` (máximo 1.000).

Respostas incluem `X-Request-ID`, `Cache-Control: no-store`,
`X-Content-Type-Options` e `X-Frame-Options`. Configure
`SWISSKNIFE_MAX_PAYLOAD_BYTES` para limitar corpos e
`SWISSKNIFE_CORS_ORIGIN` para habilitar uma origem web.

## Portal — porta 8080

- `GET /`
- `GET /api/status`
- `GET /api/jobs?limit=100&state=RUNNING`
- `GET /api/jobs/{id}`
- `POST /api/jobs`
- `DELETE /api/jobs/{id}`
- `GET /api/metrics`
- `GET /metrics` (Prometheus)
- `GET /health`

O portal consulta os healthchecks das APIs sem expor credenciais ao navegador.
Os endpoints `/api/jobs` e `/api/metrics` usam o mesmo Bearer token configurado
em `SWISSKNIFE_API_TOKEN`.

Criação de job:

```json
{"command":["quality","src/main/java"]}
```

Somente comandos de análise presentes na allowlist interna podem ser
executados. Migração, restore, servidores e envio a integrações/IA são
deliberadamente proibidos pela interface web.
