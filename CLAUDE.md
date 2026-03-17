# Mock Banking Backend - Microservices Learning Project

## Tổng quan

Project mock banking backend để học microservices architecture. Bao gồm 4 Spring Boot services giao tiếp qua REST (internal API) và Kafka (async events), với Redis caching, API Gateway, monitoring stack, và K8s deployment.

**Tech stack**: Spring Boot 3.4.1, JDK 21, PostgreSQL 16, Kafka (Confluent 7.7.0), Redis 7, Spring Cloud Gateway 2024.0.0, Prometheus + Grafana + Loki, Kubernetes.

---

## Kiến trúc hệ thống

```
Client (curl/Postman)
    │
    ▼
┌──────────────────────────────────────┐
│  API Gateway :8080                   │
│  - Auth: X-API-Key header            │
│  - Rate limiting (Redis-backed)      │
│  - Block /internal/** routes         │
└──────┬──────────────┬────────────────┘
       │              │
       ▼              ▼
┌──────────────┐  ┌──────────────────┐
│ Account Svc  │  │ Transaction Svc  │
│ :8081        │  │ :8082            │
│              │◄─┤ REST /internal/* │
│ - CRUD       │  │ - Transfer money │
│ - Redis cache│  │ - Kafka producer │
│ - @Scheduled │  └────────┬─────────┘
│   reconcile  │           │ Kafka: transaction-events
└──────┬───────┘           ▼
       │          ┌──────────────────┐
       │          │ Notification Svc │
       │          │ :8083            │
       ▼          │ - Kafka consumer │
   ┌────────┐     │ - Mock SMS/Email │
   │ Redis  │     └──────────────────┘
   │ :6379  │
   └────────┘

   ┌────────────┐   ┌────────────┐
   │ PostgreSQL  │   │   Kafka    │
   │ :5432       │   │ :9092(int) │
   │ account_db  │   │ :29092(host)│
   │ transaction │   └────────────┘
   │ _db         │
   └────────────┘
```

### Internal vs Public API

| Type | Path | Ai gọi được | Qua Gateway? |
|------|------|-------------|--------------|
| Public | `/api/accounts/**` | Client → Gateway → Account Svc | Có (cần X-API-Key) |
| Public | `/api/transactions/**` | Client → Gateway → Transaction Svc | Có (cần X-API-Key) |
| Internal | `/internal/accounts/{id}/debit` | Transaction Svc → Account Svc (direct) | Không (bị block) |
| Internal | `/internal/accounts/{id}/credit` | Transaction Svc → Account Svc (direct) | Không (bị block) |

---

## Cấu trúc project

