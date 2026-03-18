# Backend Engineering Learning Guide - Từ Code Đến Production

> Tài liệu đúc kết kiến thức từ project Mock Banking Backend.
> Mỗi bài học gắn trực tiếp với code thực tế trong repo này.
> Mục tiêu: nắm chắc kiến thức tương đương **Middle Backend Engineer (~3 năm kinh nghiệm)**.

---

## MỤC LỤC

| # | Chủ đề | Level |
|---|--------|-------|
| 1 | [REST API Design & Spring Boot Foundation](#bài-1-rest-api-design--spring-boot-foundation) | Junior |
| 2 | [Database Design & JPA + Flyway Migration](#bài-2-database-design--jpa--flyway-migration) | Junior |
| 3 | [DTO Pattern & Input Validation](#bài-3-dto-pattern--input-validation) | Junior |
| 4 | [Error Handling - RFC 7807 ProblemDetail](#bài-4-error-handling---rfc-7807-problemdetail) | Junior |
| 5 | [Service-to-Service Communication (Synchronous)](#bài-5-service-to-service-communication-synchronous) | Junior-Mid |
| 6 | [Kafka Deep Dive - Event-Driven Architecture](#bài-6-kafka-deep-dive---event-driven-architecture) | Mid |
| 7 | [Redis Caching - Cache-Aside Pattern](#bài-7-redis-caching---cache-aside-pattern) | Mid |
| 8 | [API Gateway Pattern](#bài-8-api-gateway-pattern) | Mid |
| 9 | [Dockerfile Deep Dive - Multi-stage Build](#bài-9-dockerfile-deep-dive---multi-stage-build) | Mid |
| 10 | [Docker Compose - Orchestration](#bài-10-docker-compose---orchestration) | Mid |
| 11 | [Kubernetes Deep Dive](#bài-11-kubernetes-deep-dive) | Mid-Senior |
| 12 | [Monitoring & Observability Stack](#bài-12-monitoring--observability-stack) | Mid-Senior |
| 13 | [Multisite / Multi-DC Architecture](#bài-13-multisite--multi-dc-architecture) | Senior |
| 14 | [Distributed System Patterns & Tư Duy Hệ Thống](#bài-14-distributed-system-patterns--tư-duy-hệ-thống) | Senior |

---

## Bài 1: REST API Design & Spring Boot Foundation

### 1.1 Kiến thức cần nắm

**REST (Representational State Transfer)** là kiến trúc API dựa trên HTTP methods:

| Method | Ý nghĩa | Idempotent? | Ví dụ trong project |
|--------|----------|-------------|---------------------|
| GET | Đọc resource | Có | `GET /api/accounts/{id}` |
| POST | Tạo resource | Không | `POST /api/accounts` |
| PUT | Update toàn bộ | Có | (chưa implement) |
| PATCH | Update 1 phần | Không | (chưa implement) |
| DELETE | Xoá resource | Có | `DELETE /api/accounts/{id}` |

> **Idempotent** = gọi 1 lần hay 100 lần, kết quả giống nhau. GET lấy account 100 lần = cùng data. DELETE account 100 lần = account vẫn bị xóa (lần 2+ có thể trả 404).

### 1.2 Code thực tế

**File**: `account-service/src/main/java/com/mockbank/account/controller/AccountController.java`

```java
@RestController                          // (1) Đánh dấu đây là REST controller
@RequestMapping("/api/accounts")         // (2) Base path cho tất cả endpoints
public class AccountController {

    private final AccountService accountService;  // (3) Inject service qua constructor

    // Constructor injection - KHÔNG dùng @Autowired field injection
    // Lý do: testable, immutable, fail-fast khi thiếu dependency
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping                          // GET /api/accounts
    public List<AccountResponse> getAllAccounts() {
        return accountService.getAllAccounts().stream()
                .map(AccountResponse::from)  // (4) Entity → DTO trước khi trả client
                .toList();
    }

    @PostMapping                         // POST /api/accounts
    @ResponseStatus(HttpStatus.CREATED)  // (5) Trả 201 thay vì 200
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        // @Valid = trigger validation annotations trong DTO
        // @RequestBody = parse JSON body thành Java object
        return AccountResponse.from(accountService.createAccount(request));
    }

    @GetMapping("/{id}/balance")         // GET /api/accounts/1/balance
    public BigDecimal getBalance(@PathVariable Long id) {
        return accountService.getBalance(id);
    }

    @DeleteMapping("/{id}")              // DELETE /api/accounts/1
    @ResponseStatus(HttpStatus.NO_CONTENT) // (6) 204 = thành công nhưng không trả body
    public void closeAccount(@PathVariable Long id) {
        accountService.closeAccount(id);
    }
}
```

### 1.3 Điểm cần nhớ

**HTTP Status Code quy ước:**
- `200 OK` - thành công, có body
- `201 Created` - tạo resource thành công
- `204 No Content` - thành công, không body (delete)
- `400 Bad Request` - client gửi sai data
- `401 Unauthorized` - chưa xác thực
- `403 Forbidden` - đã xác thực nhưng không có quyền
- `404 Not Found` - resource không tồn tại
- `500 Internal Server Error` - lỗi server

**Constructor Injection vs Field Injection:**
```java
// BAD - Field injection
@Autowired
private AccountService accountService;

// GOOD - Constructor injection (như trong project)
private final AccountService accountService;
public AccountController(AccountService accountService) {
    this.accountService = accountService;
}
```
Lý do: Constructor injection giúp (1) dễ test vì truyền mock qua constructor, (2) `final` đảm bảo immutable, (3) Spring fail ngay lúc startup nếu thiếu bean → không bị NullPointerException lúc runtime.

### 1.4 Phân biệt Public vs Internal API

**File**: `account-service/src/main/java/com/mockbank/account/controller/InternalAccountController.java`

```java
@RestController
@RequestMapping("/internal/accounts")   // Prefix /internal = chỉ service-to-service
public class InternalAccountController {
    @PostMapping("/{id}/debit")
    public AccountResponse debit(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return AccountResponse.from(accountService.debit(id, request.amount()));
    }

    @PostMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return AccountResponse.from(accountService.credit(id, request.amount()));
    }
}
```

**Tại sao tách Internal API?**
- Client (người dùng) KHÔNG được phép gọi debit/credit trực tiếp → lỗ hổng bảo mật
- Chỉ có `transaction-service` mới gọi (sau khi validate business logic)
- Gateway sẽ block mọi request tới `/internal/**`

> **Interview tip**: "Tại sao không để tất cả trong 1 controller?" → Separation of concerns. Public API phục vụ client, Internal API phục vụ service-to-service. Security boundary khác nhau.

---

## Bài 2: Database Design & JPA + Flyway Migration

### 2.1 Kiến thức cần nắm

**Database-per-Service** là pattern trong microservices: mỗi service sở hữu riêng 1 database, không service nào được truy cập DB của service khác.

```
account-service  → account_db      (sở hữu riêng)
transaction-svc  → transaction_db  (sở hữu riêng)
```

Lý do:
- **Loose coupling**: thay đổi schema account_db không ảnh hưởng transaction-service
- **Independent deployment**: deploy account-service mà không cần deploy transaction-service
- **Tech diversity**: mỗi service có thể dùng DB khác nhau (PostgreSQL, MongoDB,...)

### 2.2 JPA Entity

**File**: `account-service/src/main/java/com/mockbank/account/entity/Account.java`

```java
@Entity                              // (1) Đánh dấu đây là JPA entity → map với table
@Table(name = "accounts")           // (2) Tên table trong database
public class Account {

    @Id                              // (3) Primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // (4) Auto-increment bởi DB
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;    // (5) Constraints ở cả JPA lẫn DB level

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;
    // (6) LUÔN dùng BigDecimal cho tiền, KHÔNG BAO GIỜ dùng double/float
    // double: 0.1 + 0.2 = 0.30000000000000004 → sai tiền = sai business

    @Enumerated(EnumType.STRING)     // (7) Lưu "ACTIVE" thay vì 0,1,2
    private AccountStatus status = AccountStatus.ACTIVE;

    @PrePersist                      // (8) JPA lifecycle callback
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();  // (9) Tự update timestamp khi save
    }
}
```

**Tại sao `@Enumerated(EnumType.STRING)` chứ không phải `EnumType.ORDINAL`?**
- ORDINAL lưu index (0, 1, 2) → thêm enum value ở giữa = data sai hết
- STRING lưu "ACTIVE", "INACTIVE" → dễ đọc, safe khi thêm/xóa enum values

### 2.3 Flyway Migration

**File**: `account-service/src/main/resources/db/migration/V1__create_accounts_table.sql`

```sql
CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,            -- Auto-increment, 64-bit
    account_number VARCHAR(20) NOT NULL UNIQUE,
    owner_name  VARCHAR(100) NOT NULL,
    balance     NUMERIC(19, 2) NOT NULL DEFAULT 0.00,  -- 19 digits, 2 decimal
    currency    VARCHAR(3) NOT NULL DEFAULT 'VND',
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Index cho các column hay query
CREATE INDEX idx_accounts_account_number ON accounts(account_number);
CREATE INDEX idx_accounts_status ON accounts(status);
```

**Flyway hoạt động thế nào?**
1. Khi service start, Flyway scan folder `db/migration/`
2. Tìm các file `V{version}__{description}.sql`
3. So sánh với bảng `flyway_schema_history` trong DB
4. Chạy các migration chưa chạy, theo thứ tự version
5. Nếu file đã chạy bị sửa → **fail** (checksum mismatch) → đảm bảo consistency

**Quy tắc đặt tên**: `V1__create_accounts_table.sql`
- `V1` = version 1
- `__` = 2 dấu gạch dưới (bắt buộc)
- `create_accounts_table` = mô tả
- Lần sau thêm column → tạo file `V2__add_email_to_accounts.sql`

**Config trong application.yml:**
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # CHỈ validate schema, KHÔNG auto tạo/sửa table
                            # Flyway quản lý schema → ddl-auto: validate
    show-sql: true          # Log SQL queries (tắt ở production)
    open-in-view: false     # Tắt OSIV → tránh lazy loading ngoài transaction
  flyway:
    enabled: true
    locations: classpath:db/migration
```

> **`open-in-view: false` là gì?** Spring mặc định giữ Hibernate Session mở suốt request (từ controller → view). Điều này gây N+1 query problem khi lazy load trong controller. Tắt đi buộc bạn phải load data đầy đủ trong service layer.

### 2.4 Repository

**File**: `account-service/src/main/java/com/mockbank/account/repository/AccountRepository.java`

```java
public interface AccountRepository extends JpaRepository<Account, Long> {
    // Spring Data JPA tự generate SQL từ method name
    Optional<Account> findByAccountNumber(String accountNumber);
    // → SELECT * FROM accounts WHERE account_number = ?

    boolean existsByAccountNumber(String accountNumber);
    // → SELECT EXISTS(SELECT 1 FROM accounts WHERE account_number = ?)
}
```

Không cần viết implementation class. Spring Data JPA parse method name → generate query tự động.

**Naming convention**: `findBy{FieldName}`, `existsBy{FieldName}`, `countBy{FieldName}`, `deleteBy{FieldName}`.

---

## Bài 3: DTO Pattern & Input Validation

### 3.1 Tại sao cần DTO?

**DTO (Data Transfer Object)** = object chỉ dùng để truyền data giữa layers, KHÔNG chứa business logic.

```
Client ←→ [DTO] ←→ Controller ←→ Service ←→ [Entity] ←→ Repository ←→ DB
```

**Lý do tách Entity và DTO:**
1. **Security**: Entity có thể có field nhạy cảm (password hash) → DTO chỉ expose field cần thiết
2. **Flexibility**: Client cần format khác DB (VD: `createdAt` entity là `LocalDateTime`, DTO có thể format thành String)
3. **Stability**: Thay đổi DB schema (Entity) không làm vỡ API contract (DTO)
4. **Validation**: DTO chứa validation annotations, Entity chứa JPA annotations → tách concern

### 3.2 Java Records làm DTO

**File**: `account-service/src/main/java/com/mockbank/account/controller/dto/CreateAccountRequest.java`

```java
public record CreateAccountRequest(        // (1) record = immutable, auto-generate constructor/getter/equals/hashCode
    @NotBlank(message = "Owner name is required")    // (2) Validation: không null, không rỗng, không chỉ spaces
    String ownerName,

    @PositiveOrZero(message = "Initial balance must be >= 0")  // (3) >= 0
    BigDecimal initialBalance,

    String currency                        // (4) Optional field, không có validation
) {}
```

**File**: `account-service/src/main/java/com/mockbank/account/controller/dto/AccountResponse.java`

```java
public record AccountResponse(
    Long id,
    String accountNumber,
    String ownerName,
    BigDecimal balance,
    String currency,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    // (5) Static factory method: Entity → DTO
    public static AccountResponse from(Account account) {
        return new AccountResponse(
            account.getId(),
            account.getAccountNumber(),
            account.getOwnerName(),
            account.getBalance(),
            account.getCurrency(),
            account.getStatus().name(),    // Enum → String
            account.getCreatedAt(),
            account.getUpdatedAt()
        );
    }
}
```

### 3.3 Validation hoạt động thế nào?

```java
@PostMapping
public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
//                                    ^^^^^ trigger validation
```

1. Client gửi JSON → Spring deserialize thành `CreateAccountRequest`
2. `@Valid` trigger Jakarta Bean Validation
3. Nếu `ownerName` null → `MethodArgumentNotValidException` → Spring trả 400

**Các annotation validation thường dùng:**

| Annotation | Dùng cho | Ý nghĩa |
|-----------|----------|---------|
| `@NotNull` | Mọi type | Không null |
| `@NotBlank` | String | Không null + không rỗng + không chỉ spaces |
| `@NotEmpty` | String/Collection | Không null + không rỗng |
| `@Positive` | Number | > 0 |
| `@PositiveOrZero` | Number | >= 0 |
| `@Size(min, max)` | String/Collection | Giới hạn length |
| `@Email` | String | Format email |
| `@Pattern(regexp)` | String | Match regex |

---

## Bài 4: Error Handling - RFC 7807 ProblemDetail

### 4.1 Kiến thức cần nắm

**RFC 7807** (Problem Details for HTTP APIs) là chuẩn format error response:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Account not found: 999",
  "instance": "/api/accounts/999"
}
```

Thay vì mỗi API trả error format khác nhau, RFC 7807 chuẩn hóa → client chỉ cần parse 1 format.

### 4.2 Code thực tế

**File**: `account-service/src/main/java/com/mockbank/account/controller/GlobalExceptionHandler.java`

```java
@RestControllerAdvice                    // (1) Xử lý exception cho TẤT CẢ controllers
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)  // (2) Bắt exception cụ thể
    public ProblemDetail handleNotFound(AccountNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,        // (3) 404
            ex.getMessage()              // (4) "Account not found: 999"
        );
        // Spring tự set Content-Type: application/problem+json
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ProblemDetail handleInsufficientBalance(InsufficientBalanceException ex) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,      // 400
            ex.getMessage()
        );
    }
}
```

**Custom Exception:**

```java
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}
```

### 4.3 Flow khi lỗi xảy ra

```
Client: GET /api/accounts/999
  → AccountController.getAccount(999)
    → AccountService.getAccountById(999)
      → accountRepository.findById(999) → Optional.empty()
      → throw new AccountNotFoundException("Account not found: 999")
    → GlobalExceptionHandler.handleNotFound() bắt exception
    → return ProblemDetail(404, "Account not found: 999")
  → Client nhận: {"status":404, "detail":"Account not found: 999"}
```

> **Tại sao dùng RuntimeException thay vì checked Exception?** Trong Spring REST, checked exceptions bắt buộc khai báo `throws` ở mọi nơi, gây noise. RuntimeException + `@ExceptionHandler` = clean hơn, chỉ cần handle ở 1 chỗ duy nhất.

---

## Bài 5: Service-to-Service Communication (Synchronous)

### 5.1 Kiến thức cần nắm

Trong microservices, service A cần data từ service B. Có 2 cách:
- **Synchronous** (đồng bộ): REST call, gRPC → chờ response rồi mới tiếp tục
- **Asynchronous** (bất đồng bộ): Message queue (Kafka, RabbitMQ) → gửi xong không cần chờ

Project này dùng CẢ HAI:
- **REST (sync)**: `transaction-service` → gọi `account-service` để debit/credit
- **Kafka (async)**: `transaction-service` → publish event → `notification-service` consume

### 5.2 RestClient - HTTP client hiện đại của Spring

**File**: `transaction-service/src/main/java/com/mockbank/transaction/client/AccountServiceClient.java`

```java
@Component
public class AccountServiceClient {

    private static final String SITE_ID_HEADER = "X-Site-Id";
    private final RestClient restClient;

    public AccountServiceClient(
            RestClient.Builder restClientBuilder,                       // (1) Spring inject builder
            @Value("${app.account-service.base-url}") String baseUrl)  // (2) Đọc URL từ config
    {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)                                       // (3) Base URL: http://account-service:8081
                .requestInterceptor((request, body, execution) -> {     // (4) Interceptor: tự động gắn header
                    String siteId = MDC.get("siteId");                  // (5) Lấy siteId từ MDC (thread-local)
                    if (siteId != null) {
                        request.getHeaders().set(SITE_ID_HEADER, siteId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    public AccountResponse debit(Long accountId, BigDecimal amount) {
        return restClient.post()                                        // (6) POST request
                .uri("/internal/accounts/{id}/debit", accountId)        // (7) URI template
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MoneyRequest(amount))                         // (8) Request body
                .retrieve()                                             // (9) Thực hiện request
                .body(AccountResponse.class);                           // (10) Deserialize response
    }
}
```

### 5.3 Transfer flow (Saga Pattern đơn giản)

**File**: `transaction-service/src/main/java/com/mockbank/transaction/service/TransferService.java`

```java
@Transactional
public Transaction transfer(TransferRequest request) {
    // Step 1: Tạo transaction PENDING
    Transaction transaction = new Transaction();
    transaction.setReferenceId(UUID.randomUUID().toString());
    transaction.setStatus(TransactionStatus.PENDING);
    transaction = transactionRepository.save(transaction);

    try {
        // Step 2: Debit source account (REST call → account-service)
        accountServiceClient.debit(request.fromAccountId(), request.amount());

        // Step 3: Credit dest account (REST call → account-service)
        accountServiceClient.credit(request.toAccountId(), request.amount());

        // Step 4: Mark COMPLETED
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction = transactionRepository.save(transaction);

        // Step 5: Publish event (async - Kafka)
        eventPublisher.publish(transaction);
    } catch (Exception e) {
        // Step 4b: Mark FAILED
        transaction.setStatus(TransactionStatus.FAILED);
        transactionRepository.save(transaction);
        throw new TransferFailedException("Transfer failed: " + e.getMessage());
    }
    return transaction;
}
```

### 5.4 Saga Pattern - Phân tích kỹ

**Flow thành công:**
```
PENDING → debit OK → credit OK → COMPLETED → publish Kafka event
```

**Flow thất bại (credit fail):**
```
PENDING → debit OK → credit FAIL → FAILED → throw exception
```

**Vấn đề hiện tại** (và cách fix trong production):
- Debit OK nhưng credit FAIL → tiền đã bị trừ ở source nhưng chưa cộng ở dest
- Cần **compensating transaction**: gọi credit lại cho source account (rollback debit)
- Production dùng **Saga Orchestrator** (VD: Temporal, Camunda) để quản lý compensating transactions

> **Interview tip**: "Nếu credit fail thì sao?" → Giải thích compensating transaction, at-least-once delivery, idempotency key.

### 5.5 So sánh Sync vs Async

| Tiêu chí | Sync (REST) | Async (Kafka) |
|----------|-------------|---------------|
| **Khi nào dùng** | Cần response ngay | Fire-and-forget |
| **Coupling** | Tight (A phải biết B) | Loose (A publish, ai consume mặc kệ) |
| **Availability** | Nếu B down → A fail | Nếu consumer down → message đợi trong queue |
| **Latency** | Cộng dồn (A + B time) | A không chờ → nhanh |
| **Use case ở đây** | Debit/Credit (cần biết kết quả) | Notification (không cần chờ gửi SMS) |

---

## Bài 6: Kafka Deep Dive - Event-Driven Architecture

### 6.1 Kafka là gì? Giải thích từ gốc

**Apache Kafka** = distributed event streaming platform. Hiểu đơn giản: **hệ thống nhắn tin siêu bền** giữa các services.

```
Producer ──publish──→ [Topic: transaction-events] ──consume──→ Consumer
                       ├── Partition 0: [msg1, msg3, msg5]
                       ├── Partition 1: [msg2, msg4, msg6]
                       └── Partition 2: [msg7, msg8]
```

**Các khái niệm cốt lõi:**

| Khái niệm | Giải thích | Ví dụ trong project |
|-----------|------------|---------------------|
| **Topic** | Kênh chứa messages, giống table trong DB | `transaction-events` |
| **Partition** | Chia topic thành phần để xử lý song song | Default 1 partition |
| **Producer** | Gửi message vào topic | `TransactionEventPublisher` |
| **Consumer** | Đọc message từ topic | `TransactionEventListener` |
| **Consumer Group** | Nhóm consumers, mỗi partition chỉ 1 consumer đọc | `notification-group` |
| **Offset** | Vị trí đọc của consumer trong partition | Kafka tự quản lý |
| **Broker** | Server Kafka (1 cluster có nhiều brokers) | `kafka:9092` |
| **Zookeeper** | Quản lý metadata của Kafka cluster | `zookeeper:2181` |

### 6.2 Tại sao cần Kafka thay vì gọi REST trực tiếp?

**Scenario: Sau khi chuyển tiền, cần gửi SMS + Email + Push notification + Update analytics + ...**

**Cách 1: REST (không tốt)**
```java
// transaction-service phải biết TẤT CẢ downstream services
notificationService.sendSMS(event);     // Nếu SMS service down → transfer fail?
notificationService.sendEmail(event);   // Thêm service → phải sửa code
analyticsService.update(event);         // Tight coupling
```

**Cách 2: Kafka (tốt hơn)**
```java
// transaction-service CHỈ publish event, không care ai consume
kafkaTemplate.send("transaction-events", event);
// notification-service tự consume → gửi SMS/Email
// analytics-service tự consume → update stats
// Thêm service mới → thêm consumer, KHÔNG SỬA transaction-service
```

### 6.3 Kafka Architecture Internals

```
                    Kafka Cluster
┌─────────────────────────────────────────────┐
│                                             │
│  Topic: transaction-events                  │
│  ┌──────────────────────────────────────┐   │
│  │ Partition 0                          │   │
│  │ [offset 0] [offset 1] [offset 2] ...│   │
│  └──────────────────────────────────────┘   │
│                                             │
│  Broker 1 (kafka:9092)                      │
│  - Lưu messages trên disk (append-only log) │
│  - Messages KHÔNG bị xóa sau khi consume    │
│  - Retention policy: default 7 ngày         │
│                                             │
│  Zookeeper (zookeeper:2181)                 │
│  - Quản lý broker metadata                  │
│  - Leader election cho partitions            │
│  - (KRaft mode mới không cần Zookeeper)     │
└─────────────────────────────────────────────┘
```

**Kafka vs RabbitMQ vs Redis Pub/Sub:**

| Tiêu chí | Kafka | RabbitMQ | Redis Pub/Sub |
|----------|-------|----------|---------------|
| **Model** | Pull (consumer pull) | Push (broker push) | Push |
| **Persistence** | Disk (bền vững) | Memory + disk | Memory (mất khi restart) |
| **Replay** | Có (đọc lại từ offset cũ) | Không (consumed = gone) | Không |
| **Throughput** | Rất cao (millions/sec) | Cao (tens of thousands) | Rất cao nhưng không bền |
| **Use case** | Event sourcing, log | Task queue, RPC | Real-time, pub/sub đơn giản |
| **Ordering** | Có (trong 1 partition) | Có (trong 1 queue) | Không guarantee |

### 6.4 Producer - Gửi event

**File**: `transaction-service/src/main/java/com/mockbank/transaction/event/TransactionEventPublisher.java`

```java
@Component
public class TransactionEventPublisher {

    private static final String TOPIC = "transaction-events";
    private final KafkaTemplate<String, TransactionCompletedEvent> kafkaTemplate;
    private final String siteId;

    public void publish(Transaction transaction) {
        // (1) Tạo event object (record)
        var event = new TransactionCompletedEvent(
                transaction.getReferenceId(),    // unique identifier
                transaction.getFromAccountId(),
                transaction.getToAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name(),
                LocalDateTime.now(),
                siteId                           // (2) Gắn siteId vào event
        );

        // (3) Send: topic, key, value
        kafkaTemplate.send(TOPIC, transaction.getReferenceId(), event)
                .whenComplete((result, ex) -> {  // (4) Async callback
                    if (ex != null) {
                        log.error("Failed to publish: {}", ex.getMessage());
                    } else {
                        log.info("Published to {} [site={}]", TOPIC, siteId);
                    }
                });
    }
}
```

**Phân tích `kafkaTemplate.send(TOPIC, key, value)`:**
- **TOPIC** = `"transaction-events"` → gửi vào topic nào
- **key** = `referenceId` → Kafka hash key để chọn partition
  - Cùng key → luôn vào cùng partition → **guarantee ordering** cho 1 transaction
  - Khác key → có thể vào partition khác → parallel processing
- **value** = event object → serialize thành JSON

### 6.5 Consumer - Nhận event

**File**: `notification-service/src/main/java/com/mockbank/notification/listener/TransactionEventListener.java`

```java
@Component
public class TransactionEventListener {

    @KafkaListener(
        topics = "transaction-events",                      // (1) Subscribe topic
        groupId = "${spring.kafka.consumer.group-id}"       // (2) Consumer group từ config
    )
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        // (3) Set MDC cho logging
        if (event.siteId() != null) {
            MDC.put("siteId", event.siteId());
        }
        try {
            log.info("=== NOTIFICATION [site={}] ===", event.siteId());

            // (4) Mock SMS
            log.info("[MOCK SMS] Sent to account {}: Your transfer of {} {} is {}",
                    event.fromAccountId(), event.amount(), event.currency(),
                    event.status().toLowerCase());

            // (5) Mock Email
            log.info("[MOCK EMAIL] Sent to account {}: You received {} {}",
                    event.toAccountId(), event.amount(), event.currency());
        } finally {
            MDC.remove("siteId");
        }
    }
}
```

### 6.6 Kafka Config chi tiết

**Producer config** (`transaction-service/application.yml`):
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:29092                # Kafka broker address
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer      # Key = String
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer # Value = JSON
      properties:
        spring.json.type.mapping: "transactionCompleted:com.mockbank.transaction.event.TransactionCompletedEvent"
        # ↑ Type mapping: alias "transactionCompleted" → full class name
        # Gửi header __TypeId__=transactionCompleted (thay vì full class name)
```

**Consumer config** (`notification-service/application.yml`):
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:29092
    consumer:
      group-id: notification-group                   # Consumer group name
      auto-offset-reset: earliest                    # Đọc từ đầu nếu chưa có offset
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.mockbank.*"   # Cho phép deserialize packages
        spring.json.type.mapping: "transactionCompleted:com.mockbank.notification.event.TransactionCompletedEvent"
        # ↑ Map ngược: alias "transactionCompleted" → class ở notification-service
        # Producer class: com.mockbank.transaction.event.TransactionCompletedEvent
        # Consumer class: com.mockbank.notification.event.TransactionCompletedEvent
        # → KHÁC package nhưng cùng alias → deserialize OK
```

### 6.7 Type Mapping - Vấn đề kinh điển

**Vấn đề**: Producer serialize class `com.mockbank.transaction.event.TransactionCompletedEvent`, gửi header `__TypeId__` = full class name. Consumer ở notification-service KHÔNG có class đó (package khác) → **deserialize fail**.

**Giải pháp**: `spring.json.type.mapping` tạo alias:
```
Producer: "transactionCompleted" → com.mockbank.transaction.event.TransactionCompletedEvent
Consumer: "transactionCompleted" → com.mockbank.notification.event.TransactionCompletedEvent
```

Kafka gửi header `__TypeId__=transactionCompleted` (alias), consumer map alias → class của mình.

### 6.8 Kafka Dual Listener - Dev vs Docker

**Vấn đề**: Kafka chạy trong Docker (hostname `kafka`), local dev chạy ngoài Docker (hostname `localhost`).

**docker-compose.yml**:
```yaml
kafka:
  environment:
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,HOST:PLAINTEXT
    KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9092,HOST://localhost:29092
    KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

| Listener | Port | Ai dùng |
|----------|------|---------|
| `INTERNAL://kafka:9092` | 9092 | Services trong Docker network |
| `HOST://localhost:29092` | 29092 (mapped) | Local dev trên host machine |

```
Docker container → kafka:9092 (INTERNAL)
Local JVM        → localhost:29092 (HOST) → Docker port mapping → kafka:29092
```

### 6.9 Consumer Group - Hiểu sâu

```
Topic: transaction-events (3 partitions)
├── Partition 0
├── Partition 1
└── Partition 2

Consumer Group: "notification-group" (2 consumers)
├── Consumer A → đọc Partition 0, 1
└── Consumer B → đọc Partition 2

Quy tắc: 1 partition chỉ được 1 consumer trong cùng group đọc
→ Scale consumers <= số partitions mới hiệu quả
→ 3 partitions + 4 consumers = 1 consumer idle (thừa)
```

**Multisite dùng consumer group khác nhau:**
```yaml
# DC1
spring.kafka.consumer.group-id: notification-group-dc1
# DC2
spring.kafka.consumer.group-id: notification-group-dc2
```
→ DC1 consumer group và DC2 consumer group = 2 groups riêng biệt
→ Mỗi group đọc TẤT CẢ messages → nhưng ở đây Kafka cluster cũng tách → hoàn toàn isolated

### 6.10 Kafka Production Concerns

**Những thứ cần xử lý khi đưa Kafka lên production:**

1. **Idempotent Consumer**: Consumer có thể nhận duplicate message (network issue, rebalance) → cần idempotency key (referenceId) để skip processed messages
2. **Dead Letter Topic (DLT)**: Message xử lý fail quá N lần → gửi vào DLT thay vì retry vô hạn
3. **Schema Registry**: Quản lý schema evolution (Avro/Protobuf thay JSON) → backward/forward compatibility
4. **Partition Strategy**: Chọn key phù hợp. Ví dụ key = accountId → tất cả events của 1 account vào cùng partition → ordered
5. **Consumer Lag Monitoring**: Theo dõi lag (số messages chưa consume) → alert nếu lag tăng
6. **Exactly-once Semantics**: Kafka transactions + idempotent producer → avoid duplicate messages

---

## Bài 7: Redis Caching - Cache-Aside Pattern

### 7.1 Kiến thức cần nắm

**Cache-Aside Pattern** (hay Lazy Loading):
1. **Read**: Check cache → nếu có (cache hit) trả về. Nếu không (cache miss) → query DB → lưu cache → trả về.
2. **Write**: Update DB → evict cache (xóa cache entry) → lần read tiếp sẽ load lại từ DB.

```
          ┌─────────┐
          │  Redis   │
          │  Cache   │
          └────┬─────┘
    cache hit  │  cache miss
    ◄──────────┤──────────►┌──────────┐
               │           │ Database │
               │           └──────────┘
               │              ▲
               │  lưu cache   │ query
               └──────────────┘
```

### 7.2 Redis Config

**File**: `account-service/src/main/java/com/mockbank/account/config/RedisConfig.java`

```java
@Configuration
@EnableCaching                   // (1) Bật Spring Cache abstraction
public class RedisConfig {

    @Bean
    public RedisCacheConfiguration cacheConfiguration(
            @Value("${app.site-id:LOCAL}") String siteId)  // (2) Inject site ID
    {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))           // (3) TTL: tự hết hạn sau 5 phút
                .prefixCacheNameWith(siteId + ":")          // (4) Key prefix: "DC1:" hoặc "DC2:"
                .serializeValuesWith(                       // (5) Serialize value thành JSON
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer())
                );
    }
}
```

**Cache key format**: `{siteId}:{cacheName}::{key}` → `DC1:balances::1`

**Tại sao dùng JSON serializer?**
- Default JDK serializer: binary format, không đọc được bằng `redis-cli`, class phải implement Serializable
- JSON serializer: đọc được bằng `redis-cli`, debug dễ hơn, không cần Serializable

### 7.3 Caching Annotations

**File**: `account-service/src/main/java/com/mockbank/account/service/AccountService.java`

```java
@Cacheable(value = "accounts", key = "#id")     // (1) READ: check cache trước
public Account getAccountById(Long id) {
    log.info("Cache MISS - fetching account {} from database", id);  // (2) Log chỉ khi cache miss
    return accountRepository.findById(id)
            .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id));
}

@Cacheable(value = "balances", key = "#id")      // (3) Cache balance riêng
public BigDecimal getBalance(Long id) {
    log.info("Cache MISS - fetching balance for account {} from database", id);
    return accountRepository.findById(id)
            .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id))
            .getBalance();
}

@Transactional
@CacheEvict(value = {"accounts", "balances"}, key = "#id")  // (4) WRITE: xóa cả 2 cache
public Account debit(Long id, BigDecimal amount) {
    Account account = accountRepository.findById(id)
            .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id));
    if (account.getBalance().compareTo(amount) < 0) {
        throw new InsufficientBalanceException("Insufficient balance");
    }
    account.setBalance(account.getBalance().subtract(amount));
    return accountRepository.save(account);
}

@Transactional
@CacheEvict(value = {"accounts", "balances"}, key = "#id")  // (5) credit cũng evict cache
public Account credit(Long id, BigDecimal amount) { ... }
```

### 7.4 Cache Bug kinh điển: Self-Invocation

**Vấn đề ban đầu:**
```java
public BigDecimal getBalance(Long id) {
    return getAccountById(id).getBalance();  // Gọi method cùng class
}
```

`getAccountById()` có `@Cacheable` NHƯNG Spring AOP proxy chỉ intercept khi gọi từ BÊN NGOÀI class. Gọi nội bộ = bypass proxy = **cache không hoạt động**.

**Giải pháp**: Query DB trực tiếp trong `getBalance()`, không gọi qua `getAccountById()`:
```java
@Cacheable(value = "balances", key = "#id")
public BigDecimal getBalance(Long id) {
    return accountRepository.findById(id)    // Query DB trực tiếp
            .orElseThrow(...)
            .getBalance();
}
```

> **Interview tip**: "Spring Cache dùng AOP proxy → self-invocation không trigger cache. 3 cách fix: (1) tách method sang class khác, (2) inject self, (3) query DB trực tiếp."

### 7.5 TTL vs Eviction

| Strategy | Khi nào cache bị xóa | Ưu | Nhược |
|----------|----------------------|-----|-------|
| **TTL** (Time-To-Live) | Sau 5 phút | Tự động, simple | Có thể stale trong 5 phút |
| **Eviction** (@CacheEvict) | Khi data thay đổi | Data luôn fresh | Phải nhớ evict ở mọi nơi sửa data |
| **Cả hai** (project này) | TTL + Eviction | Best of both worlds | Phức tạp hơn 1 chút |

### 7.6 Redis distributed lock cho Scheduled Job

**File**: `account-service/src/main/java/com/mockbank/account/job/BalanceReconciliationJob.java`

```java
@Scheduled(fixedRate = 60000)  // Mỗi 60 giây
public void reconcileBalances() {
    String lockKey = "job:reconciliation:" + siteId;

    // (1) SET lockKey siteId NX EX 55
    // NX = chỉ set nếu key chưa tồn tại (Not eXists)
    // EX 55 = expire sau 55 giây (< 60s để tránh overlap)
    Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, siteId, Duration.ofSeconds(55));

    if (!Boolean.TRUE.equals(acquired)) {
        log.debug("Skipped - lock already held");   // (2) Instance khác đang chạy
        return;
    }

    // (3) Chỉ 1 instance chạy tại 1 thời điểm
    List<Account> accounts = accountRepository.findAll();
    for (Account account : accounts) {
        if (account.getBalance().compareTo(BigDecimal.ZERO) < 0) {
            log.warn("ISSUE: Account {} has negative balance", account.getAccountNumber());
        }
    }
}
```

**Tại sao cần distributed lock?**
- Scale account-service lên 3 instances → 3 instances đều chạy `@Scheduled` mỗi 60s
- Không cần 3 lần reconciliation → Redis lock đảm bảo chỉ 1 instance chạy

**Tại sao TTL 55s cho lock job chạy mỗi 60s?**
- Job chạy mỗi 60s, lock 55s
- Nếu instance crash → lock tự expire sau 55s → instance khác chạy tiếp
- 55 < 60 → lock luôn hết hạn trước lần chạy tiếp → không bị deadlock

---

## Bài 8: API Gateway Pattern

### 8.1 Kiến thức cần nắm

**API Gateway** = entry point duy nhất cho tất cả client requests. Thay vì client gọi trực tiếp từng service, client chỉ gọi gateway.

```
Không có Gateway:                    Có Gateway:
Client → account-service:8081        Client → Gateway:8080 ─→ account-service
Client → transaction-service:8082                          ─→ transaction-service
Client → notification-service:8083
```

**Responsibilities của Gateway:**
1. **Routing**: Forward request tới đúng service
2. **Authentication**: Kiểm tra API key / JWT token
3. **Authorization**: Block internal APIs
4. **Rate Limiting**: Giới hạn request/s
5. **Load Balancing**: Phân tải giữa instances
6. **Cross-cutting concerns**: Logging, metrics, CORS

### 8.2 Spring Cloud Gateway - Reactive

**File**: `api-gateway/build.gradle`
```gradle
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway'    // WebFlux-based
    implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'  // Rate limiter
}
```

> **Quan trọng**: Spring Cloud Gateway dùng **WebFlux** (reactive), KHÔNG PHẢI Spring MVC (servlet). Toàn bộ code dùng `Mono<Void>`, `ServerWebExchange` thay vì `HttpServletRequest`.

### 8.3 Route Configuration

**File**: `api-gateway/src/main/resources/application.yml`
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: account-service
          uri: http://localhost:8081                # Target service
          predicates:
            - Path=/api/accounts/**                # Match URL pattern
        - id: transaction-service
          uri: http://localhost:8082
          predicates:
            - Path=/api/transactions/**
      default-filters:
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 10   # 10 requests/second sustained
            redis-rate-limiter.burstCapacity: 20   # Burst up to 20
            key-resolver: "#{@apiKeyResolver}"     # Rate limit per API key
```

**Rate Limiter giải thích:**
- **replenishRate = 10**: Thêm 10 tokens mỗi giây
- **burstCapacity = 20**: Tối đa 20 tokens trong bucket
- **Token Bucket algorithm**: Mỗi request tốn 1 token. Hết token → 429 Too Many Requests
- Redis lưu token count per key (API key hoặc IP)

### 8.4 Auth Filter

**File**: `api-gateway/src/main/java/com/mockbank/gateway/filter/AuthFilter.java`

```java
@Component
public class AuthFilter implements GlobalFilter, Ordered {  // (1) GlobalFilter = áp dụng cho MỌI route

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // (2) Block internal API qua gateway → 403
        if (path.startsWith("/internal")) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        // (3) Skip auth cho health check
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        // (4) Kiểm tra API key
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey == null || !"mockbank-secret-key".equals(apiKey)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);  // 401
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);  // (5) Pass → tiếp tục filter chain
    }

    @Override
    public int getOrder() {
        return -1;  // (6) Số nhỏ = chạy trước. AuthFilter order -1, SiteFilter order -2
    }
}
```

### 8.5 Site Context Filter (Reactive)

**File**: `api-gateway/src/main/java/com/mockbank/gateway/filter/SiteContextFilter.java`

```java
@Component
public class SiteContextFilter implements GlobalFilter, Ordered {

    @Value("${app.site-id:LOCAL}")
    private String defaultSiteId;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String siteId = exchange.getRequest().getHeaders().getFirst("X-Site-Id");
        if (siteId == null || siteId.isBlank()) {
            siteId = defaultSiteId;
        }

        // (1) REACTIVE: không dùng MDC (thread-local) vì reactive là multi-thread
        // (2) Thay vào đó: mutate request, thêm header
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-Site-Id", siteId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -2;  // Chạy TRƯỚC AuthFilter (-1) → siteId available cho downstream
    }
}
```

> **Reactive vs Servlet Filter:**
> - Servlet: `HttpServletRequest` + `FilterChain` + MDC (thread-local OK vì 1 thread per request)
> - Reactive: `ServerWebExchange` + `GatewayFilterChain` + `Mono<Void>` (KHÔNG dùng MDC vì thread switch)

### 8.6 Rate Limiter Config

**File**: `api-gateway/src/main/java/com/mockbank/gateway/config/RateLimiterConfig.java`

```java
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            // (1) Rate limit theo API key (nếu có)
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (apiKey != null) {
                return Mono.just(apiKey);
            }
            // (2) Fallback: rate limit theo IP
            var remoteAddress = exchange.getRequest().getRemoteAddress();
            String key = remoteAddress != null
                    ? remoteAddress.getAddress().getHostAddress()
                    : "unknown";
            return Mono.just(key);
        };
    }
}
```

### 8.7 Request flow qua Gateway

```
curl -H "X-API-Key: mockbank-secret-key" http://localhost:8080/api/accounts/1/balance

1. SiteContextFilter (order -2)
   → Header X-Site-Id không có → inject default "DC1"

2. AuthFilter (order -1)
   → Path = /api/accounts/1/balance → KHÔNG phải /internal → OK
   → X-API-Key = "mockbank-secret-key" → match → OK

3. RequestRateLimiter (default filter)
   → Key = "mockbank-secret-key"
   → Check Redis: tokens remaining > 0 → OK
   → Nếu hết token → 429 Too Many Requests

4. Route matching
   → Path /api/accounts/** match route "account-service"
   → Forward to http://account-service:8081/api/accounts/1/balance

5. account-service xử lý → trả response → gateway forward lại cho client
```

---

## Bài 9: Dockerfile Deep Dive - Multi-stage Build

### 9.1 Kiến thức cần nắm

**Docker image** = snapshot của filesystem + metadata (CMD, ENV,...). **Container** = running instance của image.

**Multi-stage build** = Dockerfile có nhiều `FROM`. Mỗi `FROM` = 1 stage. Stage cuối cùng = final image. Stage trước đó bị discard → image nhỏ hơn nhiều.

### 9.2 Phân tích Dockerfile từng dòng

**File**: `account-service/Dockerfile`

```dockerfile
# ==================== STAGE 1: BUILD ====================
FROM eclipse-temurin:21-jdk AS build
# eclipse-temurin: OpenJDK distribution từ Adoptium (trusted, production-ready)
# 21-jdk: JDK 21 (cần javac để compile)
# AS build: đặt tên stage này là "build" (reference ở stage sau)

WORKDIR /workspace
# Set working directory. Tất cả command sau đây chạy trong /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
# Copy Gradle wrapper + config files TRƯỚC
# Docker cache layer: nếu file không đổi → layer được cache → không cần download dependencies lại

COPY account-service/ account-service/
# Copy source code SAU → chỉ rebuild khi code thay đổi

RUN chmod +x gradlew && ./gradlew :account-service:bootJar -x test --no-daemon
# chmod +x: Linux cần executable permission
# :account-service:bootJar: build Spring Boot fat JAR
# -x test: skip tests (test ở CI, không test trong Docker build)
# --no-daemon: tắt Gradle daemon (không cần trong container)

# ==================== STAGE 2: RUNTIME ====================
FROM eclipse-temurin:21-jre
# JRE (không có JDK) → nhỏ hơn ~200MB so với JDK image
# Stage 1 (~800MB JDK + source + build tools) bị DISCARD hoàn toàn

WORKDIR /app

COPY --from=build /workspace/account-service/build/libs/*.jar app.jar
# --from=build: copy file TỪ stage "build"
# Chỉ copy 1 file JAR → final image siêu nhỏ

EXPOSE 8081
# Document port (informational, không thực sự mở port)

ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=docker", "app.jar"]
# ENTRYPOINT: command chạy khi container start
# -Dspring.profiles.active=docker: activate Spring profile "docker"
# → load application-docker.yml (override DB/Redis URLs cho Docker network)
```

### 9.3 Docker Layer Caching - Tối ưu build time

```dockerfile
# Thứ tự COPY quan trọng:
COPY gradlew settings.gradle build.gradle ./    # Layer 1: ít thay đổi
COPY gradle/ gradle/                             # Layer 2: ít thay đổi
# → 2 layers này CACHED nếu gradle config không đổi

COPY account-service/ account-service/           # Layer 3: hay thay đổi
RUN ./gradlew :account-service:bootJar           # Layer 4: rebuild
# → Chỉ layer 3 + 4 phải rebuild khi code thay đổi
# → Gradle dependencies đã download ở layer 2 (cached) → NHANH hơn
```

**Nếu viết sai thứ tự:**
```dockerfile
# BAD: copy tất cả 1 lần → mọi thay đổi source code invalidate TẤT CẢ layers
COPY . .
RUN ./gradlew :account-service:bootJar
```

### 9.4 Image Size Comparison

| Stage | Nội dung | Size ước tính |
|-------|----------|--------------|
| Build (JDK) | JDK 21 + Gradle + Source + Dependencies + Build output | ~800MB |
| Runtime (JRE) | JRE 21 + 1 JAR file | ~300MB |

→ Multi-stage giảm **~500MB** per image. Với 4 services = tiết kiệm **~2GB**.

### 9.5 Dockerfile Best Practices

```dockerfile
# 1. Dùng specific tag, KHÔNG dùng :latest
FROM eclipse-temurin:21-jre          # GOOD
FROM eclipse-temurin:latest          # BAD - version có thể thay đổi

# 2. Non-root user (production)
RUN addgroup --system app && adduser --system --ingroup app app
USER app

# 3. Health check
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl -f http://localhost:8081/actuator/health || exit 1

# 4. .dockerignore (giảm build context)
# .git, .gradle, build/, *.md, ...

# 5. ENTRYPOINT vs CMD
ENTRYPOINT ["java", "-jar", "app.jar"]    # Fixed command, args từ docker run
CMD ["java", "-jar", "app.jar"]            # Có thể override toàn bộ từ docker run
# ENTRYPOINT thường dùng cho application, CMD cho utility

# 6. JVM Tuning cho container
ENTRYPOINT ["java",
    "-XX:+UseContainerSupport",       # JVM nhận biết container memory limit
    "-XX:MaxRAMPercentage=75.0",      # Dùng 75% container memory cho heap
    "-jar", "app.jar"]
```

### 9.6 Debug Docker build

```bash
# Build với output chi tiết
docker build --progress=plain -f account-service/Dockerfile -t test .

# Xem layers và size
docker history mockbank/account-service:latest

# Inspect image
docker inspect mockbank/account-service:latest

# Shell vào running container
docker exec -it mockbank-account-service sh
```

---

## Bài 10: Docker Compose - Orchestration

### 10.1 Kiến thức cần nắm

**Docker Compose** = tool định nghĩa và chạy multi-container Docker applications bằng 1 file YAML.

### 10.2 Phân tích docker-compose.yml chi tiết

```yaml
services:
  # ============ INFRASTRUCTURE ============

  postgres:
    image: postgres:16                       # Official PostgreSQL 16 image
    container_name: mockbank-postgres
    environment:
      POSTGRES_USER: mockbank                # Superuser name
      POSTGRES_PASSWORD: mockbank123
      POSTGRES_DB: account_db                # Default DB (tạo tự động)
    ports:
      - "5432:5432"                          # host:container
    volumes:
      - postgres-data:/var/lib/postgresql/data   # (1) Named volume → data persist khi restart
      - ./init-db.sql:/docker-entrypoint-initdb.d/init-db.sql  # (2) Init script
      # docker-entrypoint-initdb.d/ → PostgreSQL auto chạy .sql files lúc first start
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U mockbank"]  # (3) Health check
      interval: 10s                          # Kiểm tra mỗi 10s
      timeout: 5s                            # Timeout mỗi lần check
      retries: 5                             # Thử 5 lần trước khi unhealthy

  kafka:
    image: confluentinc/cp-kafka:7.7.0       # Confluent Kafka
    depends_on:
      - zookeeper                            # Start sau zookeeper
    ports:
      - "29092:29092"                        # Host listener port
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9092,HOST://localhost:29092
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1     # Dev only (1 broker → factor 1)
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"       # Auto create topics

  # ============ APPLICATION SERVICES ============

  account-service:
    build:
      context: .                             # Build context = root directory
      dockerfile: account-service/Dockerfile
    container_name: mockbank-account-service
    environment:
      SPRING_PROFILES_ACTIVE: docker,dc1     # Active profiles
    depends_on:
      postgres:
        condition: service_healthy           # (4) Chờ postgres HEALTHY mới start
      redis:
        condition: service_healthy

  api-gateway:
    build:
      context: .
      dockerfile: api-gateway/Dockerfile
    ports:
      - "8080:8080"                          # (5) CHỈ gateway expose ra host
    environment:
      SPRING_PROFILES_ACTIVE: docker,dc1

volumes:
  postgres-data:                             # (6) Named volume declaration
```

### 10.3 Docker Networking

```
Docker Compose tự tạo network: mock-banking-backend_default

Trong network này:
- Mọi service truy cập nhau bằng service name (DNS)
- account-service → postgres:5432
- transaction-service → kafka:9092
- transaction-service → account-service:8081

Từ bên ngoài (host machine):
- CHỈ access qua ports đã map
- localhost:8080 → api-gateway
- localhost:5432 → postgres (debug)
- localhost:29092 → kafka (local dev)
```

### 10.4 Compose Overlay Pattern

```bash
# Single-site (8 containers)
docker compose up

# Multisite (15 containers) - overlay thêm DC2
docker compose -f docker-compose.yml -f docker-compose.multisite.yml up
```

**Nguyên lý**: File compose thứ 2 MERGE với file thứ 1. Thêm services mới, override config nếu trùng tên.

### 10.5 depends_on vs healthcheck

```yaml
# service_started: chờ container START (có thể app chưa sẵn sàng)
depends_on:
  kafka:
    condition: service_started

# service_healthy: chờ container HEALTHY (healthcheck pass)
depends_on:
  postgres:
    condition: service_healthy
```

**Tại sao postgres dùng service_healthy?**
- PostgreSQL cần vài giây để init DB, chạy init scripts
- Nếu account-service start trước khi postgres ready → connection refused → crash

**Tại sao kafka dùng service_started?**
- Kafka broker cần 10-30s để sẵn sàng
- Spring Kafka có built-in retry khi connect fail → tự retry

### 10.6 Volumes

| Type | Persist? | Use case |
|------|----------|----------|
| Named volume | Có | Database data, cache data |
| Bind mount | Có | Config files, init scripts, source code (dev) |
| tmpfs | Không | Sensitive data, temp files |

### 10.7 Useful Docker Compose Commands

```bash
docker compose up --build                # Start all + rebuild
docker compose up -d --build             # Detached mode
docker compose logs -f account-service   # Follow logs
docker compose restart account-service   # Restart 1 service
docker compose down                      # Stop + remove containers
docker compose down -v                   # + remove volumes (DATA MẤT!)
docker stats                             # Resource usage
```

---

## Bài 11: Kubernetes Deep Dive

### 11.1 Kubernetes là gì?

**Kubernetes (K8s)** = container orchestration platform. Docker Compose chạy trên 1 máy, K8s chạy trên NHIỀU máy (cluster).

```
Docker Compose: 1 máy, dev/test
Kubernetes:     nhiều máy, production

K8s giải quyết:
- Nếu 1 máy chết → tự di chuyển containers sang máy khác
- Scale up/down tự động theo load
- Rolling update: deploy version mới mà không downtime
- Service discovery: tự động DNS cho services
- Secret management: quản lý credentials
```

### 11.2 K8s Architecture

```
┌──────────────────────────────────────────────────────┐
│                  KUBERNETES CLUSTER                    │
│                                                        │
│  ┌──────────────────┐                                  │
│  │   Control Plane   │                                  │
│  │  - API Server     │  ← kubectl giao tiếp với đây    │
│  │  - Scheduler      │  ← quyết định pod chạy ở node nào│
│  │  - Controller Mgr │  ← đảm bảo desired state       │
│  │  - etcd           │  ← key-value store (cluster state)│
│  └──────────────────┘                                  │
│                                                        │
│  ┌──────────────────┐  ┌──────────────────┐            │
│  │     Node 1        │  │     Node 2        │            │
│  │  ┌─────┐ ┌─────┐ │  │  ┌─────┐ ┌─────┐ │            │
│  │  │ Pod │ │ Pod │ │  │  │ Pod │ │ Pod │ │            │
│  │  │ acc │ │ txn │ │  │  │ ntf │ │ gw  │ │            │
│  │  └─────┘ └─────┘ │  │  └─────┘ └─────┘ │            │
│  │  kubelet          │  │  kubelet          │            │
│  │  kube-proxy       │  │  kube-proxy       │            │
│  └──────────────────┘  └──────────────────┘            │
└──────────────────────────────────────────────────────┘
```

### 11.3 K8s Core Concepts

| Concept | Là gì | Ví dụ |
|---------|-------|-------|
| **Pod** | Đơn vị nhỏ nhất. 1 Pod = 1+ containers | account-service pod |
| **Deployment** | Quản lý Pods: replicas, rolling update | account-service Deployment |
| **Service** | Stable network endpoint cho Pods | ClusterIP: account-service:8081 |
| **Namespace** | Logical isolation | `mockbank` namespace |
| **ConfigMap** | Config non-sensitive | DB URLs, Kafka addresses |
| **Secret** | Config sensitive | DB password |

### 11.4 Phân tích K8s files chi tiết

**File**: `k8s/infra/postgres.yml`

```yaml
# === SECRET: lưu credentials ===
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
  namespace: mockbank
type: Opaque
data:
  POSTGRES_USER: bW9ja2Jhbms=           # base64("mockbank")
  POSTGRES_PASSWORD: bW9ja2JhbmsxMjM=   # base64("mockbank123")
# base64 KHÔNG phải encryption → chỉ encoding
# Production dùng: Sealed Secrets, Vault, hoặc cloud KMS

---
# === CONFIGMAP: init SQL ===
apiVersion: v1
kind: ConfigMap
metadata:
  name: postgres-init
  namespace: mockbank
data:
  init-db.sql: |
    CREATE DATABASE transaction_db;
    GRANT ALL PRIVILEGES ON DATABASE transaction_db TO mockbank;

---
# === DEPLOYMENT ===
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
  namespace: mockbank
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres              # Tìm pods có label này
  template:
    metadata:
      labels:
        app: postgres            # Label cho pod
    spec:
      containers:
        - name: postgres
          image: postgres:16
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: postgres-secret    # Inject secret → env vars
          volumeMounts:
            - name: init-scripts
              mountPath: /docker-entrypoint-initdb.d
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "mockbank"]
            initialDelaySeconds: 5
            periodSeconds: 10
      volumes:
        - name: init-scripts
          configMap:
            name: postgres-init

---
# === SERVICE ===
apiVersion: v1
kind: Service
metadata:
  name: postgres                 # DNS: postgres.mockbank.svc.cluster.local
  namespace: mockbank
spec:
  selector:
    app: postgres                # Forward traffic → pods có label này
  ports:
    - port: 5432
      targetPort: 5432
  type: ClusterIP                # Chỉ trong cluster
```

### 11.5 Application Deployment

**File**: `k8s/services/account-service.yml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: account-service
  namespace: mockbank
spec:
  replicas: 1
  selector:
    matchLabels:
      app: account-service
  template:
    metadata:
      labels:
        app: account-service
    spec:
      containers:
        - name: account-service
          image: mockbank/account-service:latest
          imagePullPolicy: Never          # Dùng local image (minikube)
          ports:
            - containerPort: 8081
          envFrom:
            - configMapRef:
                name: app-config
          readinessProbe:                 # Sẵn sàng nhận traffic?
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 30       # Spring Boot cần ~30s start
            periodSeconds: 10
          livenessProbe:                  # Còn sống không?
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 60       # Cho đủ thời gian start
            periodSeconds: 15
```

### 11.6 Readiness vs Liveness Probe

```
Readiness Probe:
  "Pod sẵn sàng nhận traffic chưa?"
  FAIL → K8s DỪNG gửi traffic, KHÔNG restart pod
  Use case: Spring Boot đang start, đang warm cache

Liveness Probe:
  "Pod còn sống không?"
  FAIL → K8s RESTART pod
  Use case: Deadlock, OOM, hanging thread

Startup Probe (chưa dùng):
  "App đã start xong chưa?"
  Chạy trước readiness/liveness
  Use case: App start chậm
```

**Tại sao initialDelaySeconds khác nhau?**
- readiness: 30s (bắt đầu check sớm)
- liveness: 60s (chờ readiness ổn định trước, tránh restart loop)

### 11.7 Service Types

| Type | Accessible từ | Use case | Ví dụ |
|------|---------------|----------|-------|
| **ClusterIP** | Trong cluster | Service-to-service | account-service, postgres |
| **NodePort** | Node IP:Port | Dev/test | - |
| **LoadBalancer** | External IP | Production entry | api-gateway |

### 11.8 ConfigMap - Shared Configuration

**File**: `k8s/services/configmap.yml`
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: mockbank
data:
  SPRING_PROFILES_ACTIVE: docker
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/account_db
  SPRING_DATA_REDIS_HOST: redis
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
  APP_ACCOUNT_SERVICE_BASE_URL: http://account-service:8081
```

Spring Boot auto-map: `SPRING_DATASOURCE_URL` → `spring.datasource.url`

### 11.9 K8s Deployment Flow

```bash
# 1. Start minikube
minikube start --memory=6144 --cpus=4

# 2. Build images TRONG minikube
eval $(minikube docker-env)
docker build -f account-service/Dockerfile -t mockbank/account-service:latest .
# → imagePullPolicy: Never → dùng local image

# 3. Deploy
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/infra/
kubectl apply -f k8s/services/

# 4. Monitor
kubectl get pods -n mockbank -w
kubectl logs -f deploy/account-service -n mockbank
kubectl describe pod <name> -n mockbank

# 5. Access
minikube tunnel
curl http://localhost:8080/api/accounts -H "X-API-Key: mockbank-secret-key"
```

### 11.10 K8s vs Docker Compose

| Tiêu chí | Docker Compose | Kubernetes |
|----------|---------------|------------|
| **Scope** | 1 máy | Cluster (nhiều máy) |
| **Self-healing** | Restart policy | Reschedule pod sang node khác |
| **Scaling** | Manual | HPA auto-scale |
| **Rolling update** | Không built-in | Zero-downtime built-in |
| **Config** | env vars | ConfigMap + Secret |
| **Health checks** | healthcheck | readiness + liveness probes |
| **Use case** | Local dev | Production |

### 11.11 Production K8s Concepts (cần biết thêm)

```yaml
# 1. Resource Limits
resources:
  requests:
    memory: "256Mi"
    cpu: "250m"       # 0.25 CPU
  limits:
    memory: "512Mi"   # Vượt → OOMKilled
    cpu: "500m"       # Vượt → throttle

# 2. HPA (Horizontal Pod Autoscaler)
spec:
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          averageUtilization: 70

# 3. PDB (Pod Disruption Budget)
spec:
  minAvailable: 1    # Luôn có ít nhất 1 pod

# 4. Ingress
spec:
  rules:
    - host: api.mockbank.com
      http:
        paths:
          - path: /
            backend:
              service:
                name: api-gateway
```

---

## Bài 12: Monitoring & Observability Stack

### 12.1 Ba trụ cột của Observability

```
┌─────────┐  ┌─────────┐  ┌─────────┐
│ Metrics │  │  Logs   │  │ Traces  │
│ (số)    │  │ (text)  │  │ (flow)  │
└────┬────┘  └────┬────┘  └────┬────┘
     │            │            │
  Prometheus    Loki       (Jaeger - chưa impl)
     │            │
     └────────────┤
               Grafana
```

| Pillar | Trả lời | Tool |
|--------|---------|------|
| **Metrics** | Bao nhiêu requests/s? Latency? | Prometheus + Micrometer |
| **Logs** | Chuyện gì xảy ra lúc 10:30? | Loki + Promtail |
| **Traces** | Request đi qua service nào? | (Chưa implement) |

### 12.2 Metrics - Prometheus

**Prometheus scrape config:**
```yaml
# monitoring/prometheus/prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'account-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['account-service:8081']
  # ... tương tự cho các service khác
```

**Flow:**
```
Spring Boot → Micrometer collect metrics → /actuator/prometheus (text)
Prometheus → PULL mỗi 15s → Store time-series
Grafana → Query Prometheus → Dashboard
```

**PromQL queries quan trọng:**
```promql
# Request rate
rate(http_server_requests_seconds_count[5m])

# P99 latency
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

# Error rate
rate(http_server_requests_seconds_count{status=~"5.."}[5m])

# JVM memory
jvm_memory_used_bytes{area="heap"}
```

### 12.3 Logs - Loki + Promtail

**Log pattern:**
```yaml
logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%X{siteId:-NO_SITE}] %-5level %logger{36} - %msg%n"
    # Output: 10:30:00.123 [DC1] INFO  AccountService - Cache MISS
```

**Flow:**
```
Spring Boot → stdout → Docker daemon capture → Promtail đọc → Push to Loki → Grafana query
```

**LogQL queries:**
```logql
{container="mockbank-account-service"}                    # Tất cả logs
{container="mockbank-account-service"} |= "ERROR"         # Chỉ ERROR
{container=~"mockbank-.*"} |= "[DC1]"                     # Logs DC1
```

### 12.4 Grafana Datasources

```yaml
# monitoring/grafana/provisioning/datasources/datasources.yml
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
  - name: Loki
    type: loki
    url: http://loki:3100
```

### 12.5 SLI / SLO / SLA

| Term | Là gì | Ví dụ |
|------|-------|-------|
| **SLI** | Metric đo | P99 latency, error rate |
| **SLO** | Mục tiêu nội bộ | P99 < 500ms, error < 0.1% |
| **SLA** | Cam kết khách hàng | 99.9% uptime |

SLO 99.9% uptime → cho phép 43 phút downtime / tháng = error budget.

---

## Bài 13: Multisite / Multi-DC Architecture

### 13.1 Tại sao cần Multisite?

- DC mất điện → toàn bộ down (single point of failure)
- User xa → latency cao
- Compliance: data phải ở trong nước

### 13.2 Các Patterns

**Pattern 1: Site Identification** (SiteContextFilter)
```java
String siteId = request.getHeader("X-Site-Id");
MDC.put("siteId", siteId);  // Gắn vào thread → log, propagate downstream
```

**Pattern 2: Data Isolation** (Spring profiles)
```yaml
# DC1: account_db
# DC2: dc2_account_db → database KHÁC hoàn toàn
```

**Pattern 3: Cache Isolation** (Redis key prefix)
```java
.prefixCacheNameWith(siteId + ":")
// DC1: "DC1:balances::1"
// DC2: "DC2:balances::1"
```

**Pattern 4: Event Isolation** (Kafka cluster + consumer group)
```yaml
# DC1: kafka:9092 + notification-group-dc1
# DC2: kafka-dc2:9092 + notification-group-dc2
```

**Pattern 5: Header Propagation** (RestClient interceptor)
```java
// Gateway → inject X-Site-Id → transaction-svc → propagate → account-svc
```

**Pattern 6: Job Deduplication** (Redis lock per site)
```java
String lockKey = "job:reconciliation:" + siteId;  // Mỗi DC có lock riêng
```

### 13.3 Profile System

```
docker,dc1 → application.yml + application-docker.yml + application-dc1.yml
docker,dc2 → application.yml + application-docker.yml + application-dc2.yml

dc1 chỉ thêm site-id: DC1 (backward-compatible)
dc2 override DB/Redis/Kafka URLs cho DC2 infra
```

---

## Bài 14: Distributed System Patterns & Tư Duy Hệ Thống

### 14.1 Patterns trong project

| Pattern | Ở đâu | Mục đích |
|---------|-------|----------|
| Database per Service | Mỗi service có DB riêng | Loose coupling |
| API Gateway | api-gateway module | Entry point, auth, rate limiting |
| Event-Driven (Pub/Sub) | Kafka | Async notification |
| Cache-Aside | Redis | Performance |
| Saga (Simplified) | TransferService | Distributed transaction |
| Distributed Lock | Redis SET NX | Job deduplication |

### 14.2 CAP Theorem

```
C - Consistency: Mọi read thấy data mới nhất
A - Availability: Mọi request nhận response
P - Partition Tolerance: Chạy khi network split

P luôn xảy ra → chọn CP hoặc AP

CP: PostgreSQL (banking cần consistency)
AP: Redis cache (cache miss → query DB → OK)
```

### 14.3 Failure Modes

**Account Service Down:**
```
Transfer → REST call → TIMEOUT → Transaction FAILED
Cần: Circuit Breaker (Resilience4j) → fail fast
```

**Kafka Down:**
```
Transfer COMPLETED → publish event → Kafka unavailable → event mất
Cần: Outbox Pattern → lưu event vào DB → retry publish
```

**Redis Down:**
```
Cache miss → query DB → vẫn hoạt động (chậm hơn)
Rate limiter → fail open (cho qua) hoặc fail close (block tất cả)
```

### 14.4 Patterns chưa implement (production cần)

1. **Circuit Breaker** (Resilience4j): fail fast khi downstream down
2. **Retry + Exponential Backoff**: 1s → 2s → 4s → 8s + jitter
3. **Outbox Pattern**: guarantee event publish nếu transaction commit
4. **CQRS**: tách read/write model → read từ Elasticsearch
5. **Distributed Tracing** (Jaeger): trace request qua tất cả services
6. **Service Mesh** (Istio): mTLS, retry, circuit breaker ở infra level

### 14.5 Tư duy thiết kế hệ thống

**Checklist khi thiết kế microservice mới:**

```
1. DATA
   → Service sở hữu data gì? DB riêng hay shared?
   → Consistency level nào?

2. COMMUNICATION
   → Sync (REST/gRPC) hay Async (Kafka)?
   → Sync: cần response ngay
   → Async: fire-and-forget

3. FAILURE
   → Dependency X down → sao?
   → Circuit breaker? Fallback? Retry?

4. SCALE
   → Stateless? (dễ scale) Stateful? (khó)
   → Bottleneck: CPU? Memory? IO?

5. OBSERVABILITY
   → Metrics nào monitor? Alert khi nào?

6. SECURITY
   → Public hay Internal API?
   → Auth mechanism?
```

### 14.6 Interview Roadmap

**Junior → Mid:**
- REST design, HTTP status codes
- JPA N+1 problem, lazy vs eager
- @Transactional propagation, isolation
- Docker basics, Dockerfile, docker-compose
- Git workflow

**Mid → Senior:**
- Sync vs Async communication trade-offs
- Kafka: partitions, consumer groups, exactly-once
- Caching strategies, invalidation
- K8s: pods, deployments, services, probes, HPA
- CAP theorem, eventual consistency
- System design interviews

**Senior:**
- Saga, 2PC, Outbox pattern
- Event sourcing + CQRS
- Multi-region architecture
- Chaos engineering
- SLI/SLO/SLA, error budget
- Capacity planning

---

## Appendix A: Tech Stack

| Technology | Version | Vai trò |
|-----------|---------|---------|
| Java | 21 (LTS) | Language |
| Spring Boot | 3.4.1 | Framework |
| Spring Cloud Gateway | 2024.0.0 | API Gateway |
| PostgreSQL | 16 | Database |
| Apache Kafka | Confluent 7.7.0 | Message broker |
| Redis | 7 | Cache + Lock |
| Flyway | via Spring Boot | DB migration |
| Micrometer | via Spring Boot | Metrics |
| Prometheus | latest | Metrics storage |
| Grafana | latest | Dashboard |
| Loki + Promtail | latest | Log aggregation |
| Docker | latest | Containerization |
| Kubernetes | minikube | Orchestration |
| Gradle | 8.12 | Build tool |

## Appendix B: Lộ trình học

```
TUẦN 1-2: Foundation
├── Bài 1: REST API + Spring Boot
├── Bài 2: JPA + Flyway
├── Bài 3: DTO + Validation
└── Bài 4: Error Handling
    → Output: Viết được 1 CRUD service hoàn chỉnh

TUẦN 3-4: Service Communication
├── Bài 5: RestClient + Saga
├── Bài 6: Kafka (FOCUS)
└── Bài 7: Redis Caching
    → Output: 2 services giao tiếp sync + async

TUẦN 5-6: Infrastructure
├── Bài 8: API Gateway
├── Bài 9: Dockerfile (FOCUS)
├── Bài 10: Docker Compose
└── Bài 11: Kubernetes (FOCUS)
    → Output: Deploy full stack lên K8s

TUẦN 7-8: Production Readiness
├── Bài 12: Monitoring
├── Bài 13: Multisite
└── Bài 14: System Design Thinking
    → Output: Nắm tư duy thiết kế hệ thống
```

## Appendix C: Commands Cheat Sheet

```bash
# === GRADLE ===
./gradlew clean build -x test
./gradlew :account-service:bootRun
./gradlew dependencies

# === DOCKER ===
docker build -f account-service/Dockerfile -t test .
docker exec -it <container> sh
docker logs -f <container>
docker system prune -a

# === DOCKER COMPOSE ===
docker compose up --build
docker compose down -v
docker compose logs -f <service>
docker compose exec redis redis-cli

# === KUBERNETES ===
kubectl get pods -n mockbank
kubectl logs -f deploy/<name> -n mockbank
kubectl describe pod <name> -n mockbank
kubectl exec -it <pod> -n mockbank -- sh
kubectl scale deploy/<name> --replicas=3 -n mockbank
kubectl port-forward svc/<name> 8081:8081 -n mockbank

# === KAFKA ===
docker exec mockbank-kafka kafka-topics --list --bootstrap-server localhost:9092
docker exec mockbank-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic transaction-events --from-beginning
docker exec mockbank-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 --group notification-group --describe

# === REDIS ===
docker exec mockbank-redis redis-cli KEYS "*"
docker exec mockbank-redis redis-cli GET "DC1:balances::1"
docker exec mockbank-redis redis-cli MONITOR

# === POSTGRESQL ===
docker exec -it mockbank-postgres psql -U mockbank -d account_db
# \dt              → List tables
# \d accounts      → Describe table
# SELECT * FROM flyway_schema_history;
```
