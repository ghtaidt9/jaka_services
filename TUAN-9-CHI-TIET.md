# Tuần 9 chi tiết — Event-Driven: inventory-service, notification-service, Outbox, CQRS

> Giai đoạn gộp Tháng 2 + 3 (Tuần 5–10). **Không cần AWS** — code chạy local bằng Docker Compose, deploy lên kind bằng Helm chart tuần 8.

## Bối Cảnh & Mục Tiêu

**Đã hoàn thành:**
- ✅ Tuần 5: Terraform module + backend + test + CI
- ✅ Tuần 6–7: K8s cơ bản + stateful + Gateway + HPA
- ✅ Tuần 8: Helm chart dùng chung, Kafka (Strimzi), message đầu tiên order → payment

**Tuần 9 mục tiêu:**
- Viết mới **inventory-service** (quản lý tồn kho, Postgres riêng)
- Viết mới **notification-service** (gửi thông báo — log/email giả lập, không cần DB phức tạp)
- Thiết kế **event contract** giữa 4 service
- Làm event-driven **đúng cách**: Outbox pattern, idempotent consumer, retry + Dead Letter Topic
- System Design phần 2: Event-driven architecture, CQRS

---

## Tại Sao Cần Tuần 9?

```
Tuần 8 (cách đơn giản):
  @Transactional
  createOrder() {
      orderRepo.save(order);          // (1) ghi DB
      kafkaTemplate.send(event);      // (2) gửi Kafka
  }
  ❌ (1) OK, (2) lỗi mạng → order có trong DB nhưng không ai biết
  ❌ (2) OK, (1) rollback → inventory trừ kho cho order không tồn tại
  ❌ Kafka gửi lại (at-least-once) → inventory trừ kho 2 lần
  ❌ Message lỗi format → consumer retry mãi, chặn cả partition

Tuần 9:
  ✅ Outbox: ghi order + event vào CÙNG transaction DB, relay đẩy lên Kafka sau
  ✅ Idempotent consumer: bảng processed_events, trùng eventId → bỏ qua
  ✅ Retry có giới hạn + Dead Letter Topic cho message hỏng
```

---

## Chủ Đề 1: Thiết Kế 2 Service Mới & Event Contract

### Nội Dung Phải Học

- **Bounded context**: mỗi service sở hữu 1 phần nghiệp vụ + dữ liệu riêng (database-per-service như tháng 1)
- **Event vs Command**:
  - Event = "đã xảy ra" (`OrderCreated`) — thì quá khứ, không chờ trả lời
  - Command = "hãy làm" (`ReserveInventory`) — có người nhận cụ thể
- **Event contract**: schema ổn định, có `eventId`, `eventType`, `occurredAt`, `version`
- **Schema evolution**: chỉ **thêm** field (optional), không đổi nghĩa/xóa field → consumer cũ không vỡ
- **Topic design**: 1 topic mỗi aggregate (`order-events`, `inventory-events`, `payment-events`)

### Cấu Trúc Tiêu Chuẩn

```
                    ┌──────────────── Kafka ────────────────┐
order-service ──►   order-events      (OrderCreated, OrderCancelled, OrderConfirmed)
inventory-svc ──►   inventory-events  (InventoryReserved, InventoryRejected, InventoryReleased)
payment-svc   ──►   payment-events    (PaymentCompleted, PaymentFailed)
                    └───────────────────────────────────────┘
notification-svc ◄── nghe cả 3 topic, gửi thông báo cho khách
```

| Service | Port | Context path | DB | Consume | Produce |
|---|---|---|---|---|---|
| order-service | 8081 | `/api/orders` | orderdb | inventory-events, payment-events | order-events |
| payment-service | 8082 | `/api/payments` | paymentdb | inventory-events | payment-events |
| inventory-service | 8083 | `/api/inventory` | inventorydb | order-events, payment-events | inventory-events |
| notification-service | 8084 | `/api/notifications` | notificationdb (lịch sử gửi) | cả 3 | — |