```
mock-banking-backend/
├── build.gradle                          # Root: Spring Boot 3.4.1, JDK 21
├── settings.gradle                       # 4 modules: account, transaction, notification, api-gateway
├── docker-compose.yml                    # Infra + tất cả services (DC1)
├── docker-compose.multisite.yml          # DC2 overlay (thêm DC2 infra + app)
├── docker-compose.monitoring.yml         # Prometheus, Grafana, Loki, Promtail
├── init-db.sql                           # Tạo transaction_db, notification_db
├── init-db-multisite.sql                 # Tạo dc2_account_db, dc2_transaction_db
│
├── account-service/                      # Port 8081
│   ├── build.gradle                      # starter-web, data-jpa, data-redis, validation, flyway
│   ├── Dockerfile                        # Multi-stage: JDK build → JRE runtime
│   └── src/main/
│       ├── java/com/mockbank/account/
│       │   ├── AccountServiceApplication.java      # @EnableScheduling
│       │   ├── config/RedisConfig.java              # @EnableCaching, JSON serializer, site-prefixed cache
│       │   ├── filter/SiteContextFilter.java        # MDC siteId from X-Site-Id header
│       │   ├── controller/
│       │   │   ├── AccountController.java           # /api/accounts (public CRUD)
│       │   │   ├── InternalAccountController.java   # /internal/accounts/{id}/debit|credit
│       │   │   ├── GlobalExceptionHandler.java      # ProblemDetail responses
│       │   │   └── dto/
│       │   │       ├── AccountResponse.java         # record
│       │   │       ├── CreateAccountRequest.java    # record + validation
│       │   │       └── MoneyRequest.java            # record {amount}
│       │   ├── entity/
│       │   │   ├── Account.java                     # JPA entity, BigDecimal balance
│       │   │   └── AccountStatus.java               # ACTIVE, INACTIVE, CLOSED
│       │   ├── job/
│       │   │   └── BalanceReconciliationJob.java    # @Scheduled(fixedRate=60000), Redis lock per site
│       │   ├── repository/AccountRepository.java    # JpaRepository
│       │   └── service/
│       │       ├── AccountService.java              # @Cacheable, @CacheEvict
│       │       ├── AccountNotFoundException.java
│       │       └── InsufficientBalanceException.java
│       └── resources/
│           ├── application.yml                      # Local dev config (site-id: LOCAL)
│           ├── application-docker.yml               # Docker overrides (postgres:5432, redis host)
│           ├── application-dc1.yml                  # DC1: site-id: DC1
│           ├── application-dc2.yml                  # DC2: site-id: DC2, override DB/Redis URLs
│           └── db/migration/V1__create_accounts_table.sql
│
├── transaction-service/                  # Port 8082
│   ├── build.gradle                      # starter-web, data-jpa, kafka, validation, flyway
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/mockbank/transaction/
│       │   ├── TransactionServiceApplication.java
│       │   ├── filter/SiteContextFilter.java         # MDC siteId from X-Site-Id header
│       │   ├── client/
│       │   │   ├── AccountServiceClient.java        # RestClient + X-Site-Id propagation
│       │   │   └── dto/
│       │   │       ├── AccountResponse.java
│       │   │       └── MoneyRequest.java
│       │   ├── controller/
│       │   │   ├── TransactionController.java       # POST /api/transactions/transfer
│       │   │   ├── GlobalExceptionHandler.java
│       │   │   └── dto/
│       │   │       ├── TransferRequest.java         # {fromAccountId, toAccountId, amount}
│       │   │       └── TransactionResponse.java
│       │   ├── entity/
│       │   │   ├── Transaction.java                 # JPA entity
│       │   │   └── TransactionStatus.java           # PENDING, COMPLETED, FAILED
│       │   ├── event/
│       │   │   ├── TransactionCompletedEvent.java   # Kafka event record (+ siteId field)
│       │   │   └── TransactionEventPublisher.java   # KafkaTemplate publish (+ siteId injection)
│       │   ├── repository/TransactionRepository.java
│       │   └── service/
│       │       ├── TransferService.java             # Debit→Credit→Publish event
│       │       ├── TransactionNotFoundException.java
│       │       └── TransferFailedException.java
│       └── resources/
│           ├── application.yml                      # kafka: localhost:29092 (site-id: LOCAL)
│           ├── application-docker.yml               # kafka:9092, account-service:8081
│           ├── application-dc1.yml                  # DC1: site-id: DC1
│           ├── application-dc2.yml                  # DC2: site-id: DC2, override DB/Kafka/account-svc URLs
│           └── db/migration/V1__create_transactions_table.sql
│
├── notification-service/                 # Port 8083
│   ├── build.gradle                      # starter-web, kafka
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/mockbank/notification/
│       │   ├── NotificationServiceApplication.java
│       │   ├── filter/SiteContextFilter.java            # MDC siteId from X-Site-Id header
│       │   ├── event/TransactionCompletedEvent.java     # + siteId field
│       │   └── listener/TransactionEventListener.java   # @KafkaListener, mock SMS/email, log siteId
│       └── resources/
│           ├── application.yml                          # (site-id: LOCAL)
│           ├── application-docker.yml
│           ├── application-dc1.yml                      # DC1: site-id + consumer group dc1
│           └── application-dc2.yml                      # DC2: site-id + Kafka/consumer group dc2
│
├── api-gateway/                          # Port 8080
│   ├── build.gradle                      # spring-cloud-starter-gateway, redis-reactive
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/mockbank/gateway/
│       │   ├── ApiGatewayApplication.java
│       │   ├── config/RateLimiterConfig.java        # KeyResolver by API key or IP
│       │   └── filter/
│       │       ├── AuthFilter.java                  # Block /internal/**, check X-API-Key
│       │       └── SiteContextFilter.java           # Reactive: inject X-Site-Id into downstream
│       └── resources/
│           ├── application.yml                      # Routes, rate limiting (site-id: LOCAL)
│           ├── application-docker.yml               # Docker service URLs
│           ├── application-dc1.yml                  # DC1: site-id: DC1
│           └── application-dc2.yml                  # DC2: site-id: DC2, route to DC2 services
│
├── k8s/
│   ├── namespace.yml                     # namespace: mockbank
│   ├── infra/
│   │   ├── postgres.yml                  # Deployment + Service + Secret + ConfigMap
│   │   ├── redis.yml                     # Deployment + Service
│   │   └── kafka.yml                     # Zookeeper + Kafka Deployments + Services
│   └── services/
│       ├── configmap.yml                 # Shared env vars
│       ├── account-service.yml           # Deployment + ClusterIP Service
│       ├── transaction-service.yml
│       ├── notification-service.yml
│       └── api-gateway.yml              # Deployment + LoadBalancer Service
│
├── monitoring/
│   ├── prometheus/prometheus.yml         # Scrape tất cả /actuator/prometheus
│   ├── grafana/provisioning/datasources/datasources.yml
│   ├── loki/loki-config.yml
│   └── promtail/promtail-config.yml     # Docker log collection
│
└── docs/
    ├── architecture.md                   # Mermaid diagram
    ├── api.md                            # API endpoints & examples
    ├── setup.md                          # Full setup guide
    ├── multisite.md                      # Multisite concepts ↔ demo code mapping
    └── multisite-demo.sh                 # Demo script: 6 scenarios curl commands
```

