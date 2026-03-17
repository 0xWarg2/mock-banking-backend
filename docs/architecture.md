# Mock Banking Backend - Architecture

## System Overview

```mermaid
graph TB
    Client["Client<br/>curl/Postman"] --> Gateway["API Gateway<br/>:8080"]

    Gateway -->|accounts API| AccountSvc["Account Service<br/>:8081"]
    Gateway -->|transactions API| TransactionSvc["Transaction Service<br/>:8082"]
    Gateway -.->|internal BLOCKED| Blocked["BLOCKED"]

    TransactionSvc -->|debit/credit| AccountSvc
    TransactionSvc -->|publish| Kafka["Apache Kafka"]
    Kafka -->|consume| NotificationSvc["Notification Service<br/>:8083"]

    AccountSvc --> Redis[("Redis<br/>Cache")]
    AccountSvc --> AccountDB[("account_db")]
    TransactionSvc --> TransactionDB[("transaction_db")]

    AccountSvc -->|reconciliation| ReconcileJob["Balance<br/>Reconciliation"]

    subgraph Monitoring["Monitoring Stack"]
        Prometheus["Prometheus<br/>:9090"]
        Grafana["Grafana<br/>:3000"]
        Loki["Loki<br/>:3100"]
        Promtail["Promtail"]

        Prometheus -->|scrape| AccountSvc
        Prometheus -->|scrape| TransactionSvc
        Prometheus -->|scrape| NotificationSvc
        Prometheus -->|scrape| Gateway
        Grafana --> Prometheus
        Grafana --> Loki
        Promtail --> Loki
    end
```

## Service Communication

| From | To | Protocol | Path | Purpose |
|------|-----|----------|------|---------|
| Client | API Gateway | HTTP | `/api/accounts/**` | Public account operations |
| Client | API Gateway | HTTP | `/api/transactions/**` | Public transaction operations |
| API Gateway | Account Service | HTTP | Proxied `/api/accounts/**` | Route to backend |
| API Gateway | Transaction Service | HTTP | Proxied `/api/transactions/**` | Route to backend |
| Transaction Service | Account Service | REST | `/internal/accounts/{id}/debit` | Debit account (internal) |
| Transaction Service | Account Service | REST | `/internal/accounts/{id}/credit` | Credit account (internal) |
| Transaction Service | Kafka | Event | `transaction-events` topic | Publish transaction completed |
| Kafka | Notification Service | Event | `transaction-events` topic | Consume and notify |

## Key Patterns

- **API Gateway**: Single entry point, auth filter (X-API-Key), rate limiting (Redis-backed), blocks `/internal/**`
- **Internal API**: Service-to-service only, not exposed through gateway
- **Event-Driven**: Kafka for async notification after transfer
- **Caching**: Redis `@Cacheable` on balance queries, `@CacheEvict` on mutations
- **Scheduled Jobs**: Balance reconciliation every 60s
- **Database-per-Service**: `account_db`, `transaction_db` (separate schemas)
- **Observability**: Prometheus metrics, Grafana dashboards, Loki log aggregation