```java
// Envelope dùng chung (copy vào mỗi service, KHÔNG tạo shared library giữa các service ở giai đoạn này)
public record EventEnvelope<T>(
        String eventId,        // UUID — khóa idempotency
        String eventType,      // "OrderCreated"
        int version,           // 1
        String aggregateId,    // orderId — dùng làm Kafka key
        Instant occurredAt,
        T payload) {}
```

**inventory-service — mô hình dữ liệu:**

```sql
CREATE TABLE products (
  sku        VARCHAR(50) PRIMARY KEY,
  name       VARCHAR(200) NOT NULL,
  available  INT NOT NULL CHECK (available >= 0),
  reserved   INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  version    BIGINT NOT NULL DEFAULT 0          -- optimistic locking (@Version)
);

CREATE TABLE reservations (
  order_id   BIGINT PRIMARY KEY,
  sku        VARCHAR(50) NOT NULL REFERENCES products(sku),
  quantity   INT NOT NULL,
  status     VARCHAR(20) NOT NULL               -- RESERVED / RELEASED / COMMITTED
);
```

> Theo đúng cách 2 service cũ: JPA `ddl-auto: update` + `CommandLineRunner` seed dữ liệu mẫu (ví dụ SKU `TEST-001` có 100 cái).

**order-service cũng phải sửa entity** — `Order` hiện chỉ có `orderNumber, customerName, status, totalAmount, currency, createdAt`, chưa có gì để inventory trừ kho:

- Thêm `productSku`, `quantity` (và `updatedAt` — tuần 10 cần cho timeout Saga), rồi thêm 2 field này vào `OrderCreated`
- ⚠️ **`ddl-auto: update` + cột `nullable = false` mới trên bảng đã có dữ liệu seed → Postgres báo lỗi** `column "product_sku" contains null values` và app không khởi động. Chọn 1: để cột mới nullable, hoặc xóa dữ liệu cũ (`kubectl delete pvc data-order-db-0` sau khi xóa StatefulSet — mất hết data)
- ⚠️ **Thêm giá trị mới vào enum `OrderStatus`** (`PENDING`, `AWAITING_PAYMENT`, `CONFIRMED` cho Saga): Hibernate 6+ tạo **check constraint** cho cột `@Enumerated(STRING)` lúc tạo bảng, và `ddl-auto: update` **không cập nhật** constraint đó → insert trạng thái mới lỗi `violates check constraint "orders_status_check"`. Sửa tay 1 lần trong psql:

```sql
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
```

> Chính 2 cái bẫy này là lý do production dùng migration (Flyway/Liquibase) thay cho `ddl-auto`.

**Output cụ thể:**
- 2 project Spring Boot mới (`inventory-service/inventory-service/`, `notification-service/notification-service/` — theo pattern thư mục cũ), mỗi cái có Dockerfile multi-stage + `docker-compose.yml` riêng (port Postgres khác: 5435, 5436)
- `docs/event-catalog.md`: bảng mọi event, producer, consumer, schema JSON mẫu
- Chứng minh: 4 service `bootRun` được, `/actuator/health` = UP

---

## Chủ Đề 2: Transactional Outbox

### Nội Dung Phải Học

- **Dual-write problem**: ghi DB + gửi Kafka không thể nằm chung 1 transaction
- **Outbox pattern**:
  1. Trong **cùng transaction**: ghi `orders` + ghi 1 dòng vào bảng `outbox`
  2. **Relay** (poller hoặc CDC) đọc `outbox` → gửi Kafka → đánh dấu đã gửi
- **Relay kiểu polling** (`@Scheduled`): đơn giản, đủ cho học
- **Relay kiểu CDC** (Debezium đọc WAL Postgres): production, biết khái niệm
- **Hệ quả**: relay có thể gửi trùng (gửi xong nhưng chưa kịp mark) → consumer **bắt buộc** idempotent
- `SELECT ... FOR UPDATE SKIP LOCKED`: nhiều replica relay không lấy trùng dòng

### Cấu Trúc Tiêu Chuẩn

