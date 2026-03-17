# Phase 10: Multisite / Multi-DC Demo

## Tổng quan

Demo 6 multisite concepts trên local machine bằng Docker Compose. Chạy 2 bộ services hoàn chỉnh (DC1 ports 8xxx, DC2 ports 9xxx) trên cùng 1 máy, sử dụng shared PostgreSQL với databases tách biệt.

## Kiến trúc

```
             DC1 (ports 8xxx)                    DC2 (ports 9xxx)
             ─────────────────                   ─────────────────
Client ───→ api-gateway :8080              ───→ api-gateway-dc2 :9080
               │                                    │
               ├→ account-service :8081              ├→ account-service-dc2 :9081
               ├→ transaction-service :8082          ├→ transaction-service-dc2 :9082
               └→ notification-service :8083         └→ notification-service-dc2 :9083

Infra DC1:                                 Infra DC2:
  kafka     :29092 (host)                    kafka-dc2    :29093 (host)
  redis     :6379                            redis-dc2    :6380
  zookeeper :2181                            zookeeper-dc2 :2182

Shared: postgres :5432
  ├─ account_db      (DC1)    ├─ dc2_account_db      (DC2)
  ├─ transaction_db  (DC1)    └─ dc2_transaction_db   (DC2)
```

## Cách chạy

```bash
# Single-site (vẫn hoạt động như cũ)
docker compose up --build

# Multi-site demo
docker compose -f docker-compose.yml -f docker-compose.multisite.yml up --build

# Run demo script
bash docs/multisite-demo.sh
```

## 6 Concepts demo

| # | Concept | Tài liệu | Implementation | Verify |
|---|---------|-----------|----------------|--------|
| 1 | **Site identification** | Mục 9 | `SiteContextFilter` + MDC logging | Logs hiện `[DC1]`/`[DC2]` |
| 2 | **Data isolation** | Mục 3 | Separate databases per DC | `GET /api/accounts` trả data riêng |
| 3 | **Kafka per site** | Mục 6 | Separate Kafka clusters + consumer groups | DC2 không nhận events DC1 |
| 4 | **Cache isolation** | Mục 7 | `prefixCacheNameWith(siteId + ":")` | Redis keys: `DC1:balances::1` |
| 5 | **Job deduplication** | Mục 5 | Redis distributed lock per site | Lock key: `job:reconciliation:DC1` |
| 6 | **Cross-site routing** | Mục 8 | Gateway routes to same-site services only | DC2 gateway → DC2 services |

## Chi tiết từng concept

### 1. Site Identification (SiteContextFilter + MDC)

Mỗi service có `SiteContextFilter` đọc header `X-Site-Id`, fallback về `app.site-id` config, set vào MDC:
- **Servlet services** (account, transaction, notification): `OncePerRequestFilter`
- **Gateway** (reactive): `GlobalFilter` - inject `X-Site-Id` vào downstream requests

Log pattern: `%d{HH:mm:ss.SSS} [%X{siteId:-NO_SITE}] %-5level ...`

### 2. Data Isolation (Separate Databases)

DC1 và DC2 sử dụng databases khác nhau trên cùng PostgreSQL instance:
- DC1: `account_db`, `transaction_db`
- DC2: `dc2_account_db`, `dc2_transaction_db`

Spring profiles `dc1`/`dc2` override `spring.datasource.url`.

### 3. Kafka Isolation (Separate Clusters)

Mỗi DC có Kafka cluster riêng:
- DC1: `kafka:9092` (internal), `localhost:29092` (host)
- DC2: `kafka-dc2:9092` (internal), `localhost:29093` (host)

Consumer groups cũng tách: `notification-group-dc1` / `notification-group-dc2`.

### 4. Cache Isolation (Site-prefixed Redis Keys)

`RedisConfig` thêm `prefixCacheNameWith(siteId + ":")`:
- DC1 balance cache: `DC1:balances::1`
- DC2 balance cache: `DC2:balances::1`

Mỗi DC cũng dùng Redis instance riêng (`redis` vs `redis-dc2`).

### 5. Job Deduplication (Redis Distributed Lock)

`BalanceReconciliationJob` acquire Redis lock trước khi chạy:
```
lock key: job:reconciliation:DC1  (TTL 55s, job chạy mỗi 60s)
lock key: job:reconciliation:DC2
```

Đảm bảo mỗi DC chỉ chạy 1 instance job, dù có scale nhiều replicas.

### 6. Header Propagation (Cross-service Routing)

`AccountServiceClient` propagate `X-Site-Id` từ MDC sang outgoing RestClient requests. Gateway inject `X-Site-Id` header vào tất cả downstream requests.

## Files thay đổi

| Action | Files |
|--------|-------|
| NEW config (8) | `application-dc1.yml` + `application-dc2.yml` x 4 services |
| NEW Java (4) | `SiteContextFilter.java` x 4 services |
| NEW Docker (2) | `docker-compose.multisite.yml`, `init-db-multisite.sql` |
| NEW Docs (2) | `docs/multisite.md`, `docs/multisite-demo.sh` |
| MODIFY Java (4) | RedisConfig, BalanceReconciliationJob, TransactionCompletedEvent x2, TransactionEventPublisher, AccountServiceClient |
| MODIFY Config (5) | 4x `application.yml` + `docker-compose.yml` |