---

## Hướng dẫn chạy

### Prerequisites
- JDK 21
- Docker Desktop (đang chạy)
- kubectl + minikube (cho K8s phase)

### System Sizing

Ước lượng tài nguyên cho mỗi cách chạy (đo trên macOS, Docker Desktop default settings):

```
╔══════════════════════════════════╦════════╦════════╦══════════╦═══════════════╗
║ Cách chạy                       ║  RAM   ║  CPU   ║ Disk     ║ Containers    ║
╠══════════════════════════════════╬════════╬════════╬══════════╬═══════════════╣
║ Local Dev (chỉ infra Docker)    ║  ~3 GB ║ 2 core ║ ~2 GB    ║ 4 (infra)     ║
║ Docker Compose (single-site)    ║  ~5 GB ║ 4 core ║ ~3 GB    ║ 8             ║
║ Docker + Monitoring             ║  ~6 GB ║ 4 core ║ ~3.5 GB  ║ 12            ║
║ Docker + Multisite (DC1+DC2)    ║  ~8 GB ║ 4 core ║ ~4 GB    ║ 15            ║
║ Multisite + Monitoring          ║ ~10 GB ║ 6 core ║ ~5 GB    ║ 19            ║
║ Kubernetes (minikube)           ║  ~6 GB ║ 4 core ║ ~4 GB    ║ 8 pods        ║
╚══════════════════════════════════╩════════╩════════╩══════════╩═══════════════╝
```

Chi tiết RAM từng component:

| Component | RAM ước lượng | Ghi chú |
|-----------|---------------|---------|
| **PostgreSQL 16** | ~100 MB | Shared cho tất cả DBs |
| **Zookeeper** | ~200 MB | Mỗi instance |
| **Kafka** | ~500 MB | Mỗi broker, Confluent image nặng |
| **Redis 7** | ~50 MB | Mỗi instance, data rất ít |
| **Spring Boot app** (mỗi service) | ~300-400 MB | JRE 21 + Spring context |
| **Prometheus** | ~200 MB | Scrape 4-8 targets |
| **Grafana** | ~150 MB | |
| **Loki + Promtail** | ~200 MB | Log ingestion |

Tổng cộng breakdown cho **Multisite (15 containers)**:
- Infra DC1: Postgres(100) + Zookeeper(200) + Kafka(500) + Redis(50) = **~850 MB**
- Infra DC2: Zookeeper(200) + Kafka(500) + Redis(50) = **~750 MB**
- App DC1: 4 services × 350 = **~1400 MB**
- App DC2: 4 services × 350 = **~1400 MB**
- Docker overhead: **~500 MB**
- **Tổng: ~5 GB thực tế**, khuyến nghị cấp **8 GB** cho Docker Desktop

### Docker Desktop Settings (khuyến nghị)

```
Single-site:  Resources → Memory: 6 GB,  CPUs: 4
Multisite:    Resources → Memory: 8 GB,  CPUs: 4
Full stack:   Resources → Memory: 10 GB, CPUs: 6
```

> **Tip**: Nếu máy chỉ có 8 GB RAM tổng, chạy single-site Docker Compose là ổn.
> Multisite cần máy **16 GB RAM** (OS chiếm ~4-6 GB, Docker cần 8 GB).
> Có thể giảm RAM bằng cách không chạy Monitoring stack.

### Cách 1: Local Development (chạy từng service)

```bash
cd mock-banking-backend

# 1. Start infrastructure
docker compose up -d postgres redis zookeeper kafka

# 2. Build
./gradlew clean build -x test

# 3. Chạy 4 services (mỗi cái 1 terminal)
./gradlew :account-service:bootRun        # Terminal 1
./gradlew :transaction-service:bootRun    # Terminal 2
./gradlew :notification-service:bootRun   # Terminal 3
./gradlew :api-gateway:bootRun            # Terminal 4
```