```
POST /api/orders
  └─ @Transactional
       ├─ INSERT orders (...)
       └─ INSERT outbox (event_id, aggregate_id, event_type, payload, published_at=NULL)
                         ── COMMIT ──
OutboxRelay @Scheduled(fixedDelay=1000)
  ├─ SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
  ├─ kafkaTemplate.send(topic, aggregate_id, payload).get()
  └─ UPDATE outbox SET published_at = now()
```

> **Đổi cấu hình Kafka của tuần 8.** Payload outbox đã là chuỗi JSON → producer phải dùng `StringSerializer`. Giữ `JacksonJsonSerializer` sẽ serialize chuỗi đó **thêm 1 lần** thành `"{\"eventId\":...}"` và consumer không đọc được.
>
> ```yaml
> spring:
>   kafka:
>     producer:
>       value-serializer: org.apache.kafka.common.serialization.StringSerializer
>     consumer:
>       value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
> ```
>
> Phía consumer khai báo bean `JacksonJsonMessageConverter` → Spring Boot tự gắn vào listener container, và Spring Kafka đọc kiểu tham số của method (kể cả generic như `EventEnvelope<OrderCreated>`) để chuyển JSON → object. Bỏ các property `spring.json.*` của tuần 8.
>
> ```java
> @Bean
> RecordMessageConverter kafkaMessageConverter() {
>     return new JacksonJsonMessageConverter();
> }
> ```
>
> **Spring Boot 4 = Jackson 3**: `ObjectMapper` nằm ở package `tools.jackson.databind`, và `writeValueAsString` ném `JacksonException` (unchecked). Code Jackson 2 cũ (`com.fasterxml...`, `JsonProcessingException` checked) sẽ không compile.

```java
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false, unique = true) String eventId;
    @Column(nullable = false) String aggregateId;
    @Column(nullable = false) String eventType;
    @Column(nullable = false, columnDefinition = "text") String payload;
    Instant createdAt;
    Instant publishedAt;
}

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper json;

    @Transactional
    @CacheEvict(value = "orders", allEntries = true)
    public Order create(CreateOrderRequest req) {
        Order order = orders.save(Order.pending(req));
        // Ghi CẢ envelope (có eventId) — consumer cần eventId để chống trùng.
        // Ghi mỗi OrderCreated thì consumer nhận EventEnvelope sẽ có eventId = null.
        var event = new EventEnvelope<>(UUID.randomUUID().toString(), "OrderCreated", 1,
                order.getId().toString(), Instant.now(), OrderCreated.from(order));
        outbox.save(OutboxEvent.of(event.eventId(), "OrderCreated", order.getId().toString(),
                json.writeValueAsString(event)));
        return order;
    }
}
```

```java
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    @Query(value = """
            SELECT * FROM outbox WHERE published_at IS NULL
            ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch(@Param("limit") int limit);
}
```

> `@Scheduled` chỉ chạy khi có **`@EnableScheduling`** trên class `@SpringBootApplication` (hiện app chỉ có `@EnableCaching`). Thiếu là relay im lặng không chạy, không báo lỗi gì.

```java
@Component
@RequiredArgsConstructor
public class OutboxRelay {
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relay() {
        for (OutboxEvent e : outbox.lockUnpublishedBatch(100)) {   // native query FOR UPDATE SKIP LOCKED
            kafka.send("order-events", e.getAggregateId(), e.getPayload()).join();
            e.setPublishedAt(Instant.now());
        }
    }
}
```

**Output cụ thể:**
- Outbox cho order-service, inventory-service, payment-service
- Chứng minh:
  - Tắt Kafka → tạo 3 order → API vẫn trả 201, bảng `outbox` có 3 dòng `published_at IS NULL`
  - Bật Kafka lại → 3 event được gửi, `published_at` có giá trị
  - Viết integration test bằng Testcontainers (Postgres + Kafka)

---

## Chủ Đề 3: Idempotent Consumer, Retry & Dead Letter Topic

