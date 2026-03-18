# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A multi-service microservices banking backend built with Spring Boot 3.4.1, Java 21, and Gradle 8.12. Demonstrates event-driven architecture, distributed caching, API gateway patterns, and observability.

## Build Commands

```bash
# Build all services (skip tests)
./gradlew build -x test

# Build a specific service
./gradlew :account-service:build

# Run all tests
./gradlew test

# Run tests for a specific service
./gradlew :account-service:test

# Run a single test class
./gradlew :account-service:test --tests com.mockbank.account.service.AccountServiceTest

# Run with test output visible
./gradlew test --info

# Start all services (Docker Compose)
docker compose up --build

# Start only infrastructure (for local dev)
docker compose up -d postgres kafka zookeeper redis

# Run a service locally (after infrastructure is up)
./gradlew :account-service:bootRun      # port 8081
./gradlew :transaction-service:bootRun  # port 8082
./gradlew :notification-service:bootRun # port 8083
./gradlew :api-gateway:bootRun          # port 8080

# With monitoring stack (Prometheus, Grafana, Loki)
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up --build
```

## Architecture

### Services and Ports
| Service | Port | Responsibility |
|---------|------|----------------|
| `api-gateway` | 8080 | Single entry point; auth (API key), rate limiting (Redis), route blocking |
| `account-service` | 8081 | Account CRUD, balance management, Redis caching, reconciliation jobs |
| `transaction-service` | 8082 | Transfer processing, calls account-service internally, publishes Kafka events |
| `notification-service` | 8083 | Kafka consumer for `transaction-events` topic |

### Request Flow
```
Client -> API Gateway (auth + rate limit) -> account-service / transaction-service
                                             transaction-service -> Kafka -> notification-service
```

- **Authentication**: `X-API-Key: mockbank-secret-key` header required on all gateway requests
- **Internal paths** (`/internal/**`) are blocked at the gateway; services call each other directly
- **Service-to-service**: Uses Spring 6 `RestClient` for synchronous calls (transaction-service -> account-service)
- **Events**: Kafka topic `transaction-events` carries `TransactionCompletedEvent` POJOs

### Key Patterns
- **Cache-Aside** with Redis: `@Cacheable` / `@CacheEvict` on account balances (5-min TTL)
- **Database per service**: `account_db` and `transaction_db` on PostgreSQL 16; schemas managed by **Flyway migrations** in `src/main/resources/db/migration/V{n}__*.sql`; JPA `ddl-auto: validate` (never auto-create)
- **Distributed locks** via Redis in `BalanceReconciliationJob` (runs every 60s) to prevent duplicate execution across replicas
- **MDC logging** with `siteId` context injected by `SiteContextFilter` for multi-datacenter tracing
- **RFC 7807 errors**: `GlobalExceptionHandler` maps custom exceptions to `ProblemDetail` responses

### Multi-Site Support
Docker Compose overlays (`docker-compose.multisite.yml`) spin up a second datacenter (dc2) with its own databases, Redis cache (key-prefixed `DC2:`), and Kafka consumer group. The `SiteContextFilter` reads `X-Site-Id` header and writes it to MDC.

### Profiles
| Profile | When used |
|---------|-----------|
| default | Local `bootRun` |
| `docker` | Docker Compose (auto-set) |
| `dc1` / `dc2` | Multi-datacenter deployments |

## Code Structure (per service)

```
service-name/src/main/java/com/mockbank/{service}/
├── {Service}Application.java
├── controller/
│   ├── {Entity}Controller.java         # Public REST API
│   ├── Internal{Entity}Controller.java # Service-to-service only (not exposed via gateway)
│   ├── GlobalExceptionHandler.java     # RFC 7807 ProblemDetail responses
│   └── dto/                            # Request/response records
├── service/                            # Business logic + custom exceptions
├── entity/                             # JPA entities + status enums
├── repository/                         # Spring Data JPA interfaces
├── config/                             # Redis, WebClient configs
├── filter/                             # SiteContextFilter
├── job/                                # Scheduled tasks (e.g., reconciliation)
├── client/                             # RestClient wrappers for other services
└── event/                              # Kafka event POJOs
```

**DTO convention**: Records in `controller/dto/`; use `from(entity)` static factory for entity-to-DTO conversion.

**Exception handling**: Custom exceptions (e.g., `AccountNotFoundException`, `InsufficientBalanceException`) caught by `GlobalExceptionHandler` and mapped to RFC 7807 `ProblemDetail`.

## Service Dependencies

| Service | Key Libraries |
|---------|--------------|
| `account-service` | Web, Data-JPA, Validation, Redis, Flyway, PostgreSQL |
| `transaction-service` | Web, Data-JPA, Validation, Flyway, Kafka, PostgreSQL |
| `notification-service` | Web, Kafka |
| `api-gateway` | Spring Cloud Gateway (2024.0.0), Redis Reactive |

All services share: Actuator, Micrometer Prometheus registry, Spring Boot Test, JUnit 5.

## Testing Notes

- Tests use **H2 in-memory** database — no running PostgreSQL required
- JUnit 5 via `useJUnitPlatform()` configured in root `build.gradle`
- No integration test profile needed; Spring Boot test autoconfiguration handles H2 substitution
- H2 test dependency is declared in service-level `build.gradle` files (`testImplementation 'com.h2database:h2'`)

## Infrastructure (Docker Compose)

| Container | Image | Port |
|-----------|-------|------|
| postgres | postgres:16 | 5432 |
| kafka | cp-kafka:7.7.0 | 29092 |
| zookeeper | cp-zookeeper:7.7.0 | 2181 |
| redis | redis:7 | 6379 |

**Monitoring stack** (separate overlay): Prometheus (:9090), Grafana (:3000, admin/admin), Loki (:3100), Promtail. Metrics exposed at `/actuator/prometheus` on each service.

## Kubernetes

```bash
minikube start --memory=6144 --cpus=4
eval $(minikube docker-env)
# Build images, then:
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/infra/
kubectl apply -f k8s/services/
minikube tunnel
```