### Cách 2: Docker Compose (1 lệnh chạy tất cả)

```bash
cd mock-banking-backend

# Chỉ services
docker compose up --build

# Services + monitoring (Grafana, Prometheus, Loki)
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up --build
```

### Cách 3: Docker Compose Multisite (2 DCs trên 1 máy)

```bash
cd mock-banking-backend

# Multi-site: DC1 (ports 8xxx) + DC2 (ports 9xxx)
docker compose -f docker-compose.yml -f docker-compose.multisite.yml up --build

# Run demo script
bash docs/multisite-demo.sh
```

### Cách 4: Kubernetes (minikube)

```bash
# 1. Start minikube
minikube start --memory=6144 --cpus=4

# 2. Build Docker images trong minikube
eval $(minikube docker-env)
docker build -f account-service/Dockerfile -t mockbank/account-service:latest .
docker build -f transaction-service/Dockerfile -t mockbank/transaction-service:latest .
docker build -f notification-service/Dockerfile -t mockbank/notification-service:latest .
docker build -f api-gateway/Dockerfile -t mockbank/api-gateway:latest .

# 3. Deploy
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/infra/
kubectl apply -f k8s/services/

# 4. Kiểm tra pods
kubectl get pods -n mockbank -w

# 5. Access gateway
minikube tunnel
# Gateway accessible at localhost:8080
```

---

## Hướng dẫn test / minh hoạ

### Demo flow đầy đủ

```bash
# ===== Qua API Gateway (port 8080, cần API key) =====

# 1. Tạo 2 accounts
curl -s -X POST http://localhost:8080/api/accounts \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"Nguyen Van A","initialBalance":5000000,"currency":"VND"}'

curl -s -X POST http://localhost:8080/api/accounts \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"Tran Thi B","initialBalance":2000000,"currency":"VND"}'

# 2. Xem danh sách accounts
curl -s http://localhost:8080/api/accounts \
  -H "X-API-Key: mockbank-secret-key" | python3 -m json.tool

# 3. Chuyển tiền: Account 1 → Account 2 (500,000 VND)
curl -s -X POST http://localhost:8080/api/transactions/transfer \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":500000,"description":"Tra tien com trua"}'

# 4. Kiểm tra balances đã thay đổi
curl -s http://localhost:8080/api/accounts/1/balance \
  -H "X-API-Key: mockbank-secret-key"
# → 4500000

curl -s http://localhost:8080/api/accounts/2/balance \
  -H "X-API-Key: mockbank-secret-key"
# → 2500000

# 5. Xem transaction history
curl -s http://localhost:8080/api/transactions/account/1 \
  -H "X-API-Key: mockbank-secret-key" | python3 -m json.tool

# ===== Test security =====

# 6. Không có API key → 401 Unauthorized
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/accounts
# → 401

# 7. Truy cập internal API qua gateway → 403 Forbidden
curl -s -o /dev/null -w "%{http_code}" \
  -H "X-API-Key: mockbank-secret-key" \
  http://localhost:8080/internal/accounts/1
# → 403

# ===== Test Redis cache (xem logs account-service) =====

# 8. Query balance lần 1 → log "Cache MISS"
curl -s http://localhost:8080/api/accounts/1/balance -H "X-API-Key: mockbank-secret-key"

# 9. Query balance lần 2 → KHÔNG có "Cache MISS" (lấy từ Redis)
curl -s http://localhost:8080/api/accounts/1/balance -H "X-API-Key: mockbank-secret-key"

# ===== Test Kafka notification (xem logs notification-service) =====
# Sau mỗi transfer thành công, notification-service sẽ log:
#   === NOTIFICATION ===
#   [MOCK SMS] Sent to account 1: Your transfer of 500000 VND...
#   [MOCK EMAIL] Sent to account 2: You received 500000 VND...
```

### Truy cập trực tiếp service (không qua Gateway)

```bash
# Account Service direct (không cần API key)
curl -s http://localhost:8081/api/accounts

# Transaction Service direct
curl -s http://localhost:8082/api/transactions/1

# Internal API (chỉ service-to-service mới gọi)
curl -s -X POST http://localhost:8081/internal/accounts/1/credit \
  -H "Content-Type: application/json" \
  -d '{"amount":100000}'
```

### Monitoring (khi chạy với docker-compose.monitoring.yml)

| Service | URL | Credentials |
|---------|-----|-------------|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | - |
| Loki | http://localhost:3100 | - |

