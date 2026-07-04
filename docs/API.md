# APIs HTTP

## Autenticação

Quando `SWISSKNIFE_API_TOKEN` estiver definida, envie:

```http
Authorization: Bearer seu-token
```

## Vulnerabilidades — porta 8081

- `GET /health`
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