### Nội Dung Phải Học

- **Delivery semantics**: at-most-once / at-least-once / exactly-once. Thực tế dùng **at-least-once + idempotent**
- **Idempotent consumer**: bảng `processed_events(event_id PK)`; insert trong cùng transaction với xử lý nghiệp vụ → trùng thì bỏ qua
- **Lỗi tạm thời vs lỗi vĩnh viễn**:
  - Tạm thời (DB timeout) → retry với backoff
  - Vĩnh viễn (JSON sai, SKU không tồn tại) → không retry, đẩy sang DLT
- **Spring Kafka**: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → gửi message lỗi sang `<topic>-dlt` (tên mặc định của Spring Kafka 4; tutorial cũ trên mạng ghi `.DLT` là của bản 3.x trở về trước)
  - Mặc định recoverer gửi vào **cùng số partition** với message gốc → topic DLT phải có **≥ 3 partition** như `order-events`. Tạo trước bằng `KafkaTopic`, đừng để Kafka tự tạo (auto-create dùng số partition mặc định của broker, thường là 1 → gửi vào partition 2 sẽ lỗi):

```yaml
apiVersion: kafka.strimzi.io/v1     # khớp bản Strimzi như topics.yaml tuần 8 (Strimzi 1.x = v1)
kind: KafkaTopic
metadata: { name: order-events-dlt, namespace: kafka, labels: { strimzi.io/cluster: jaka } }
spec:
  partitions: 3
  replicas: 1
```

- **Poison pill**: JSON hỏng → với cấu hình ở trên (`StringDeserializer` + `JacksonJsonMessageConverter`), lỗi xảy ra lúc convert → `MessageConversionException`, mặc định **không retry**, đi thẳng DLT. Nếu dùng deserializer JSON trực tiếp thì phải bọc bằng `ErrorHandlingDeserializer`
- **Optimistic locking** (`@Version`) khi trừ kho song song

### Cấu Trúc Tiêu Chuẩn

```java
@Configuration
public class KafkaErrorConfig {
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);   // → <topic>-dlt, cùng partition
        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        // MessageConversionException (JSON hỏng) đã nằm sẵn trong danh sách không-retry mặc định
        handler.addNotRetryableExceptions(ProductNotFoundException.class);
        return handler;
    }
}
```

```java
@Component
@RequiredArgsConstructor
public class OrderEventsListener {
    private final ProcessedEventRepository processed;
    private final InventoryService inventory;

    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    @Transactional
    public void on(EventEnvelope<OrderCreated> event) {
        if (processed.existsById(event.eventId())) {
            return;                                     // đã xử lý → bỏ qua
        }
        inventory.reserve(event.payload());             // ghi reservations + outbox InventoryReserved/Rejected
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
```

**Output cụ thể:**
- Mọi consumer đều idempotent + có DLT
- Chứng minh:
  - Gửi lại cùng 1 event 3 lần bằng console producer → kho chỉ trừ 1 lần. Gửi kèm key để cùng partition: thêm `--property parse.key=true --property key.separator=:` rồi gõ `12:{"eventId":"...",...}`
  - Gửi 1 message JSON hỏng → nằm trong `order-events-dlt`, consumer vẫn xử lý message sau nó
  - 2 order cùng SKU còn 1 cái gửi đồng thời → 1 Reserved, 1 Rejected (không âm kho)

---

## Chủ Đề 4: notification-service

### Nội Dung Phải Học

- **Fan-out**: 1 event, nhiều consumer group khác nhau đều nhận (inventory và notification cùng nghe `order-events`)
- **Consumer group riêng** = bản sao độc lập của luồng event
- **Gửi thông báo**: giai đoạn này chỉ log + lưu bảng `notifications`. Có thể dùng **MailHog/Mailpit** (Docker) để thấy email thật mà không cần SES
- **Template thông báo** theo eventType

### Cấu Trúc Tiêu Chuẩn