Trong Grafana:
- Data sources đã tự động provision (Prometheus + Loki)
- Vào Explore → chọn Prometheus → query `http_server_requests_seconds_count`
- Vào Explore → chọn Loki → query `{container="mockbank-account-service"}`

### Health checks

```bash
# Tất cả services expose /actuator/health
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8082/actuator/health
curl -s http://localhost:8083/actuator/health
curl -s http://localhost:8080/actuator/health

# Prometheus metrics
curl -s http://localhost:8081/actuator/prometheus | head -20
```

---

## Chi tiết từng phase đã triển khai

### Phase 1: Account Service Skeleton
- Gradle multi-module project (wrapper 8.12)
- `Account` entity với JPA + `AccountStatus` enum
- `AccountController` (GET/POST/DELETE) + `AccountRepository`
- Flyway migration `V1__create_accounts_table.sql`
- DTOs dùng Java records: `CreateAccountRequest`, `AccountResponse`
- `GlobalExceptionHandler` trả `ProblemDetail` (RFC 7807)

### Phase 2: Transaction Service + Internal API
- `InternalAccountController` expose `/internal/accounts/{id}/debit` và `/credit`
- `MoneyRequest` record cho debit/credit request body
- `transaction-service` module mới với database riêng (`transaction_db`)
- `AccountServiceClient` dùng Spring `RestClient` gọi internal API
- `TransferService`: tạo transaction PENDING → debit source → credit dest → COMPLETED
- Nếu lỗi → transaction status = FAILED + throw exception

### Phase 3: Kafka + Notification Service
- Zookeeper + Kafka (Confluent 7.7.0) trong docker-compose
- Kafka dual listener: `INTERNAL://kafka:9092` (Docker) + `HOST://localhost:29092` (local dev)
- `TransactionEventPublisher` publish `TransactionCompletedEvent` sau transfer thành công
- `notification-service` consume event qua `@KafkaListener(topics="transaction-events")`
- **Type mapping**: Producer/Consumer dùng `spring.json.type.mapping` để map giữa 2 class khác package (`com.mockbank.transaction.event.*` ↔ `com.mockbank.notification.event.*`)
- Mock SMS + Email notification logs

### Phase 4: Redis Caching + Scheduled Jobs
- Redis 7 trong docker-compose
- `RedisConfig`: `@EnableCaching`, `GenericJackson2JsonRedisSerializer`, TTL 5 phút
- `@Cacheable("accounts")` trên `getAccountById()`
- `@Cacheable("balances")` trên `getBalance()`
- `@CacheEvict({"accounts","balances"})` trên `debit()`, `credit()`, `closeAccount()`
- `BalanceReconciliationJob` chạy mỗi 60 giây, kiểm tra negative balances

### Phase 5: API Gateway
- Spring Cloud Gateway (reactive/WebFlux)
- Route `/api/accounts/**` → account-service:8081
- Route `/api/transactions/**` → transaction-service:8082
- `AuthFilter` (GlobalFilter): block `/internal/**` (403), check `X-API-Key` header (401)
- `RateLimiterConfig`: Redis-backed rate limiting (10 req/s, burst 20)

### Phase 6: Dockerize
- Multi-stage Dockerfile cho mỗi service (JDK build → JRE runtime)
- `application-docker.yml` profiles override: DB URLs dùng Docker service names
- docker-compose.yml orchestrate tất cả: chỉ gateway expose :8080
- Health checks + depends_on conditions

### Phase 7: Monitoring
- `micrometer-registry-prometheus` cho tất cả services
- Prometheus scrape `/actuator/prometheus` endpoints
- Grafana auto-provision datasources (Prometheus + Loki)
- Loki + Promtail: Docker log aggregation

### Phase 8: Kubernetes
- Namespace `mockbank`
- Infrastructure: Postgres (Deployment + Secret + ConfigMap), Redis, Zookeeper, Kafka
- Services: Deployment + ClusterIP Service cho mỗi app service
- API Gateway: LoadBalancer Service (expose ra ngoài qua `minikube tunnel`)
- Readiness/liveness probes: `/actuator/health`
- `imagePullPolicy: Never` (build local trong minikube)

### Phase 9: Documentation
- `docs/architecture.md`: Mermaid diagram
- `docs/api.md`: Tất cả API endpoints với examples
- `docs/setup.md`: Setup guide cho 3 cách chạy
- `CLAUDE.md` (file này): Chi tiết tất cả

### Phase 10: Multisite / Multi-DC Demo
- Demo 6 multisite concepts trên local machine bằng Docker Compose overlay
- 2 bộ services hoàn chỉnh: DC1 (ports 8xxx) + DC2 (ports 9xxx), shared PostgreSQL
- **Site identification**: `SiteContextFilter` + MDC logging (`[DC1]`/`[DC2]` trong logs)
- **Data isolation**: Separate databases per DC (`account_db` vs `dc2_account_db`)
- **Kafka isolation**: Separate Kafka clusters + consumer groups per site
- **Cache isolation**: Redis key prefix `DC1:` / `DC2:` via `prefixCacheNameWith()`
- **Job deduplication**: Redis distributed lock per site (`job:reconciliation:DC1`)
- **Header propagation**: `X-Site-Id` propagated qua Gateway → services → RestClient calls
- Spring profiles: `docker,dc1` và `docker,dc2` - backward-compatible
- `docker-compose.multisite.yml` overlay, `init-db-multisite.sql`
- Demo script: `docs/multisite-demo.sh`, docs: `docs/multisite.md`

---

## Single-site vs Multisite: So sánh chi tiết

### Tại sao cần multisite?

Single-site = 1 bộ services, 1 bộ infra, 1 database. Khi hệ thống cần chạy ở nhiều datacenter (VD: DC Hà Nội + DC HCM), code phải xử lý thêm nhiều vấn đề: data isolation, event isolation, cache isolation, job dedup, request routing.

Phase 10 demo tất cả các vấn đề này trên local machine bằng cách chạy 2 bộ services song song.

### Kiến trúc so sánh

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                           SINGLE-SITE (Phase 1-9)                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                             ║
║  Client ──→ Gateway :8080 ──→ account-svc :8081 ──→ Redis :6379            ║
║                            ──→ transaction-svc :8082 ──→ Kafka :9092       ║
║                                                    ──→ notification :8083   ║
║                                                                             ║
║  Infra: 1 Postgres │ 1 Kafka │ 1 Redis │ 1 Zookeeper                      ║
║  Profile: docker                                                            ║
║  DB: account_db, transaction_db                                             ║
║  Cache key: balances::1                                                     ║
║  Kafka group: notification-group                                            ║
║  Log: 10:30:00.123 INFO ... (không có site marker)                         ║
║                                                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                           MULTISITE (Phase 10)                              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                             ║
║  DC1 (ports 8xxx)                      DC2 (ports 9xxx)                    ║
║  ─────────────────                     ─────────────────                    ║
║  Gateway :8080                         Gateway-DC2 :9080                   ║
║    ├→ account-svc :8081                  ├→ account-svc-dc2 :9081          ║
║    ├→ transaction-svc :8082              ├→ transaction-svc-dc2 :9082      ║
║    └→ notification-svc :8083             └→ notification-svc-dc2 :9083     ║
║                                                                             ║
║  Infra DC1        │  Infra DC2        │  Shared                            ║
║  kafka :29092     │  kafka-dc2 :29093 │  postgres :5432                    ║
║  redis :6379      │  redis-dc2 :6380  │                                    ║
║  zookeeper :2181  │  zookeeper-dc2    │                                    ║
║                   │    :2182          │                                     ║
║                                                                             ║
║  Profile: docker,dc1                   Profile: docker,dc2                 ║
║  DB: account_db                        DB: dc2_account_db                  ║
║  Cache: DC1:balances::1                Cache: DC2:balances::1              ║
║  Kafka group: notification-group-dc1   Kafka group: notification-group-dc2 ║
║  Log: [DC1] INFO ...                   Log: [DC2] INFO ...                 ║
║  Job lock: job:reconciliation:DC1      Job lock: job:reconciliation:DC2    ║
║                                                                             ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Code thay đổi gì khi chuyển từ single-site sang multisite?

**Nguyên tắc: Zero thay đổi business logic**. Chỉ thêm config + cross-cutting concerns.

| Component | Single-site (trước) | Multisite (sau) | Tại sao cần? |
|-----------|--------------------|--------------------|---------------|
| **Spring profile** | `docker` | `docker,dc1` / `docker,dc2` | Phân biệt config theo site |
| **Log format** | `10:30:00 INFO ...` | `10:30:00 [DC1] INFO ...` | Biết log thuộc DC nào khi debug |
| **Filter** | Không có | `SiteContextFilter` set MDC | Inject site context vào mọi request |
| **Redis cache key** | `balances::1` | `DC1:balances::1` | Tránh DC1 đọc cache DC2 (stale data) |
| **Kafka event** | `{referenceId, amount, ...}` | `{referenceId, amount, ..., siteId}` | Consumer biết event từ DC nào |
| **Kafka consumer group** | `notification-group` | `notification-group-dc1` | Mỗi DC consume riêng, không cross-site |
| **Scheduled job** | Chạy trực tiếp | Redis lock `job:reconciliation:DC1` | Tránh chạy trùng khi scale replicas |
| **RestClient** | Không propagate header | Propagate `X-Site-Id` | Service downstream biết request từ DC nào |
| **Gateway** | Route tới 1 bộ services | DC1 route DC1 services, DC2 route DC2 | Không cross-site routing |
| **Database** | `account_db` | DC1: `account_db`, DC2: `dc2_account_db` | Data hoàn toàn tách biệt |