```
order-events ──┬── group inventory-service    → trừ kho
               └── group notification-service → "Đơn #12 đã được tạo"
payment-events ─── group notification-service → "Thanh toán đơn #12 thành công"
```

```yaml
# docker-compose.yml (notification-service) — thêm Mailpit
  mailpit:
    image: axllent/mailpit
    ports: ["8025:8025", "1025:1025"]   # UI: http://localhost:8025
```

**Output cụ thể:**
- notification-service nghe 3 topic, lưu lịch sử, gửi mail qua Mailpit
- Chứng minh: tạo order → email "Đơn đã tạo" xuất hiện trong Mailpit UI

---

## Chủ Đề 5: System Design — Phần 2 (Event-Driven & CQRS)

### Nội Dung Phải Học

**Event-Driven Architecture**
- Event notification vs Event-carried state transfer vs Event sourcing
- Ưu: loose coupling, scale độc lập, chịu lỗi tốt. Nhược: khó debug, eventual consistency, cần correlation ID
- **Correlation ID**: truyền qua Kafka header để trace 1 đơn qua 4 service (chuẩn bị cho tracing tháng 4)

**CQRS (Command Query Responsibility Segregation)**
- Tách **write model** (chuẩn hóa, đảm bảo nghiệp vụ) và **read model** (phi chuẩn hóa, tối ưu cho query)
- Read model cập nhật từ event → eventual consistency
- Bài tập nhỏ: `GET /api/orders/{id}/summary` trả về order + trạng thái kho + trạng thái thanh toán, đọc từ bảng `order_summary` trong order-service được cập nhật bởi event từ `inventory-events` và `payment-events` — không gọi REST sang service khác
- Khi **không** nên dùng CQRS: CRUD đơn giản, team nhỏ

**Output cụ thể:**
- Endpoint `order summary` theo CQRS
- `docs/system-design-2.md`: so sánh 3 kiểu event, khi nào dùng CQRS, sơ đồ luồng event 4 service
- Tự trả lời: "Làm sao đảm bảo không mất event?", "Consumer nhận trùng thì sao?", "Eventual consistency ảnh hưởng UX thế nào?"

---

## Portfolio Cuối Tuần — Repo `springboot-event-driven`

```
springboot-event-driven/
├── order-service/
├── payment-service/
├── inventory-service/          # mới
├── notification-service/       # mới
├── docker-compose.yml          # chạy cả 4 + 4 Postgres + Redis + Kafka + Mailpit ở local
├── charts/spring-service/      # tái dùng từ tuần 8, thêm values cho 2 service mới
├── docs/
│   ├── event-catalog.md
│   └── system-design-2.md
└── README.md
```

**README.md phải trả lời được:**
1. 4 service sở hữu dữ liệu gì, giao tiếp qua event nào?
2. Outbox giải quyết vấn đề gì? Vẽ sơ đồ
3. Làm sao consumer không xử lý trùng?
4. Message hỏng đi đâu, xử lý lại thế nào?
5. CQRS áp dụng ở đâu trong hệ thống?

---

## 📋 Quick Reference — Tuần 9

| Pattern | Giải quyết | Cách làm |
|---|---|---|
| Transactional Outbox | Dual-write DB + Kafka | Bảng outbox + relay |
| Idempotent Consumer | Nhận trùng message | Bảng `processed_events` |
| Dead Letter Topic | Message hỏng chặn partition | `DeadLetterPublishingRecoverer` |
| Optimistic Locking | Trừ kho song song | `@Version` |
| CQRS | Query chéo nhiều service | Read model cập nhật từ event |
| Correlation ID | Trace 1 request qua nhiều service | Kafka header |

## 🎯 Key Learnings

- [ ] inventory-service + notification-service chạy được, database riêng
- [ ] Event catalog rõ ràng, có version
- [ ] Outbox: tắt Kafka không mất event
- [ ] Consumer idempotent, có retry + DLT
- [ ] CQRS read model cho order summary

**Tuần sau → Tuần 10: Saga, EKS (Terraform), deploy 4 service lên kind, tổng kết giai đoạn** 🚀