### Request flow so sánh

**Single-site flow** (1 đường duy nhất):
```
curl :8080/api/accounts
  → Gateway (auth check)
    → account-service
      → PostgreSQL (account_db)
      → Redis (balances::1)
```

**Multisite flow** (2 đường hoàn toàn tách biệt):
```
curl :8080/api/accounts                    curl :9080/api/accounts
  → Gateway-DC1                              → Gateway-DC2
    → inject X-Site-Id: DC1                    → inject X-Site-Id: DC2
    → account-service-DC1                      → account-service-DC2
      → SiteContextFilter: MDC[siteId=DC1]       → SiteContextFilter: MDC[siteId=DC2]
      → PostgreSQL (account_db)                   → PostgreSQL (dc2_account_db)
      → Redis DC1 (DC1:balances::1)               → Redis DC2 (DC2:balances::1)
      → Log: [DC1] INFO getBalance...             → Log: [DC2] INFO getBalance...
```

**Transfer flow multisite** (mọi component đều isolated):
```
curl :8080/api/transactions/transfer (DC1)
  → Gateway-DC1 (inject X-Site-Id: DC1)
    → transaction-service-DC1
      → SiteContextFilter: MDC[siteId=DC1]
      → AccountServiceClient (propagate X-Site-Id: DC1 header)
        → account-service-DC1 /internal/debit  (hit account_db, NOT dc2_account_db)
        → account-service-DC1 /internal/credit
      → Kafka DC1: publish {siteId: DC1, ...} → topic "transaction-events"
    → notification-service-DC1 (group: notification-group-dc1)
      → consume event → log [DC1] === NOTIFICATION [site=DC1] ===

  ❌ notification-service-DC2 KHÔNG nhận event này
     (khác Kafka cluster + khác consumer group)
```

### Demo: Thấy sự khác biệt single-site vs multisite

#### Demo 1: Chạy Single-site

```bash
# Chạy 8 containers (4 infra + 4 app)
docker compose up --build

# Tạo account + transfer
curl -s -X POST http://localhost:8080/api/accounts \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"User A","initialBalance":5000000,"currency":"VND"}'

# Xem logs → [DC1] vì profile docker,dc1
docker logs mockbank-account-service 2>&1 | tail -5
# Output: 10:30:00.123 [DC1] INFO ... Created account ...

# Redis key → có prefix DC1
docker exec mockbank-redis redis-cli KEYS "*"
# Output: DC1:balances::1

# Chỉ có 1 bộ services, 1 entry point :8080
```

#### Demo 2: Chạy Multisite

```bash
# Chạy 15 containers (7 infra + 8 app)
docker compose -f docker-compose.yml -f docker-compose.multisite.yml up --build

# === Tạo account trên DC1 ===
curl -s -X POST http://localhost:8080/api/accounts \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"DC1 User","initialBalance":5000000,"currency":"VND"}'

# === Tạo account trên DC2 ===
curl -s -X POST http://localhost:9080/api/accounts \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"DC2 User","initialBalance":3000000,"currency":"VND"}'

# === Verify: Data isolation ===
curl -s http://localhost:8080/api/accounts -H "X-API-Key: mockbank-secret-key"
# → Chỉ thấy "DC1 User"

curl -s http://localhost:9080/api/accounts -H "X-API-Key: mockbank-secret-key"
# → Chỉ thấy "DC2 User"

# === Verify: Log isolation ===
docker logs mockbank-account-service 2>&1 | grep "\[DC1\]"
# → [DC1] INFO ... Created account ...

docker logs mockbank-account-service-dc2 2>&1 | grep "\[DC2\]"
# → [DC2] INFO ... Created account ...

# === Verify: Cache isolation ===
docker exec mockbank-redis redis-cli KEYS "*"
# → DC1:balances::1, DC1:accounts::1

docker exec mockbank-redis-dc2 redis-cli KEYS "*"
# → DC2:balances::1, DC2:accounts::1

# === Verify: Kafka isolation ===
# Transfer trên DC1
curl -s -X POST http://localhost:8080/api/transactions/transfer \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":500000,"description":"DC1 only"}'

docker logs mockbank-notification-service 2>&1 | grep "NOTIFICATION"
# → [DC1] === NOTIFICATION [site=DC1] ===

docker logs mockbank-notification-service-dc2 2>&1 | grep "NOTIFICATION"
# → (trống - DC2 KHÔNG nhận event DC1)

# === Verify: Job dedup ===
# Đợi 60s rồi check
docker exec mockbank-redis redis-cli KEYS "job:*"
# → job:reconciliation:DC1

docker exec mockbank-redis-dc2 redis-cli KEYS "job:*"
# → job:reconciliation:DC2
```

### Spring Profile system giải thích

```
application.yml          ← Base config (local dev, site-id: LOCAL)
application-docker.yml   ← Docker overrides (service names thay localhost)
application-dc1.yml      ← DC1: site-id: DC1 (chỉ thế thôi)
application-dc2.yml      ← DC2: site-id: DC2, override DB/Redis/Kafka URLs

Profile precedence (later overrides earlier):
  docker,dc1 → application.yml + application-docker.yml + application-dc1.yml
  docker,dc2 → application.yml + application-docker.yml + application-dc2.yml

Backward-compatible: single-site chạy docker,dc1 = hoạt động y hệt docker cũ
  (dc1 chỉ thêm site-id: DC1, không đổi bất kỳ URL nào)
```

---

## Build commands

```bash
# Build tất cả
./gradlew clean build -x test

# Build 1 module
./gradlew :account-service:build -x test

# Run 1 service
./gradlew :account-service:bootRun

# Docker compose
docker compose up --build                    # Tất cả services
docker compose up -d postgres redis kafka zookeeper  # Chỉ infra
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up --build  # + monitoring
```

---

## Key design decisions

1. **Database-per-service**: Mỗi service có DB riêng (account_db, transaction_db) → loose coupling
2. **Internal API pattern**: `/internal/*` endpoints chỉ cho service-to-service, gateway block access
3. **Saga pattern (simplified)**: Transfer = debit → credit, nếu credit fail thì transaction = FAILED (chưa implement compensating transaction/rollback debit)
4. **Event-driven notification**: Kafka decouple transaction-service khỏi notification-service
5. **Cache-aside pattern**: Redis cache balance queries, evict khi mutation
6. **Spring profiles**: `default` cho local dev, `docker` cho Docker/K8s, `docker,dc1`/`docker,dc2` cho multisite
7. **Multisite isolation**: Mỗi DC có Kafka + Redis riêng, DB riêng, cache prefix riêng, consumer group riêng → zero cross-site contamination
8. **Docker Compose overlay**: `docker-compose.multisite.yml` chỉ thêm DC2 stack, không sửa base compose → single-site vẫn chạy bình thường

---

## Self-test Results (2026-03-17)

Tất cả đã test thành công trên local dev (Cách 1: chạy từng service):

| Test | Kết quả |
|------|---------|
| `./gradlew clean build -x test` | BUILD SUCCESSFUL (26 tasks) |
| Account Service health `/actuator/health` | UP |
| Transaction Service health | UP |
| Notification Service health | UP |
| API Gateway health | UP |
| Create account via Gateway | 201 Created |
| Transfer via Gateway (A→B 500K) | COMPLETED, balances correct |
| Auth: no API key → Gateway | 401 Unauthorized |
| Redis cache: 2nd balance query | No "Cache MISS" log (from cache) |
| Kafka: consumer offset after transfer | LAG=0 (consumed successfully) |
| Prometheus metrics `/actuator/prometheus` | Exposing http_server_requests metrics |

### Bugs đã fix trong quá trình test
1. **Kafka deserialization error**: Producer gửi `com.mockbank.transaction.event.TransactionCompletedEvent` nhưng consumer class là `com.mockbank.notification.event.TransactionCompletedEvent` → Fix: thêm `spring.json.type.mapping` trên cả 2 bên
2. **Kafka dual listener**: Ban đầu `ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092` → local dev không kết nối được → Fix: thêm `HOST://localhost:29092` listener
3. **NPE in RateLimiterConfig**: `getRemoteAddress()` có thể null → Fix: thêm null check
4. **Cache self-invocation**: `getBalance()` gọi `getAccountById()` (cùng class) → Spring proxy không intercept → Fix: query DB trực tiếp trong `getBalance()`
