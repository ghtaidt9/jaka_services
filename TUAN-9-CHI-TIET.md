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
  - Tạm thời (DB timeout, optimistic lock conflict) → retry với backoff
  - Vĩnh viễn (JSON sai, SKU không tồn tại) → không retry, đẩy sang DLT
- **Spring Kafka**: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → gửi message lỗi sang `<topic>-dlt` (tên mặc định của Spring Kafka 4; tutorial cũ trên mạng ghi `.DLT` là của bản 3.x trở về trước)
- **Poison pill**: JSON hỏng → với cấu hình tuần này (`StringDeserializer` + `JacksonJsonMessageConverter`), lỗi xảy ra lúc convert → `MessageConversionException`, mặc định **không retry**, đi thẳng DLT. Nếu dùng deserializer JSON trực tiếp thì phải bọc bằng `ErrorHandlingDeserializer`
- **Optimistic locking** (`@Version`) khi trừ kho song song

### Cấu Trúc Tiêu Chuẩn

#### Bản đồ: cái gì nằm ở đâu

Retry + DLT + idempotency là chuyện của **phía consume**. Service nào có `@KafkaListener` thì service đó cần đủ 3 thứ; service chỉ produce thì không cần.

DLT đặt tên theo **topic gốc**, không theo service: message lỗi đọc từ `order-events` → luôn vào `order-events-dlt`, dù service nào đọc lỗi.

| Service | Consume (tuần 9) | DLT có thể ghi vào | `KafkaErrorConfig` | Bảng `processed_events` |
|---|---|---|---|---|
| order-service | — (tuần 10 mới consume `inventory-events`, `payment-events`) | — | Chưa cần (thêm ở tuần 10) | Chưa cần |
| payment-service | `order-events`, `inventory-events` | `order-events-dlt`, `inventory-events-dlt` | ✅ | ✅ trong `paymentdb` |
| inventory-service | `order-events`, `payment-events` | `order-events-dlt`, `payment-events-dlt` | ✅ | ✅ trong `inventorydb` |
| notification-service | cả 3 topic (Chủ đề 4) | cả 3 DLT | ✅ | ✅ dùng luôn cột `eventId` unique của bảng `notifications` |

> Mỗi service một bản `KafkaErrorConfig` riêng (copy), **không** tạo shared library — cùng quy tắc với `EventEnvelope`.

#### Bước 1 — Khai báo topic: `jaka_infrastructure/k8s/kafka/topics.yml`

Gom **mọi** topic vào file topic của tuần 8 (hiện chỉ có `order-events`). Thêm 2 topic event mới + 3 topic DLT:

```yaml
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: { name: order-events, namespace: kafka, labels: { strimzi.io/cluster: jaka } }
spec: { partitions: 3, replicas: 1, config: { retention.ms: 604800000 } }
---
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: { name: inventory-events, namespace: kafka, labels: { strimzi.io/cluster: jaka } }
spec: { partitions: 3, replicas: 1, config: { retention.ms: 604800000 } }
---
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: { name: payment-events, namespace: kafka, labels: { strimzi.io/cluster: jaka } }
spec: { partitions: 3, replicas: 1, config: { retention.ms: 604800000 } }
---
# DLT: cùng số partition với topic gốc (lý do ở dưới), giữ lâu hơn để kịp điều tra
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: { name: order-events-dlt, namespace: kafka, labels: { strimzi.io/cluster: jaka } }
spec: { partitions: 3, replicas: 1, config: { retention.ms: 2592000000 } }   # 30 ngày
---
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: { name: inventory-events-dlt, namespace: kafka, labels: { strimzi.io/cluster: jaka } }
spec: { partitions: 3, replicas: 1, config: { retention.ms: 2592000000 } }
---
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata: { name: payment-events-dlt, namespace: kafka, labels: { strimzi.io/cluster: jaka } }
spec: { partitions: 3, replicas: 1, config: { retention.ms: 2592000000 } }
```

```bash
kubectl apply -f jaka_infrastructure/k8s/kafka/topics.yml
kubectl get kafkatopic -n kafka        # 6 topic, cột READY = True
```

> ⚠️ **Vì sao phải tạo DLT trước, đủ 3 partition:** mặc định recoverer gửi message lỗi vào **cùng số partition** với message gốc. Nếu để Kafka tự tạo `order-events-dlt` khi lần đầu có lỗi, nó dùng số partition mặc định của broker (thường là 1) → message lỗi từ partition 2 không có chỗ để ghi → recoverer lỗi → consumer kẹt.

#### Bước 2 — Error handler: `config/KafkaErrorConfig.java` (payment, inventory, notification)

```java
package com.jaka.inventory_service.config;          // đổi theo package của từng service

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaErrorConfig {
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);   // → <topic>-dlt, cùng partition
        var backOff = new ExponentialBackOff(1000L, 2.0);              // 1s, 2s, 4s, ...
        backOff.setMaxElapsedTime(10_000L);                            // tối đa ~10s rồi bỏ cuộc → DLT
        var handler = new DefaultErrorHandler(recoverer, backOff);
        // MessageConversionException (JSON hỏng) đã nằm sẵn trong danh sách không-retry mặc định
        handler.addNotRetryableExceptions(ProductNotFoundException.class);   // CHỈ inventory-service
        return handler;
    }
}
```

Cần biết:
- **Không cần gắn tay:** Spring Boot thấy bean kiểu `CommonErrorHandler` (`DefaultErrorHandler` là 1 loại) → tự gắn vào listener container, giống cách nó tự gắn `JacksonJsonMessageConverter` ở Chủ đề 2. Có thể để chung file với `KafkaConfig` (payment đã có) hoặc tách file — miễn nằm trong package được scan.
- **`KafkaTemplate` ở service chỉ consume:** payment/inventory/notification chưa có phần `producer:` trong `application.yaml`, nhưng Spring Boot vẫn tự tạo `KafkaTemplate` với serializer mặc định là `StringSerializer` — khớp, vì value đọc vào đã là `String` (`StringDeserializer`). Không cần thêm config.
- **`ProductNotFoundException`** là exception **tự viết** của inventory-service (SKU không có trong bảng `products` → lỗi vĩnh viễn, retry vô ích). payment và notification bỏ dòng `addNotRetryableExceptions`.
- **Nhiều group cùng ghi 1 DLT:** payment, inventory, notification đều đọc `order-events` → lỗi của cả 3 cùng vào `order-events-dlt`. Biết message lỗi của ai qua header `kafka_dlt-original-consumer-group` mà recoverer tự gắn (cùng với `kafka_dlt-exception-message`, `kafka_dlt-original-offset`, ...).

```java
// inventory-service: exception/ProductNotFoundException.java
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String sku) { super("SKU not found: " + sku); }
}
```

#### Bước 3 — Bảng chống trùng: `entity/ProcessedEvent.java` + repository (payment, inventory)

Mỗi service 1 bảng **trong DB của chính nó** (`ddl-auto: update` tự tạo bảng):

```java
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {
    @Id
    @Column(length = 36)
    private String eventId;              // = EventEnvelope.eventId() — PK nên trùng là DB từ chối luôn
    @Column(nullable = false)
    private Instant processedAt;
}

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
```

notification-service không cần bảng này: `notifications.eventId` đã `unique = true` → kiểm tra `existsByEventId(...)` trên chính bảng lịch sử.

#### Bước 4 — Listener idempotent: `consumer/OrderEventsListener.java` (ví dụ của inventory-service)

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
        inventory.reserve(event.payload());   // ghi reservations + outbox InventoryReserved/Rejected
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
```

Vì sao đúng:
- `reserve(...)` và `processed.save(...)` nằm **cùng 1 transaction DB** → hoặc cả hai được commit, hoặc không cái nào. Không có trường hợp "đã trừ kho nhưng chưa đánh dấu" (→ trừ 2 lần khi nhận lại).
- Có exception → transaction rollback → `DefaultErrorHandler` retry theo backoff → hết lượt → DLT. Kho không bị trừ dở dang.
- 2 bản trùng của cùng event có cùng key (orderId) → cùng partition → được **1 consumer xử lý tuần tự**, nên `existsById` rồi mới `save` không bị chạy đua với chính nó.
- payment-service làm y hệt với listener của nó — chỉ khác phần nghiệp vụ.

#### Bước 5 — Trừ kho an toàn khi chạy song song: `InventoryService.reserve` (inventory-service)

```java
@Transactional
public void reserve(OrderCreated order) {
    Product p = products.findById(order.productSku())
            .orElseThrow(() -> new ProductNotFoundException(order.productSku()));   // vĩnh viễn → DLT
    if (p.getAvailable() < order.quantity()) {
        // hết hàng là KẾT QUẢ nghiệp vụ, không phải lỗi → không throw, phát InventoryRejected
        outbox.save(/* InventoryRejected */);
        return;
    }
    p.setAvailable(p.getAvailable() - order.quantity());
    p.setReserved(p.getReserved() + order.quantity());
    reservations.save(new Reservation(order.orderId(), order.productSku(), order.quantity(), ReservationStatus.RESERVED));
    outbox.save(/* InventoryReserved */);
}
```

- 2 pod cùng trừ 1 SKU → cả 2 đọc `version = 5` → pod commit sau bị `UPDATE ... WHERE version = 5` không khớp dòng nào → `ObjectOptimisticLockingFailureException`.
- Exception này **để retry** (không thêm vào not-retryable): lần retry đọc lại tồn kho mới → thấy hết hàng → `InventoryRejected`. Đó là cách "không âm kho" mà không cần khóa bi quan.
- Outbox của inventory dùng đúng mẫu Chủ đề 2 (bảng `outbox` + `OutboxRelay`, gửi vào `inventory-events`).
- ⚠️ **Bước 5 chưa xong khi chỉ có placeholder `/* InventoryReserved */`**: payload phải là record riêng `InventoryReserved` / `InventoryRejected` (định nghĩa ở Bước 6, dòng code thay thế ở cuối Bước 9) — **đừng** dùng `OrderCreated` làm payload.

#### Bước 6 — Event catalog: mọi event, payload, ai gửi, ai nhận

Bước 4–5 mới làm **1 chiều** (inventory nghe `order-events`). Bảng ở đầu chủ đề còn 2 chiều chưa ai code: **payment nghe `inventory-events`** và **inventory nghe `payment-events`**. Trước khi code, chốt hợp đồng của từng event — đây cũng chính là nội dung `docs/event-catalog.md` (Output Chủ đề 1).

**Luồng tuần 9 (chưa có saga — order-service chưa nghe gì, trạng thái order đứng yên ở `CREATED` đến tuần 10):**

```
order-svc ──OrderCreated──► order-events ──► inventory-svc: reserve
                                              ├─ đủ hàng → InventoryReserved ─► inventory-events ──► payment-svc: charge
                                              │                                                     ├─ OK   → PaymentCompleted ─► payment-events ──► inventory-svc: commit
                                              │                                                     └─ lỗi  → PaymentFailed    ─► payment-events ──► inventory-svc: release
                                              │                                                                                                      └─ InventoryReleased ─► inventory-events
                                              └─ thiếu hàng → InventoryRejected ─► inventory-events  (payment bỏ qua)
notification-svc nghe cả 3 topic (Chủ đề 4) · order-svc nghe inventory-events + payment-events (tuần 10)
```

| Event | Topic | Producer | Payload (record) | Consumer tuần 9 → làm gì |
|---|---|---|---|---|
| `OrderCreated` | `order-events` | order | `orderId, orderNumber, customerName, totalAmount, currency, productSku, quantity` | inventory → reserve |
| `InventoryReserved` | `inventory-events` | inventory | `orderId, productSku, quantity, customerName, totalAmount, currency` | payment → charge |
| `InventoryRejected` | `inventory-events` | inventory | `orderId, productSku, quantity, customerName, totalAmount, reason` | payment → **bỏ qua** |
| `InventoryReleased` | `inventory-events` | inventory | `orderId, productSku, quantity, customerName, reason` | payment → **bỏ qua** |
| `PaymentCompleted` | `payment-events` | payment | `orderId, paymentReference, amount, currency, customerName` | inventory → commit reservation |
| `PaymentFailed` | `payment-events` | payment | `orderId, amount, currency, customerName, reason` | inventory → release reservation |

Vì sao payload chứa những field đó:
- **`InventoryReserved` phải mang `totalAmount`, `currency`, `customerName`** — payment chỉ nghe `inventory-events`, không đọc `order-events`, nên đây là nơi duy nhất nó biết phải thu bao nhiêu. Inventory chép các field này từ `OrderCreated` sang.
- **Mọi event đều có `customerName`** — notification (Chủ đề 4) cần nó để viết "Chào Tai, ...". Không có thì notification phải gọi ngược order-service → mất tính tách rời.
- **Mỗi event 1 record riêng** (không tái dùng `OrderCreated` làm payload của `InventoryReserved`): consumer đọc tên field là hiểu event nói gì, và đổi `OrderCreated` sau này không vô tình đổi hợp đồng của inventory.
- **Mỗi service chỉ copy record của event nó dùng**: inventory có `OrderCreated`, `PaymentCompleted`, `PaymentFailed` (đọc) + 3 record `Inventory*` (ghi); payment có `InventoryReserved` (đọc) + `PaymentCompleted`, `PaymentFailed` (ghi).

```java
// inventory-service: dto/  (payment-service copy InventoryReserved; inventory copy PaymentCompleted, PaymentFailed)
public record InventoryReserved(Long orderId, String productSku, int quantity,
                                String customerName, BigDecimal totalAmount, String currency) {}
public record InventoryRejected(Long orderId, String productSku, int quantity, String customerName, BigDecimal totalAmount, String reason) {}
public record InventoryReleased(Long orderId, String productSku, int quantity, String customerName, String reason) {}

// payment-service: dto/
public record PaymentCompleted(Long orderId, String paymentReference, BigDecimal amount, String currency, String customerName) {}
public record PaymentFailed(Long orderId, BigDecimal amount, String currency, String customerName, String reason) {}
```

#### Bước 7 — Gom code ghi outbox: `outbox/OutboxWriter.java` (order, inventory, payment)

Mỗi chỗ phát event đều lặp 3 dòng: tạo envelope → `writeValueAsString` → `outbox.save`. Gom vào 1 component để service nghiệp vụ chỉ còn 1 dòng `outboxWriter.write(...)`:

```java
@Component
@RequiredArgsConstructor
public class OutboxWriter {
    private final OutboxRepository outbox;
    private final ObjectMapper json;               // tools.jackson.databind.ObjectMapper

    // Gọi BÊN TRONG @Transactional của nghiệp vụ → dòng outbox commit cùng dữ liệu nghiệp vụ
    public void write(String eventType, Long orderId, Object payload) {
        var event = new EventEnvelope<>(UUID.randomUUID().toString(), eventType, 1,
                orderId.toString(), Instant.now(), payload);
        outbox.save(OutboxEvent.of(event.eventId(), eventType, orderId.toString(),
                json.writeValueAsString(event)));
    }
}
```

`aggregateId` luôn là **orderId** (kể cả event của inventory và payment) → mọi event về cùng 1 order nằm cùng partition, đúng thứ tự: `InventoryReleased` không thể vượt `InventoryReserved`.

Mỗi service phát event cần đủ bộ của Chủ đề 2: bảng `outbox`, `OutboxRepository`, `OutboxRelay` (đổi tên topic), **`@EnableScheduling`**, và phần `producer:` trong `application.yaml`:

```yaml
spring:
  kafka:
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      properties:
        enable.idempotence: true
```

| Service | `OutboxRelay` gửi vào |
|---|---|
| order-service | `order-events` |
| inventory-service | `inventory-events` |
| payment-service | `payment-events` |

#### Bước 8 — 1 topic, nhiều loại event: dispatch theo `eventType`

`payment-events` chứa **2 loại** payload (`PaymentCompleted`, `PaymentFailed`), `inventory-events` chứa **3 loại**. Listener không thể khai báo `EventEnvelope<PaymentCompleted>` vì message tiếp theo có thể là `PaymentFailed`. Cách làm:

1. Khai báo `EventEnvelope<JsonNode>` → converter đọc phần envelope, để `payload` ở dạng cây JSON chưa ép kiểu
2. `switch` theo `eventType`
3. Ép `payload` sang đúng record bằng `json.treeToValue(...)`
4. Event không quan tâm → `default` bỏ qua (không throw — throw là đẩy vào DLT vô cớ)

`JsonNode` của Jackson 3 nằm ở `tools.jackson.databind.JsonNode`.

`order-events` ở tuần 9 chỉ có `OrderCreated` nên listener của Bước 4 giữ `EventEnvelope<OrderCreated>` được. Tuần 10 thêm `OrderCancelled` vào `order-events` → lúc đó đổi listener đó sang cùng kiểu dispatch này.

#### Bước 9 — inventory-service nghe `payment-events`: commit / release

```java
// consumer/PaymentEventConsumer.java
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {
    private final ProcessedEventRepository processed;
    private final InventoryService inventory;
    private final ObjectMapper json;

    @KafkaListener(topics = "payment-events", groupId = "inventory-service")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if (processed.existsById(event.eventId())) {
            return;
        }
        switch (event.eventType()) {
            case "PaymentCompleted" -> inventory.commit(json.treeToValue(event.payload(), PaymentCompleted.class));
            case "PaymentFailed"    -> inventory.release(json.treeToValue(event.payload(), PaymentFailed.class));
            default -> log.debug("Ignoring {}", event.eventType());
        }
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
```

```java
// service/InventoryService.java — thêm 2 method
@Transactional
public void commit(PaymentCompleted payment) {
    Reservation r = reservations.findById(payment.orderId())
            .orElseThrow(() -> new ReservationNotFoundException(payment.orderId()));
    if (r.getStatus() != ReservationStatus.RESERVED) {
        return;                                   // đã COMMITTED/RELEASED trước đó → không làm lại
    }
    Product p = products.findById(r.getSku()).orElseThrow();
    p.setReserved(p.getReserved() - r.getQuantity());    // hàng rời kho: chỉ giảm reserved (available đã giảm lúc reserve)
    r.setStatus(ReservationStatus.COMMITTED);
}

@Transactional
public void release(PaymentFailed payment) {
    Reservation r = reservations.findById(payment.orderId())
            .orElseThrow(() -> new ReservationNotFoundException(payment.orderId()));
    if (r.getStatus() != ReservationStatus.RESERVED) {
        return;
    }
    Product p = products.findById(r.getSku()).orElseThrow();
    p.setReserved(p.getReserved() - r.getQuantity());    // trả hàng về: reserved → available
    p.setAvailable(p.getAvailable() + r.getQuantity());
    r.setStatus(ReservationStatus.RELEASED);
    outboxWriter.write("InventoryReleased", r.getOrderId(),
            new InventoryReleased(r.getOrderId(), r.getSku(), r.getQuantity(), payment.customerName(), payment.reason()));
}
```

Số tồn kho qua từng event (ví dụ `TEST-001` = 100, đặt 2):

| Sau event | `available` | `reserved` | `reservations.status` |
|---|---|---|---|
| (ban đầu) | 100 | 0 | — |
| `OrderCreated` → reserve | 98 | 2 | `RESERVED` |
| `PaymentCompleted` → commit | 98 | 0 | `COMMITTED` |
| *hoặc* `PaymentFailed` → release | 100 | 0 | `RELEASED` |

- **Kiểm tra `status != RESERVED` = lớp idempotent thứ 2**, theo nghiệp vụ: kể cả `processed_events` bị lọt, release 2 lần cũng không cộng kho 2 lần. Compensation (release) bắt buộc idempotent — tuần 10 sẽ cần điều này.
- **`ReservationNotFoundException`** (tự viết, giống `ProductNotFoundException`): payment báo kết quả cho order mà inventory không hề giữ hàng → dữ liệu sai, retry vô ích → thêm vào `addNotRetryableExceptions` để đi thẳng DLT.
- `InventoryReserved` của Bước 5 cũng đổi sang record mới:
  ```java
  outboxWriter.write("InventoryReserved", order.orderId(), new InventoryReserved(order.orderId(),
          order.productSku(), order.quantity(), order.customerName(), order.totalAmount(), order.currency()));
  // nhánh thiếu hàng:
  outboxWriter.write("InventoryRejected", order.orderId(), new InventoryRejected(order.orderId(),
          order.productSku(), order.quantity(), order.customerName(), order.totalAmount(), "OUT_OF_STOCK"));
  ```

#### Bước 10 — payment-service nghe `inventory-events`: charge

payment-service **đổi nguồn**: bỏ listener `order-events` của tuần 8 (xóa `OrderEventConsumer`), chuyển sang nghe `inventory-events` — chỉ thu tiền **sau khi** kho đã giữ hàng. Thu tiền trước rồi mới biết hết hàng thì lại phải hoàn tiền.

Không cần reset offset: group `payment-service` chưa từng đọc `inventory-events` nên sẽ đọc từ đầu (`auto-offset-reset: earliest`). Offset cũ của group trên `order-events` để nguyên cũng không sao — không còn listener nào đọc topic đó.

```java
// consumer/InventoryEventConsumer.java
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventConsumer {
    private final ProcessedEventRepository processed;
    private final PaymentService payments;
    private final ObjectMapper json;

    @KafkaListener(topics = "inventory-events", groupId = "payment-service")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if (processed.existsById(event.eventId())) {
            return;
        }
        if ("InventoryReserved".equals(event.eventType())) {
            payments.charge(json.treeToValue(event.payload(), InventoryReserved.class));
        }
        // InventoryRejected / InventoryReleased: không có gì để thu → bỏ qua
        processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
```

```java
// service/PaymentService.java — thêm method
@Transactional
@CacheEvict(value = "payments", allEntries = true)
public void charge(InventoryReserved req) {
    if (paymentRepository.existsByOrderId(req.orderId())) {
        return;                                    // lớp idempotent theo nghiệp vụ: 1 order chỉ thu 1 lần
    }
    // Giả lập cổng thanh toán: từ chối khi số tiền lớn hoặc khách tên "fail"
    boolean approved = req.totalAmount().compareTo(new BigDecimal("1000")) <= 0
            && !"fail".equalsIgnoreCase(req.customerName());

    Payment payment = paymentRepository.save(Payment.builder()
            .paymentReference("PAY-" + System.currentTimeMillis())
            .orderId(req.orderId())
            .amount(req.totalAmount())
            .currency(req.currency())
            .method(PaymentMethod.CREDIT_CARD)
            .status(approved ? PaymentStatus.COMPLETED : PaymentStatus.FAILED)
            .createdAt(LocalDateTime.now())
            .build());

    if (approved) {
        outboxWriter.write("PaymentCompleted", req.orderId(), new PaymentCompleted(req.orderId(),
                payment.getPaymentReference(), req.totalAmount(), req.currency(), req.customerName()));
    } else {
        outboxWriter.write("PaymentFailed", req.orderId(), new PaymentFailed(req.orderId(),
                req.totalAmount(), req.currency(), req.customerName(), "CARD_DECLINED"));
    }
}
```

```java
// repository/PaymentRepository.java — thêm
boolean existsByOrderId(Long orderId);
```

- **Bị từ chối thanh toán là kết quả nghiệp vụ, không phải lỗi** → không throw; lưu `Payment` trạng thái `FAILED` và phát `PaymentFailed`. Chỉ lỗi hạ tầng (DB chết) mới throw → retry.
- `PaymentDataSeeder` seed `orderId` 1..8 — order mới của order-service bắt đầu từ 9 trở lên nên không đụng nhau. Nếu đã xóa DB order (Chủ đề 2) mà chưa xóa DB payment, `existsByOrderId` sẽ khớp nhầm order 1..8 cũ → xóa luôn PVC `data-payment-db-0` cho sạch.

#### Bảng tổng: mỗi service cần những gì sau Chủ đề 3

| Thành phần | order | inventory | payment |
|---|---|---|---|
| `outbox` + `OutboxRelay` + `@EnableScheduling` + `producer:` | ✅ (Chủ đề 2) | ✅ → `inventory-events` | ✅ → `payment-events` |
| `OutboxWriter` | ✅ | ✅ | ✅ |
| `JacksonJsonMessageConverter` + `StringDeserializer` | tuần 10 | ✅ | ✅ |
| `KafkaErrorConfig` (retry + DLT) | tuần 10 | ✅ | ✅ |
| `processed_events` | tuần 10 | ✅ | ✅ |
| Listener | — | `order-events` (Bước 4), `payment-events` (Bước 9) | `inventory-events` (Bước 10) |
| Record đọc | — | `OrderCreated`, `PaymentCompleted`, `PaymentFailed` | `InventoryReserved` |
| Record ghi | `OrderCreated` | `InventoryReserved`, `InventoryRejected`, `InventoryReleased` | `PaymentCompleted`, `PaymentFailed` |


**Output cụ thể:**
- payment-service, inventory-service (và notification ở Chủ đề 4) đều idempotent + có retry + DLT
- `topics.yml` có đủ 6 topic, `kubectl get kafkatopic -n kafka` đều READY
- inventory nghe `order-events` + `payment-events`, payment nghe `inventory-events`, cả 3 service phát event qua outbox
- `docs/event-catalog.md` = bảng Bước 6
- Chứng minh:

1. **Gửi trùng 3 lần → kho chỉ trừ 1 lần.** Mở console producer, gửi kèm key (orderId) để cùng partition, dán **cùng 1 dòng** 3 lần (cùng `eventId`):
   ```bash
   kubectl exec -it -n kafka jaka-dual-role-0 -- bin/kafka-console-producer.sh \
     --bootstrap-server localhost:9092 --topic order-events \
     --property parse.key=true --property key.separator=:
   ```
   ```
   900:{"eventId":"dup-test-1","eventType":"OrderCreated","version":1,"aggregateId":"900","occurredAt":"2026-01-01T00:00:00Z","payload":{"orderId":900,"orderNumber":"ORD-DUP","customerName":"Dup","totalAmount":10,"currency":"USD","productSku":"TEST-001","quantity":1}}
   ```
   ```bash
   kubectl exec -n dev inventory-db-0 -- psql -U inventory_user -d inventorydb \
     -c "select sku, available, reserved from products where sku='TEST-001';" \
     -c "select * from processed_events where event_id='dup-test-1';"
   ```
   `available` giảm đúng 1, `processed_events` có đúng 1 dòng.

2. **JSON hỏng → nằm trong DLT, consumer vẫn chạy tiếp.** Trong cùng console producer, gửi `901:{not json` rồi 1 event hợp lệ khác. Đọc DLT kèm header:
   ```bash
   kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 --topic order-events-dlt --from-beginning \
     --property print.key=true --property print.headers=true --timeout-ms 10000
   ```
   Thấy `{not json` kèm header `kafka_dlt-original-consumer-group`, `kafka_dlt-exception-message`. Event hợp lệ gửi sau vẫn được xử lý (log của inventory).

3. **2 order cùng SKU còn 1 cái, gửi đồng thời → 1 Reserved, 1 Rejected.** `MUG-WHITE` seed 5 cái: POST 1 order `"quantity":4` trước (còn 1), rồi gửi song song 2 order mỗi cái 1:
   ```bash
   for i in 1 2; do
     curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8090/api/orders/ \
       -H 'Content-Type: application/json' \
       -d '{"customerName":"Race-'$i'","totalAmount":5,"currency":"USD","productSku":"MUG-WHITE","quantity":1}' &
   done; wait
   ```
   `reservations` có 1 dòng mới RESERVED, `inventory-events` có 1 `InventoryRejected`, `available` = 0 (không âm).
   Nếu 2 order rơi vào 2 partition khác nhau (2 pod) → thấy `ObjectOptimisticLockingFailureException` + retry trong log; nếu cùng partition → xử lý tuần tự. Cả 2 trường hợp kết quả phải như trên.

4. **Luồng đầy đủ — 3 kịch bản.** Mỗi kịch bản POST 1 order rồi xem 3 nơi:
   ```bash
   # thành công
   curl -s -X POST http://localhost:8090/api/orders/ -H 'Content-Type: application/json' \
     -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":2}'
   # hết hàng
   curl -s -X POST http://localhost:8090/api/orders/ -H 'Content-Type: application/json' \
     -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"SOLD-OUT-001","quantity":1}'
   # thanh toán bị từ chối
   curl -s -X POST http://localhost:8090/api/orders/ -H 'Content-Type: application/json' \
     -d '{"customerName":"fail","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":2}'
   ```
   ```bash
   kubectl exec -n dev inventory-db-0 -- psql -U inventory_user -d inventorydb \
     -c "select * from reservations order by order_id;" -c "select sku, available, reserved from products;"
   kubectl exec -n dev payment-db-0 -- psql -U payment_user -d paymentdb \
     -c "select order_id, status, amount from payments where order_id > 8 order by order_id;"
   kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --include 'inventory-events|payment-events' --from-beginning --timeout-ms 10000 | grep -o '"eventType":"[A-Za-z]*"'
   ```

   | Kịch bản | `reservations` | `payments` | Event thấy được |
   |---|---|---|---|
   | Thành công | `COMMITTED` | `COMPLETED` | `InventoryReserved`, `PaymentCompleted` |
   | Hết hàng | (không có dòng) | (không có dòng) | `InventoryRejected` |
   | Thanh toán bị từ chối | `RELEASED`, kho `TEST-001` trả về như cũ | `FAILED` | `InventoryReserved`, `PaymentFailed`, `InventoryReleased` |

---

## Chủ Đề 4: notification-service

### Nội Dung Phải Học

- **Fan-out**: 1 event, nhiều consumer group khác nhau đều nhận (inventory và notification cùng nghe `order-events`)
- **Consumer group riêng** = bản sao độc lập của luồng event: notification đọc chậm hay chết cũng không làm inventory chậm theo, và ngược lại
- **Gửi thông báo**: giai đoạn này chỉ log + lưu bảng `notifications` + gửi mail thật vào **Mailpit** (SMTP giả, có web UI) — không cần SES
- **Template thông báo** theo `eventType`
- **Side effect ra ngoài không rollback được**: email đã gửi thì không "thu hồi" được như 1 dòng DB → chấp nhận hiếm khi gửi trùng, và giảm khả năng trùng bằng thứ tự ghi đúng (Bước 5)

### Cấu Trúc Tiêu Chuẩn

#### Bản đồ: event nào thành thông báo nào

```
order-events ──┬── group inventory-service    → trừ kho
               └── group notification-service → "Đơn ORD-xxx đã được tạo"
inventory-events ─ group notification-service → "Đơn ORD-xxx: sản phẩm đã hết hàng"   (chỉ InventoryRejected)
payment-events ─── group notification-service → "Thanh toán đơn ... thành công / thất bại"
```

| Event | Gửi thông báo? | Tiêu đề | Vì sao |
|---|---|---|---|
| `OrderCreated` | ✅ | `Đơn ORD-xxx đã được tạo` | Khách cần xác nhận đã nhận đơn |
| `InventoryReserved` | ❌ bỏ qua | — | Bước nội bộ, khách không cần biết |
| `InventoryRejected` | ✅ | `Đơn #<orderId>: sản phẩm <sku> đã hết hàng` | Đơn sẽ không thành công |
| `InventoryReleased` | ❌ bỏ qua | — | Đi kèm `PaymentFailed`, khách đã được báo qua event đó |
| `PaymentCompleted` | ✅ | `Thanh toán đơn #<orderId> thành công` | |
| `PaymentFailed` | ✅ | `Thanh toán đơn #<orderId> thất bại` | |

> Chỉ `OrderCreated` có `orderNumber`; các event khác chỉ có `orderId` → tiêu đề dùng `#<orderId>`. Muốn mọi email đều ghi `ORD-xxx` thì thêm `orderNumber` vào payload các event kia (schema evolution: thêm field là an toàn).

**Người nhận:** `Order` chỉ có `customerName`, chưa có email → tạm dựng địa chỉ giả `<customerName viết thường>@example.com`. Mailpit nhận mọi địa chỉ, nên vẫn thấy được thư.

#### Bước 1 — Dependency + cấu hình (`build.gradle`, `application.yaml`)

```groovy
implementation 'org.springframework.boot:spring-boot-starter-mail'
```

```yaml
spring:
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}          # SMTP của Mailpit, không cần user/password
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      group-id: notification-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer   # giống Chủ đề 2 — thiếu thì converter không nhận được String
```

`KafkaConfig` (converter + `DefaultErrorHandler` + DLT) giữ y như Chủ đề 3 Bước 2. notification **không phát event** → không cần `outbox`, `OutboxRelay`, `@EnableScheduling` hay phần `producer:`.

#### Bước 2 — Mailpit: local (`docker-compose.yml`) và trên kind (`k8s/notification-service/mailpit.yml`)

Local — thêm vào `notification-service/notification-service/docker-compose.yml`:

```yaml
  mailpit:
    image: axllent/mailpit
    container_name: notification-service-mailpit
    ports: ["8025:8025", "1025:1025"]   # UI: http://localhost:8025, SMTP: 1025
```

Trên kind — file mới `jaka_infrastructure/k8s/notification-service/mailpit.yml` (cùng folder với `notification-db.yml`):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: mailpit, namespace: dev }
spec:
  replicas: 1
  selector: { matchLabels: { app: mailpit } }
  template:
    metadata: { labels: { app: mailpit } }
    spec:
      containers:
        - name: mailpit
          image: axllent/mailpit
          ports:
            - { name: smtp, containerPort: 1025 }
            - { name: ui, containerPort: 8025 }
---
apiVersion: v1
kind: Service
metadata: { name: mailpit, namespace: dev }
spec:
  selector: { app: mailpit }
  ports:
    - { name: smtp, port: 1025, targetPort: smtp }
    - { name: ui, port: 8025, targetPort: ui }
```

Thư lưu trong RAM của pod → pod restart là mất thư. Đủ cho demo.

Thêm vào `values/notification-dev.yml`:

```yaml
env:
  # ... các dòng cũ ...
  MAIL_HOST: mailpit           # tên Service ở trên, cùng namespace dev
  MAIL_PORT: "1025"
```

#### Bước 3 — DTO: copy record của các event cần đọc (`dto/`)

Copy **đúng** field như service gửi đi (Bước 6 Chủ đề 3):

| Record | Copy từ |
|---|---|
| `OrderCreated` | order-service |
| `InventoryRejected` | inventory-service |
| `PaymentCompleted`, `PaymentFailed` | payment-service |

`InventoryReserved` và `InventoryReleased` không cần — notification bỏ qua 2 event đó.

#### Bước 4 — Template: `service/NotificationTemplates.java`

1 nơi duy nhất quyết định "event nào → tiêu đề + nội dung gì". Event không cần báo → trả `Optional.empty()`:

```java
@Component
@RequiredArgsConstructor
public class NotificationTemplates {
    private final ObjectMapper json;

    public record Message(Long orderId, String customerName, String subject, String body) {}

    public Optional<Message> render(EventEnvelope<JsonNode> event) {
        return switch (event.eventType()) {
            case "OrderCreated" -> {
                var e = json.treeToValue(event.payload(), OrderCreated.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Đơn " + e.orderNumber() + " đã được tạo",
                        "Chào " + e.customerName() + ", chúng tôi đã nhận đơn " + e.orderNumber()
                                + ": " + e.quantity() + " × " + e.productSku() + ", tổng " + e.totalAmount() + " " + e.currency() + "."));
            }
            case "InventoryRejected" -> {
                var e = json.treeToValue(event.payload(), InventoryRejected.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Đơn #" + e.orderId() + ": sản phẩm " + e.productSku() + " đã hết hàng",
                        "Chào " + e.customerName() + ", rất tiếc sản phẩm " + e.productSku() + " không đủ hàng. Đơn đã bị hủy."));
            }
            case "PaymentCompleted" -> {
                var e = json.treeToValue(event.payload(), PaymentCompleted.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Thanh toán đơn #" + e.orderId() + " thành công",
                        "Chào " + e.customerName() + ", đã thu " + e.amount() + " " + e.currency()
                                + " (mã " + e.paymentReference() + ")."));
            }
            case "PaymentFailed" -> {
                var e = json.treeToValue(event.payload(), PaymentFailed.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Thanh toán đơn #" + e.orderId() + " thất bại",
                        "Chào " + e.customerName() + ", thanh toán " + e.amount() + " " + e.currency()
                                + " bị từ chối (" + e.reason() + "). Hàng đã được trả lại kho."));
            }
            default -> Optional.empty();          // InventoryReserved, InventoryReleased, event lạ → không báo
        };
    }
}
```

Body hơn 500 ký tự sẽ vượt cột `message` (`length = 500`) → giữ nội dung ngắn, hoặc tăng `length`.

#### Bước 5 — Gửi + lưu lịch sử: `service/NotificationService.java`

```java
@Transactional
public void handle(EventEnvelope<JsonNode> event) {
    if (notificationRepository.existsByEventId(event.eventId())) {
        return;                                            // idempotent: đã báo cho event này rồi
    }
    Optional<NotificationTemplates.Message> msg = templates.render(event);
    if (msg.isEmpty()) {
        return;                                            // event không cần báo → không lưu gì
    }
    var m = msg.get();
    String recipient = m.customerName().toLowerCase().replaceAll("\\s+", ".") + "@example.com";

    // (1) ghi DB TRƯỚC: eventId unique → bản trùng chạy song song sẽ bị DB chặn ở đây
    notificationRepository.save(Notification.builder()
            .eventId(event.eventId()).eventType(event.eventType()).orderId(m.orderId())
            .channel(NotificationChannel.EMAIL).recipient(recipient).message(m.subject())
            .status(NotificationStatus.SENT).sentAt(LocalDateTime.now())
            .build());
    notificationRepository.flush();                        // đẩy INSERT xuống DB ngay để lỗi trùng nổ ra TRƯỚC khi gửi mail

    // (2) gửi mail SAU: lỗi SMTP → exception → transaction rollback dòng (1) → retry → gửi lại
    var mail = new SimpleMailMessage();
    mail.setFrom("shop@jaka.local");
    mail.setTo(recipient);
    mail.setSubject(m.subject());
    mail.setText(m.body());
    mailSender.send(mail);
    log.info("Sent {} for order {} to {}", event.eventType(), m.orderId(), recipient);
}
```

```java
// repository/NotificationRepository.java — thêm
boolean existsByEventId(String eventId);
```

Vì sao **ghi DB trước, gửi mail sau** (không thể làm cả 2 atomic vì mail không nằm trong transaction):

| Thứ tự | Lỗi ở giữa | Hậu quả |
|---|---|---|
| Gửi mail → ghi DB | Mail đi rồi, ghi DB lỗi → rollback → retry | **Gửi trùng** mail mỗi lần retry |
| **Ghi DB → gửi mail** (chọn cách này) | SMTP lỗi → rollback dòng DB → retry | Không trùng, chỉ gửi lại khi thật sự chưa gửi được |
| | Mail đi rồi, **commit** lỗi (rất hiếm) | Trùng 1 lần — chấp nhận được với email |

Mailpit/SMTP chết lâu hơn ~10s backoff → hết retry → event vào DLT (`order-events-dlt`, ...), kèm header `kafka_dlt-original-consumer-group: notification-service` → biết là thông báo nào chưa gửi được.

Không dùng `processed_events` ở service này: cột `notifications.eventId` (`unique = true`) đã làm đúng việc đó. Event không cần báo (`InventoryReserved`, `InventoryReleased`) vẫn đi qua `handle`: `render` rơi vào nhánh `default` → trả `Optional.empty()` → `handle` thoát, **không lưu dòng nào**. Nên nếu nhận lại event đó lần 2, bước kiểm tra `existsByEventId` không nhận ra — nhưng không sao: `render` lại trả rỗng, lại thoát, vẫn không làm gì. Chỉ event **có gửi mail** mới cần được ghi lại để chống gửi trùng.

#### Bước 6 — Listener: `consumer/EventConsumer.java`

1 listener nghe **cả 3 topic**, mọi việc giao cho `NotificationService`:

```java
@Component
@RequiredArgsConstructor
public class EventConsumer {
    private final NotificationService notifications;

    @KafkaListener(topics = {"order-events", "inventory-events", "payment-events"}, groupId = "notification-service")
    public void on(EventEnvelope<JsonNode> event) {
        notifications.handle(event);
    }
}
```

- `@Transactional` đặt ở `handle` (Bước 5), không cần đặt lặp ở listener.
- `order-events` giờ cũng dùng `EventEnvelope<JsonNode>` (khác inventory dùng `EventEnvelope<OrderCreated>`) vì listener này nhận 6 loại event từ 3 topic.

#### Bước 7 — Deploy lên kind

```bash
R=~/projects/springboot_aws_compute_databases
kubectl apply -f $R/jaka_infrastructure/k8s/notification-service/mailpit.yml
kubectl rollout status deploy/mailpit -n dev
```

⚠️ **Trước khi notification mới chạy lần đầu**: group `notification-service` chưa có offset → `earliest` → đọc lại **mọi** message cũ trên 3 topic: message phẳng tuần 8 (payload null → lỗi → DLT sau ~10s mỗi cái) và cả các event test → khách nhận email cho đơn cũ. Đặt offset về cuối trước:

```bash
for t in order-events inventory-events payment-events; do
  kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group notification-service --topic $t --reset-offsets --to-latest --execute
done
```

```bash
cd $R/notification-service/notification-service
docker build -t notification-service:v3 .
kind load docker-image notification-service:v3 --name jaka
cd $R/jaka_infrastructure/charts/spring-service
helm upgrade notification-service . -n dev -f values/notification-dev.yml --set image.tag=v3
kubectl rollout status deploy/notification-service -n dev
```

**Output cụ thể:**
- notification-service nghe 3 topic, lưu lịch sử, gửi mail qua Mailpit
- Chứng minh:

1. **Tạo order → email "đã được tạo" + "thanh toán thành công" trong Mailpit UI.**
   ```bash
   kubectl port-forward -n dev svc/mailpit 8025:8025          # mở http://localhost:8025
   curl -s -X POST http://localhost:8090/api/orders/ -H 'Content-Type: application/json' \
     -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":1}'
   ```
   Hộp thư `tai@example.com` có 2 thư: `Đơn ORD-... đã được tạo`, `Thanh toán đơn #... thành công`.

2. **Hết hàng và thanh toán lỗi đều có email riêng:** POST với `"productSku":"SOLD-OUT-001"` → thư "đã hết hàng"; POST với `"customerName":"fail"` → thư "thất bại" tới `fail@example.com`.

3. **Lịch sử khớp với thư:**
   ```bash
   kubectl exec -n dev notification-db-0 -- psql -U notification_user -d notificationdb \
     -c "select order_id, event_type, recipient, message, status from notifications order by id;"
   ```
   Không có dòng cho `InventoryReserved` / `InventoryReleased`.

4. **Fan-out độc lập:** `kubectl scale deploy/notification-service -n dev --replicas=0` → POST 1 order → kho vẫn trừ, payment vẫn thu (inventory/payment không chờ notification). Scale lên lại 2 → thư của order đó mới xuất hiện trong Mailpit (notification đọc tiếp từ offset của group nó).

---

## Chủ Đề 5: System Design — Phần 2 (Event-Driven & CQRS)
 
> Chủ đề này gồm 2 phần: **lý thuyết** (Phần A — để viết `docs/system-design-2.md` và trả lời phỏng vấn) và **thực hành** (Phần B: Correlation ID, Phần C: CQRS read model). Phần D là 3 câu hỏi tự trả lời, có gợi ý đáp án.

### Quan hệ với Tuần 10 và Tháng 4 — đọc trước khi làm

| Phần | Chồng với | Nên làm thế nào |
|---|---|---|
| A, D (lý thuyết) | — | Làm đủ |
| B. Correlation ID | **Tháng 4 — Tracing** (Micrometer Tracing / OpenTelemetry tự truyền `traceId` qua HTTP + Kafka header) | Làm đến **B6** (in cid vào log) rồi dừng. Phần B là bản *làm tay* của tracing — hiểu cơ chế ở đây, Tháng 4 thay bằng công cụ tự động (xem ghi chú cuối B.7) |
| C. CQRS `order_summary` | **Tuần 10 Chủ đề 1 — Saga**: cũng bắt order-service consume `inventory-events` + `payment-events` | Làm đủ. **Hạ tầng consume** của C.7 (deserializer, converter, `KafkaErrorConfig`, interceptor) làm 1 lần ở đây → Tuần 10 **dùng lại**, không làm lại |

Phần C và Saga **khác nhau ở nghiệp vụ**, dù đọc cùng topic:

| | Phần C (tuần 9) | Saga (tuần 10) |
|---|---|---|
| Listener / group | `OrderSummaryProjector` / `order-summary-projector` | listener mới / `order-service` |
| Ghi vào | `order_summary` — **read model**, chỉ để đọc | `orders.status` — **write model**, nguồn sự thật |
| Phát event | Không | Có (`OrderConfirmed`, `OrderCancelled`) |
| Chống trùng | `projected_events` | `processed_events` (bảng riêng) |

2 group độc lập cùng đọc 1 topic = fan-out, như inventory và notification cùng đọc `order-events`. Sau tuần 10, trạng thái trong `order_summary` sẽ **lặp một phần** `orders.status` — không sai: read model được phép chép dữ liệu cho tiện đọc; nguồn sự thật vẫn là `orders`.
 
### Bản đồ Chủ đề 5
 
| Phần | Làm gì | Service bị sửa | Output |
|---|---|---|---|
| A. 3 kiểu event | Hiểu hệ thống tuần 9 đang dùng kiểu nào, vì sao | — | Bảng so sánh trong `docs/system-design-2.md` |
| B. Correlation ID | 1 mã đi theo đơn hàng qua HTTP → outbox → Kafka header → log của 4 service | cả 4 | `grep` 1 mã thấy log ở cả 4 service |
| C. CQRS | Bảng `order_summary` trong orderdb, cập nhật từ event | order-service | `GET /api/orders/{id}/summary` |
| D. Tự trả lời | 3 câu hỏi system design | — | Phần cuối `docs/system-design-2.md` |
 
---
 
### Phần A — Event-Driven Architecture: 3 kiểu event
 
#### A.1. Ba kiểu, nhìn bằng cùng 1 ví dụ "đơn #12 được tạo"
 
**(1) Event Notification — event "mỏng", chỉ báo "có chuyện xảy ra"**
 
```json
{ "eventType": "OrderCreated", "aggregateId": "12", "payload": { "orderId": 12 } }
```
 
```
order-svc ──OrderCreated{orderId:12}──► Kafka ──► inventory-svc
                                                     │ "đơn 12 là SKU nào, mấy cái?"
                                                     └── GET /api/orders/12 ──► order-svc   ← gọi ngược lại
```
 
- Consumer phải **gọi REST ngược** để lấy dữ liệu → order-service chết thì inventory cũng kẹt → mất tính tách rời (loose coupling) mà event-driven hứa hẹn.
- Hợp khi: dữ liệu lớn/nhạy cảm, không muốn rải ra Kafka (ví dụ "file đã upload", consumer tự lấy từ S3).
**(2) Event-Carried State Transfer (ECST) — event "đầy đủ", mang theo dữ liệu cần dùng** ← **hệ thống tuần 9 đang dùng kiểu này**
 
```json
{ "eventType": "OrderCreated", "aggregateId": "12",
  "payload": { "orderId": 12, "orderNumber": "ORD-12", "customerName": "Tai",
               "totalAmount": 20, "currency": "USD", "productSku": "TEST-001", "quantity": 2 } }
```
 
- Consumer có đủ dữ liệu, **không gọi ngược ai** → order-service chết, inventory vẫn xử lý được event đã có trong Kafka.
- Đây chính là lý do ở Bước 6 Chủ đề 3, `InventoryReserved` phải chép `totalAmount`, `customerName` từ `OrderCreated`.
- Giá phải trả: event to hơn; dữ liệu bị **chép ra nhiều nơi** (bản chép có thể cũ); đổi schema phải cẩn thận (chỉ thêm field).
**(3) Event Sourcing — chuỗi event CHÍNH LÀ dữ liệu gốc**
 
Không lưu dòng `orders(status = 'CONFIRMED')`. Chỉ lưu các event theo thứ tự, trạng thái hiện tại = "cộng dồn" (replay) toàn bộ event:
 
```
event_store (aggregate_id = 12)
seq │ event_type          │ data
────┼─────────────────────┼──────────────────────────
 1  │ OrderCreated        │ {sku: TEST-001, qty: 2}
 2  │ InventoryReserved   │ {}
 3  │ PaymentCompleted    │ {ref: PAY-123}
 
replay(1→3)  ⇒  Order{id:12, status: CONFIRMED, paymentRef: PAY-123}
```
 
- Ưu: có **toàn bộ lịch sử** (audit), xem lại trạng thái ở bất kỳ thời điểm nào.
- Nhược: khó nhất trong 3 kiểu — query trạng thái hiện tại phải replay (nên gần như **bắt buộc đi kèm CQRS**), đổi schema event cũ rất đau, team phải quen cách nghĩ mới.
> ⚠️ **Đừng nhầm Outbox với Event Sourcing.** Bảng `outbox` của tuần 9 chỉ là **chỗ chờ gửi tạm** — gửi xong là hết vai trò, có thể xóa. Dữ liệu gốc vẫn là bảng `orders`. Trong Event Sourcing, bảng event **là** dữ liệu gốc, không bao giờ xóa.
 
#### A.2. Bảng so sánh (đưa vào `docs/system-design-2.md`)
 
| | Event Notification | Event-Carried State Transfer | Event Sourcing |
|---|---|---|---|
| Payload | Chỉ ID | Đủ dữ liệu consumer cần | Dữ liệu thay đổi của từng bước |
| Consumer gọi ngược REST? | Có | Không | Không |
| Phụ thuộc lúc chạy | Cao | Thấp | Thấp |
| Dữ liệu gốc nằm ở | Bảng của producer | Bảng của producer | **Chuỗi event** |
| Độ khó | Dễ | Vừa | Khó |
| Dùng khi | Dữ liệu lớn/nhạy cảm | Mặc định cho microservices | Cần audit đầy đủ (ngân hàng, kế toán) |
| Trong hệ thống này | — | ✅ cả 6 event tuần 9 | — (chỉ biết khái niệm) |
 
#### A.3. Ưu / nhược của event-driven — ví dụ thật trong hệ thống
 
| | Ý nghĩa | Thấy ở đâu trong tuần 9 |
|---|---|---|
| ✅ Tách rời (loose coupling) | Producer không biết ai nghe | Thêm notification-service ở Chủ đề 4 mà **không sửa 1 dòng** order-service |
| ✅ Scale độc lập | Service chậm thì tăng pod riêng | inventory chậm → tăng replica inventory, order không bị ảnh hưởng |
| ✅ Chịu lỗi | Consumer chết, event chờ trong Kafka | Tắt payment-service → order vẫn tạo được; bật lại → xử lý tiếp từ offset cũ |
| ❌ Khó debug | 1 đơn đi qua 4 service, 3 topic, log rải rác | Đơn kẹt `PROCESSING` — kẹt ở service nào? → **Phần B: Correlation ID** |
| ❌ Eventual consistency | Dữ liệu các service khớp nhau **sau một lúc**, không ngay lập tức | Vừa POST xong, `payments` chưa có dòng → **Phần C + câu hỏi 3 Phần D** |
| ❌ Thứ tự chỉ đảm bảo trong 1 partition | Khác topic thì không có thứ tự | `PaymentCompleted` có thể được đọc trước `InventoryReserved` → **Phần C, Bước C5** |
 
---
 
### Phần B — Correlation ID: theo dấu 1 đơn hàng qua 4 service
 
#### B.1. 3 loại ID — dễ nhầm
 
| ID | Ý nghĩa | Ví dụ đơn #12 | Thay đổi khi nào |
|---|---|---|---|
| `eventId` | Định danh **1 event** — dùng chống trùng | mỗi event 1 UUID khác nhau | Mỗi event mới |
| `aggregateId` | Event nói về **đối tượng** nào — dùng làm Kafka key | `"12"` | Không đổi |
| `correlationId` | **1 yêu cầu gốc** của người dùng — dùng để trace | `"demo-cid-001"` cho cả 5 event của đơn #12 | Không đổi trong cả chuỗi |
 
> Vì sao không dùng luôn `orderId` để trace? Vì 1 request có thể không liên quan đơn nào (ví dụ: nhập kho), hoặc 1 request tạo nhiều đơn. `correlationId` là khái niệm chung, và tháng 4 sẽ được thay bằng `traceId` của OpenTelemetry — cùng ý tưởng.
 
#### B.2. Đường đi của correlationId
 
```
curl -H "X-Correlation-Id: demo-cid-001"
  │
  ▼
order-svc  CorrelationIdFilter ──► MDC["correlationId"]="demo-cid-001"   (log của request này đều có cid)
  │          OutboxWriter đọc MDC ──► outbox.correlation_id = "demo-cid-001"
  │          OutboxRelay ──► Kafka header  correlationId: demo-cid-001
  ▼
order-events ──► inventory-svc  CorrelationIdInterceptor: header ──► MDC
                    │  OutboxWriter đọc MDC ──► outbox.correlation_id ──► header
                    ▼
                 inventory-events ──► payment-svc ──► ... (lặp lại y hệt)
                                  ──► notification-svc
```
 
**MDC (Mapped Diagnostic Context)** = 1 cái `Map` gắn với **thread hiện tại**, logger (Logback) đọc ra để in vào mỗi dòng log. Đặt 1 lần đầu request → mọi `log.info` sau đó trên cùng thread đều tự có cid.
 
#### B.3. Bước B1 — Hằng số: `common/Correlation.java` (copy vào cả 4 service)
 
```java
package com.jaka.order_service.common;          // đổi theo package của từng service
 
public final class Correlation {
    public static final String HTTP_HEADER  = "X-Correlation-Id";
    public static final String KAFKA_HEADER = "correlationId";
    public static final String MDC_KEY      = "correlationId";
    private Correlation() {}
}
```
 
#### B.4. Bước B2 — Nhận / tạo correlationId ở cửa HTTP: `web/CorrelationIdFilter.java` (order-service)
 
```java
package com.jaka.order_service.web;
 
import com.jaka.order_service.common.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
 
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    // Chỉ nhận chữ, số, gạch ngang, tối đa 64 ký tự — chặn người ngoài chèn ký tự xuống dòng làm giả log
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{1,64}");
 
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(Correlation.HTTP_HEADER);
        String cid = (incoming != null && SAFE.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(Correlation.MDC_KEY, cid);
        res.setHeader(Correlation.HTTP_HEADER, cid);      // trả lại cho client để họ báo lỗi kèm mã này
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(Correlation.MDC_KEY);               // BẮT BUỘC: thread của Tomcat được tái dùng cho request khác
        }
    }
}
```
 
- Không gắn `@Order(...)`: trong order-service đã có entity tên `Order` → import `org.springframework.core.annotation.Order` sẽ trùng tên, dễ rối. Thứ tự filter không quan trọng ở đây.
- Thiếu `finally { MDC.remove }` → request sau dùng lại thread sẽ **mang cid của request trước** → log sai, rất khó phát hiện.
#### B.5. Bước B3 — Lưu correlationId vào outbox (order, inventory, payment)
 
`OutboxEvent` thêm 1 cột — **nullable** (nhớ bẫy `ddl-auto` ở Chủ đề 1: cột `nullable = false` mới trên bảng đã có dữ liệu → app không khởi động):
 
```java
@Column(length = 64)
String correlationId;            // KHÔNG nullable = false — các dòng outbox cũ không có giá trị
```
 
Sửa `OutboxEvent.of(...)` thêm tham số `correlationId`, rồi sửa `OutboxWriter` (Bước 7 Chủ đề 3):
 
```java
public void write(String eventType, Long orderId, Object payload) {
    var event = new EventEnvelope<>(UUID.randomUUID().toString(), eventType, 1,
            orderId.toString(), Instant.now(), payload);
    String cid = MDC.get(Correlation.MDC_KEY);
    if (cid == null) {
        cid = UUID.randomUUID().toString();     // không có request gốc (job chạy nền...) → bắt đầu chuỗi mới
    }
    outbox.save(OutboxEvent.of(event.eventId(), eventType, orderId.toString(),
            json.writeValueAsString(event), cid));
}
```
 
#### B.6. Bước B4 — Relay gửi correlationId thành Kafka header (order, inventory, payment)
 
```java
@Scheduled(fixedDelay = 1000)
@Transactional
public void relay() {
    for (OutboxEvent e : outbox.lockUnpublishedBatch(100)) {
        var record = new ProducerRecord<>("order-events", e.getAggregateId(), e.getPayload());  // đổi topic theo service
        if (e.getCorrelationId() != null) {
            record.headers().add(Correlation.KAFKA_HEADER, e.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add("eventType", e.getEventType().getBytes(StandardCharsets.UTF_8)); // xem nhanh bằng console, không cần mở JSON
        String previous = MDC.get(Correlation.MDC_KEY);    // giá trị đang có trên thread (có thể null)
        try {
            if (e.getCorrelationId() != null) {
                MDC.put(Correlation.MDC_KEY, e.getCorrelationId());
            }
            kafka.send(record).join();
            log.info("Published {} {} for aggregate {}", e.getEventType(), e.getEventId(), e.getAggregateId());
        } finally {
            if (previous != null) {
                MDC.put(Correlation.MDC_KEY, previous);     // trả lại đúng như trước
            } else {
                MDC.remove(Correlation.MDC_KEY);
            }
        }
        e.setPublishedAt(Instant.now());
    }
}
```
 
Import: `org.apache.kafka.clients.producer.ProducerRecord`, `java.nio.charset.StandardCharsets`.
 
> ⚠️ **Không `put` rồi `remove` thẳng tay.** `MDC.put` ghi đè giá trị đang có trên thread, `MDC.remove` xóa luôn thay vì trả lại → làm mất context của code bên ngoài (logger, tracing). Luôn lưu `previous` rồi trả lại trong `finally`. Cũng không `put` giá trị `null`.
 
> **Vì sao header mà không thêm field vào `EventEnvelope`?** Cả 2 đều được. Header: không đụng schema event, Spring Kafka tự chép header sang DLT, và OpenTelemetry (tháng 4) cũng truyền trace qua header. Field trong envelope: dễ thấy hơn khi đọc JSON, và vẫn còn nếu ai đó chép payload sang hệ thống khác không giữ header. Tuần này chọn header.
 
#### B.7. Bước B5 — Đọc header ở phía consume: `kafka/CorrelationIdInterceptor.java` (inventory, payment, notification; order ở Phần C)
 
```java
package com.jaka.inventory_service.kafka;
 
import com.jaka.inventory_service.common.Correlation;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;
 
@Component
public class CorrelationIdInterceptor implements RecordInterceptor<Object, Object> {
 
    // intercept và afterRecord là 2 method tách nhau, chạy trên cùng 1 thread
    // → giữ giá trị cũ của MDC trong ThreadLocal để afterRecord trả lại được
    private static final ThreadLocal<String> PREVIOUS = new ThreadLocal<>();
 
    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        PREVIOUS.set(MDC.get(Correlation.MDC_KEY));
        Header h = record.headers().lastHeader(Correlation.KAFKA_HEADER);
        if (h != null) {
            MDC.put(Correlation.MDC_KEY, new String(h.value(), StandardCharsets.UTF_8));
        }
        return record;                                  // trả null = bỏ qua message — KHÔNG làm vậy
    }
 
    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        String previous = PREVIOUS.get();               // chạy sau mỗi message, kể cả khi lỗi
        PREVIOUS.remove();
        if (previous != null) {
            MDC.put(Correlation.MDC_KEY, previous);     // trả lại đúng như trước
        } else {
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
```
 
- **1 bean dùng cho mọi `@KafkaListener`**: Spring Boot thấy bean kiểu `RecordInterceptor<Object, Object>` → tự gắn vào listener container (giống cách tự gắn `DefaultErrorHandler` ở Chủ đề 3). Không cần sửa listener nào.
- `intercept` chạy **trên cùng thread** với listener → bên trong listener, `OutboxWriter` đọc được cid từ MDC → event tiếp theo mang đúng cid. Chuỗi không bị đứt.
- Khi retry (DefaultErrorHandler), message được đọc lại → `intercept` chạy lại → MDC vẫn đúng.
- DLT: `DeadLetterPublishingRecoverer` chép header gốc sang → message trong `*-dlt` vẫn có `correlationId` → biết message lỗi thuộc request nào.
- **Tháng 4 (tracing):** Micrometer Tracing dùng key `traceId`/`spanId` nên hiện không trùng. Nhưng nếu đưa `correlationId` vào baggage và cho vào MDC (`management.tracing.baggage.correlation.fields`), tracing sẽ tự quản lý key đó → **bỏ hết** code `MDC.put/remove` tự viết (filter, relay, interceptor), nếu không 2 bên sẽ ghi đè nhau.
#### B.8. Bước B6 — In cid vào mỗi dòng log: `application.yaml` (cả 4 service)
 
```yaml
logging:
  pattern:
    level: "%5p [cid=%X{correlationId:-}]"
```
 
`%X{correlationId:-}` = lấy `correlationId` trong MDC, không có thì để trống. Thêm `log.info(...)` ở các chỗ nghiệp vụ chính nếu chưa có: `OrderService.create`, `InventoryService.reserve/commit/release`, `PaymentService.charge`, chỗ gửi mail của notification.
 
#### B.9. Chứng minh Phần B
 
**1. Gửi request có sẵn cid:**
 
```bash
curl -si -X POST http://localhost:8090/api/orders/ \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-cid-001' \
  -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":2}' | head -n 5
```
 
✅ Thành công:
```
HTTP/1.1 201 Created
X-Correlation-Id: demo-cid-001
Content-Type: application/json
...
```
 
❌ Không thấy dòng `X-Correlation-Id` → filter chưa được scan (sai package, nằm ngoài package của class `@SpringBootApplication`).
 
**2. Outbox đã lưu cid:**
 
```bash
kubectl exec -n dev order-db-0 -- psql -U order_user -d orderdb \
  -c "select event_type, correlation_id, published_at is not null as sent from outbox order by id desc limit 3;"
```
✅
```
  event_type  | correlation_id | sent
--------------+----------------+------
 OrderCreated | demo-cid-001   | t
```
❌ `correlation_id` trống → `OutboxWriter` chưa đọc MDC, hoặc chưa build lại image sau khi sửa code.
 
> Tên pod DB, user, database: đổi theo cái bạn đặt ở tuần 7 (`kubectl get pods -n dev`).
 
**3. Header có trong Kafka:**
 
```bash
kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --include 'order-events|inventory-events|payment-events' \
  --from-beginning --property print.headers=true --timeout-ms 10000 | grep demo-cid-001 | cut -c1-80
```
✅ 3 dòng (đơn thành công: OrderCreated, InventoryReserved, PaymentCompleted):
```
correlationId:demo-cid-001,eventType:OrderCreated	{"eventId":"5b1c...
correlationId:demo-cid-001,eventType:InventoryReserved	{"eventId":"a9f2...
correlationId:demo-cid-001,eventType:PaymentCompleted	{"eventId":"0c7e...
```
❌ Chỉ thấy `OrderCreated` → inventory chưa có `CorrelationIdInterceptor` (MDC trống → `OutboxWriter` tạo cid mới → chuỗi bị đứt). Kiểm tra: tìm event `InventoryReserved` mới nhất, header của nó sẽ là 1 UUID lạ.
 
**4. Log của cả 4 service:**
 
```bash
kubectl get deploy -n dev          # xem đúng tên Deployment
for app in order-service inventory-service payment-service notification-service; do
  echo "===== $app"
  kubectl logs -n dev deploy/$app --since=10m | grep 'cid=demo-cid-001'
done
```
✅ Mỗi service có ít nhất 1 dòng, dạng:
```
===== order-service
2026-11-04T10:15:02.123+07:00  INFO [cid=demo-cid-001] 1 --- [order-service] [nio-8081-exec-3] c.j.o.service.OrderService : Created order 12
2026-11-04T10:15:03.010+07:00  INFO [cid=demo-cid-001] 1 --- [order-service] [   scheduling-1] c.j.o.outbox.OutboxRelay   : Published OrderCreated 5b1c... for aggregate 12
===== inventory-service
2026-11-04T10:15:03.120+07:00  INFO [cid=demo-cid-001] 1 --- [inventory-service] [ntainer#0-0-C-1] c.j.i.service.InventoryService : Reserved 2 x TEST-001 for order 12
...
```
❌ Service nào trống → xem lại B5 (interceptor) và B6 (log pattern) của service đó. Nếu log có `[cid=]` (rỗng) → interceptor chưa được gắn; gắn tay: lấy bean `ConcurrentKafkaListenerContainerFactory` và gọi `factory.setRecordInterceptor(...)`.
 
**5. Request không gửi cid** → service tự tạo UUID, response trả về header `X-Correlation-Id: <uuid>` → dùng uuid đó `grep` như bước 4.
 
---
 
### Phần C — CQRS: read model `order_summary`
 
#### C.1. Vấn đề cần giải
 
Màn hình "Chi tiết đơn hàng" cần: thông tin đơn + kho đã giữ chưa + thanh toán xong chưa. 3 thứ nằm ở **3 database khác nhau** (database-per-service). 3 cách:
 
```
Cách 1 — API Composition (gọi REST lúc đọc)
  GET /orders/12/summary ──► order-svc ──┬─► GET inventory-svc/reservations/12
                                         └─► GET payment-svc/payments?orderId=12
  ❌ 1 service chết → màn hình lỗi   ❌ chậm = service chậm nhất   ❌ tải đọc dồn sang service khác
 
Cách 2 — JOIN thẳng 3 database
  ❌ Phá database-per-service. Không bao giờ làm.
 
Cách 3 — CQRS: read model riêng, cập nhật từ event  ← làm cách này
  inventory-events ─┐
  payment-events  ──┴─► projector (trong order-svc) ──► bảng order_summary (orderdb)
  GET /orders/12/summary ──► SELECT * FROM order_summary WHERE order_id = 12
  ✅ 1 câu SELECT, không gọi ai   ✅ inventory/payment chết vẫn đọc được   ❌ dữ liệu có thể trễ vài giây
```
 
**CQRS = tách phía GHI (command) và phía ĐỌC (query) thành 2 mô hình dữ liệu khác nhau:**
 
| | Write model | Read model |
|---|---|---|
| Bảng | `orders` | `order_summary` |
| Thiết kế cho | Đúng nghiệp vụ, chuẩn hóa | Đọc nhanh cho 1 màn hình, **phi chuẩn hóa** (chép dữ liệu từ nhiều nơi) |
| Ai ghi | `OrderService.create` (API) | `OrderSummaryProjector` (từ event) + 1 dòng lúc tạo đơn |
| Sai/mất thì sao | Mất dữ liệu thật | **Xóa đi, dựng lại** từ event (Bước C9) |
 
#### C.2. Bước C1 — Bảng read model: `entity/OrderSummary.java` (order-service)
 
```java
@Entity
@Table(name = "order_summary")
@Getter
@NoArgsConstructor
public class OrderSummary {
    @Id
    private Long orderId;                     // = orders.id, không tự sinh
    @Version
    private Long version;                     // 2 topic có thể cập nhật cùng 1 dòng song song (xem C5)
 
    private String orderNumber;
    private String customerName;
    private String productSku;
    private Integer quantity;
    private BigDecimal totalAmount;
    private String currency;
 
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false)
    private SummaryInventoryStatus inventoryStatus;   // PENDING, RESERVED, REJECTED, RELEASED
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false)
    private SummaryPaymentStatus paymentStatus;       // PENDING, COMPLETED, FAILED, SKIPPED
 
    private String paymentReference;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
 
    public static OrderSummary from(Order o) {
        var s = new OrderSummary();
        s.orderId = o.getId();
        s.orderNumber = o.getOrderNumber();
        s.customerName = o.getCustomerName();
        s.productSku = o.getProductSku();
        s.quantity = o.getQuantity();
        s.totalAmount = o.getTotalAmount();
        s.currency = o.getCurrency();
        s.inventoryStatus = SummaryInventoryStatus.PENDING;
        s.paymentStatus = SummaryPaymentStatus.PENDING;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }
 
    // ---- Chỉ cho đi TIẾN, không cho lùi (giải thích ở C5) ----
    public void onInventoryReserved() {
        if (inventoryStatus == SummaryInventoryStatus.PENDING) {
            inventoryStatus = SummaryInventoryStatus.RESERVED;
            touch();
        }
    }
    public void onInventoryRejected(String reason) {
        if (inventoryStatus == SummaryInventoryStatus.PENDING) {
            inventoryStatus = SummaryInventoryStatus.REJECTED;
            paymentStatus = SummaryPaymentStatus.SKIPPED;     // không giữ được hàng → không bao giờ thu tiền
            failureReason = reason;
            touch();
        }
    }
    public void onInventoryReleased(String reason) {
        if (inventoryStatus != SummaryInventoryStatus.RELEASED) {
            inventoryStatus = SummaryInventoryStatus.RELEASED;
            touch();
        }
    }
    public void onPaymentCompleted(String paymentRef) {
        if (paymentStatus == SummaryPaymentStatus.PENDING) {
            paymentStatus = SummaryPaymentStatus.COMPLETED;
            paymentReference = paymentRef;
            touch();
        }
    }
    public void onPaymentFailed(String reason) {
        if (paymentStatus == SummaryPaymentStatus.PENDING) {
            paymentStatus = SummaryPaymentStatus.FAILED;
            failureReason = reason;
            touch();
        }
    }
    private void touch() { updatedAt = Instant.now(); }
}
```
 
```java
public enum SummaryInventoryStatus { PENDING, RESERVED, REJECTED, RELEASED }
public enum SummaryPaymentStatus   { PENDING, COMPLETED, FAILED, SKIPPED }
 
public interface OrderSummaryRepository extends JpaRepository<OrderSummary, Long> {}
```
 
- Tên enum có tiền tố `Summary` để không trùng với `OrderStatus` đã có.
- Không có `COMMITTED` cho kho: inventory commit (Bước 9 Chủ đề 3) **không phát event** → order-service không thể biết. Read model chỉ biết những gì **có event**. Đây là quy tắc quan trọng của CQRS.
- ⚠️ Bẫy enum của Chủ đề 1 áp dụng ở đây luôn: sau này thêm giá trị mới vào 2 enum này → phải `ALTER TABLE order_summary DROP CONSTRAINT ...` bằng tay.
#### C.3. Bước C2 — Tạo dòng summary CÙNG transaction với đơn: `OrderService.create`
 
```java
@Transactional
@CacheEvict(value = "orders", allEntries = true)
public Order create(CreateOrderRequest req) {
    Order order = orders.save(Order.pending(req));
    summaries.save(OrderSummary.from(order));             // ← thêm dòng này
    outboxWriter.write("OrderCreated", order.getId(), OrderCreated.from(order));
    log.info("Created order {}", order.getId());
    return order;
}
```
 
> **Trade-off — vì sao không dựng summary từ chính event `OrderCreated`?**
>
> | | Ghi summary cùng transaction (chọn) | Dựng từ `OrderCreated` (CQRS "thuần") |
> |---|---|---|
> | POST xong GET ngay | ✅ Có dòng (`PROCESSING`) | ❌ Có thể 404 trong ~1s đầu |
> | Event đến mà chưa có dòng | Không xảy ra | Phải xử lý |
> | Tách bạch ghi / đọc | Kém hơn 1 chút | Tách hoàn toàn |
>
> Người dùng vừa tạo đơn mà bấm xem lại thấy "Không tìm thấy" là trải nghiệm tệ. Đây gọi là **read-your-own-writes** (tự đọc được cái mình vừa ghi). Chọn cách đơn giản hơn và UX tốt hơn.
 
#### C.4. Bước C3 — Bảng chống trùng riêng cho projector: `entity/ProjectedEvent.java`
 
```java
@Entity
@Table(name = "projected_events")
@Getter @NoArgsConstructor @AllArgsConstructor
public class ProjectedEvent {
    @Id @Column(length = 36)
    private String eventId;
    @Column(nullable = false)
    private Instant projectedAt;
}
 
public interface ProjectedEventRepository extends JpaRepository<ProjectedEvent, String> {}
```
 
> **Vì sao KHÔNG dùng chung bảng `processed_events`?** Tuần 10, order-service có thêm listener của Saga (group `order-service`) cũng đọc `inventory-events`. Cùng 1 `eventId` phải được **cả 2 listener** xử lý. Dùng chung bảng → listener nào chạy trước ghi `eventId` → listener kia thấy "đã xử lý" → **bỏ qua nhầm**. Cách khác: 1 bảng với khóa chính 2 cột `(event_id, consumer_group)`.
 
#### C.5. Bước C4 — Projector: `consumer/OrderSummaryProjector.java`
 
Copy vào `dto/` của order-service các record cần đọc: `InventoryRejected`, `InventoryReleased`, `PaymentCompleted`, `PaymentFailed` (Bước 6 Chủ đề 3). `InventoryReserved` không cần đọc payload.
 
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSummaryProjector {
    private final OrderSummaryRepository summaries;
    private final ProjectedEventRepository projected;
    private final ObjectMapper json;                      // tools.jackson.databind.ObjectMapper
 
    @KafkaListener(topics = {"inventory-events", "payment-events"},
                   groupId = "order-summary-projector",
                   autoStartup = "${app.projector.enabled:true}")
    @Transactional
    public void on(EventEnvelope<JsonNode> event) {
        if (projected.existsById(event.eventId())) {
            return;
        }
        Long orderId = Long.valueOf(event.aggregateId());
        OrderSummary s = summaries.findById(orderId)
                .orElseThrow(() -> new OrderSummaryNotFoundException(orderId));
        switch (event.eventType()) {
            case "InventoryReserved" -> s.onInventoryReserved();
            case "InventoryRejected" -> s.onInventoryRejected(
                    json.treeToValue(event.payload(), InventoryRejected.class).reason());
            case "InventoryReleased" -> s.onInventoryReleased(
                    json.treeToValue(event.payload(), InventoryReleased.class).reason());
            case "PaymentCompleted"  -> s.onPaymentCompleted(
                    json.treeToValue(event.payload(), PaymentCompleted.class).paymentReference());
            case "PaymentFailed"     -> s.onPaymentFailed(
                    json.treeToValue(event.payload(), PaymentFailed.class).reason());
            default -> log.debug("Ignoring {}", event.eventType());
        }
        projected.save(new ProjectedEvent(event.eventId(), Instant.now()));
        log.info("Projected {} → order {} [{} / {}]", event.eventType(), orderId,
                s.getInventoryStatus(), s.getPaymentStatus());
    }
}
```
 
```java
public class OrderSummaryNotFoundException extends RuntimeException {
    public OrderSummaryNotFoundException(Long orderId) { super("No order_summary for order " + orderId); }
}
```
 
Giải thích:
- **Consumer group riêng `order-summary-projector`** (không phải `order-service`): như fan-out ở Chủ đề 4 — projector là 1 "bản sao độc lập" của luồng event. Projector lỗi/chậm không làm kẹt Saga tuần 10, và có thể **reset offset riêng** để dựng lại read model (C9) mà không đụng Saga.
- **1 listener nghe 2 topic** được: `topics = {...}`. Mỗi topic vẫn giữ thứ tự riêng của nó.
- `s.onXxx(...)` chỉ sửa object; `@Transactional` + JPA dirty checking tự `UPDATE` khi commit, kèm `WHERE version = ?`.
- `autoStartup = "${app.projector.enabled:true}"`: bật/tắt projector bằng biến môi trường `APP_PROJECTOR_ENABLED` — cần cho C8, C9.
#### C.6. Bước C5 — Vì sao "chỉ cho đi tiến" và vì sao cần `@Version`
 
**Thứ tự chỉ đảm bảo trong 1 partition của 1 topic.** Projector đọc 2 topic khác nhau, nên có thể xảy ra:
 
```
Thời gian ──►
inventory-events:  ....... [InventoryReserved #12] ← projector đọc topic này bị chậm
payment-events:    [PaymentCompleted #12] ......... ← đọc trước
```
 
| Thứ tự projector nhận | Không có guard | Có guard "chỉ đi tiến" |
|---|---|---|
| PaymentCompleted → InventoryReserved | payment=COMPLETED, inventory=RESERVED ✅ | giống ✅ |
| InventoryReserved → InventoryReleased → (bản trùng) InventoryReserved | inventory quay lại **RESERVED** ❌ | Reserved chỉ áp khi đang `PENDING` → vẫn **RELEASED** ✅ |
| PaymentFailed → InventoryReleased | ✅ | ✅ |
 
`InventoryReserved` và `InventoryReleased` cùng topic, cùng key (orderId) → luôn đúng thứ tự. Nhưng relay có thể gửi trùng (Chủ đề 2) và `projected_events` là lớp chống trùng thứ 1; guard trạng thái là **lớp thứ 2**, giống `status != RESERVED` ở Bước 9 Chủ đề 3.
 
**`@Version`**: event của `inventory-events` và `payment-events` có thể rơi vào 2 consumer thread/2 pod khác nhau → cùng `UPDATE` 1 dòng `order_summary` → pod commit sau bị `ObjectOptimisticLockingFailureException` → `DefaultErrorHandler` retry → đọc lại dòng mới → áp tiếp. Không mất cập nhật nào.
 
```
Không có @Version (lost update):
  pod A đọc {inv:PENDING, pay:PENDING}   pod B đọc {inv:PENDING, pay:PENDING}
  pod A ghi {inv:RESERVED, pay:PENDING}  pod B ghi {inv:PENDING, pay:COMPLETED}  ← đè mất RESERVED ❌
Có @Version:
  pod B ghi ... WHERE version=0 → 0 dòng → exception → retry → đọc {inv:RESERVED} → ghi {RESERVED, COMPLETED} ✅
```
 
#### C.7. Bước C6 — Cấu hình Kafka consume cho order-service
 
order-service tới giờ chỉ produce. Thêm đủ bộ phía consume của Chủ đề 3 (copy từ inventory-service). **Tuần 10 (Saga) dùng lại đúng bộ này** cho listener `order-service` — làm cẩn thận 1 lần ở đây:
 
```yaml
# application.yaml — thêm vào spring.kafka
spring:
  kafka:
    consumer:
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
 
app:
  projector:
    enabled: true
```
 
| File | Copy từ | Sửa gì |
|---|---|---|
| `config/KafkaConfig.java` — bean `JacksonJsonMessageConverter` | inventory | package |
| `config/KafkaErrorConfig.java` | inventory | `addNotRetryableExceptions(OrderSummaryNotFoundException.class)` |
| `kafka/CorrelationIdInterceptor.java` | Phần B | package |
 
`OrderSummaryNotFoundException` không retry: event về 1 order không có trong orderdb → retry bao nhiêu lần cũng không có → đi thẳng `inventory-events-dlt` / `payment-events-dlt`, header `kafka_dlt-original-consumer-group = order-summary-projector`.
 
> Event "dup-test" đơn 900 và 901 bạn gửi tay ở Chủ đề 3 sẽ rơi vào trường hợp này → thấy chúng trong DLT là **đúng**, không phải lỗi.
 
#### C.8. Bước C7 — Đơn cũ chưa có summary: backfill trước khi bật projector
 
⚠️ **Bẫy:** group `order-summary-projector` là group mới → `auto-offset-reset: earliest` → đọc **toàn bộ** event cũ trong `inventory-events`, `payment-events`. Các đơn tạo trước khi có bảng `order_summary` → không có dòng → **hàng loạt message vào DLT**.
 
Cách làm đúng thứ tự:
 
```bash
# 1) Deploy bản mới với projector TẮT → app chạy, ddl-auto tạo bảng order_summary, projected_events
kubectl set env deploy/order-service -n dev APP_PROJECTOR_ENABLED=false
kubectl rollout status deploy/order-service -n dev
```
 
```bash
# 2) Chép đơn cũ sang read model, tất cả ở trạng thái PENDING
kubectl exec -n dev order-db-0 -- psql -U order_user -d orderdb -c "
INSERT INTO order_summary (order_id, version, order_number, customer_name, product_sku, quantity,
                           total_amount, currency, inventory_status, payment_status, created_at, updated_at)
SELECT id, 0, order_number, customer_name, product_sku, quantity,
       total_amount, currency, 'PENDING', 'PENDING', created_at, now()
FROM orders
ON CONFLICT (order_id) DO NOTHING;"
```
✅ `INSERT 0 15` (số đơn của bạn).
❌ `relation "order_summary" does not exist` → bước 1 chưa xong, app chưa khởi động bản mới.
 
```bash
# 3) Bật projector → đọc lại event cũ từ đầu → các dòng PENDING được cập nhật đúng trạng thái
kubectl set env deploy/order-service -n dev APP_PROJECTOR_ENABLED=true
kubectl rollout status deploy/order-service -n dev
```
 
> Bước 3 chính là ưu điểm lớn của CQRS: **read model dựng lại được từ event**. Giới hạn: topic giữ 7 ngày (`retention.ms`) → event cũ hơn 7 ngày đã bị xóa. Production dùng snapshot hoặc compacted topic; tuần này chỉ cần biết.
 
#### C.9. Bước C8 — Endpoint: `controller/OrderSummaryController.java`
 
```java
public record OrderSummaryResponse(Long orderId, String orderNumber, String customerName,
                                   String productSku, Integer quantity, BigDecimal totalAmount, String currency,
                                   String inventoryStatus, String paymentStatus, String paymentReference,
                                   String failureReason, String overallStatus, Instant updatedAt) {
 
    public static OrderSummaryResponse from(OrderSummary s) {
        return new OrderSummaryResponse(s.getOrderId(), s.getOrderNumber(), s.getCustomerName(),
                s.getProductSku(), s.getQuantity(), s.getTotalAmount(), s.getCurrency(),
                s.getInventoryStatus().name(), s.getPaymentStatus().name(), s.getPaymentReference(),
                s.getFailureReason(), overall(s), s.getUpdatedAt());
    }
 
    // 1 trạng thái dễ hiểu cho màn hình, tính lúc đọc — không lưu
    private static String overall(OrderSummary s) {
        if (s.getInventoryStatus() == SummaryInventoryStatus.REJECTED) return "OUT_OF_STOCK";
        if (s.getPaymentStatus() == SummaryPaymentStatus.FAILED)       return "PAYMENT_FAILED";
        if (s.getPaymentStatus() == SummaryPaymentStatus.COMPLETED)    return "COMPLETED";
        return "PROCESSING";
    }
}
```
 
```java
@RestController
@RequiredArgsConstructor
public class OrderSummaryController {
    private final OrderSummaryRepository summaries;
 
    @GetMapping("/{id}/summary")                // context-path /api/orders → /api/orders/{id}/summary
    public ResponseEntity<OrderSummaryResponse> get(@PathVariable Long id) {
        return summaries.findById(id)
                .map(OrderSummaryResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```
 
- **Không `@Cacheable`** endpoint này: dữ liệu đổi theo event; muốn cache thì projector phải evict → thêm độ phức tạp cho 1 câu SELECT theo khóa chính vốn đã nhanh. Tránh tối ưu sớm.
- Nếu `OrderController` đang có `@RequestMapping` riêng, đặt cùng prefix cho khớp.
#### C.10. Chứng minh Phần C
 
Cần `jq` để đọc JSON: `sudo dnf install -y jq`.
 
**1. Eventual consistency nhìn thấy được — luồng thành công:**
 
```bash
ID=$(curl -s -X POST http://localhost:8090/api/orders/ -H 'Content-Type: application/json' \
  -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":2}' | jq -r .id)
echo "order=$ID"
for i in 1 2 3 4 5; do
  curl -s http://localhost:8090/api/orders/$ID/summary | jq -c '{inventoryStatus,paymentStatus,overallStatus}'
  sleep 1
done
```
✅ (thời điểm đổi trạng thái có thể khác, mỗi bước ~1s do relay `fixedDelay = 1000`):
```
order=21
{"inventoryStatus":"PENDING","paymentStatus":"PENDING","overallStatus":"PROCESSING"}
{"inventoryStatus":"RESERVED","paymentStatus":"PENDING","overallStatus":"PROCESSING"}
{"inventoryStatus":"RESERVED","paymentStatus":"COMPLETED","overallStatus":"COMPLETED"}
{"inventoryStatus":"RESERVED","paymentStatus":"COMPLETED","overallStatus":"COMPLETED"}
{"inventoryStatus":"RESERVED","paymentStatus":"COMPLETED","overallStatus":"COMPLETED"}
```
❌ `order=null` → response POST không có field `id` (đổi `.id` theo tên field của bạn).
❌ Đứng mãi ở `PROCESSING` → xem LAG của projector (bước 4).
 
**2. Ba kịch bản** (lặp lệnh trên với 3 body như Chủ đề 3, chờ 5s rồi GET):
 
| Kịch bản | `inventoryStatus` | `paymentStatus` | `overallStatus` | `failureReason` |
|---|---|---|---|---|
| Thành công | `RESERVED` | `COMPLETED` | `COMPLETED` | null |
| Hết hàng (`SOLD-OUT-001`) | `REJECTED` | `SKIPPED` | `OUT_OF_STOCK` | `Insufficient stock` |
| Thẻ bị từ chối (`"fail"`) | `RELEASED` | `FAILED` | `PAYMENT_FAILED` | `CARD_DECLINED` |
 
**3. Không gọi REST sang service khác — chứng minh bằng cách tắt chúng:**
 
```bash
kubectl scale deploy/inventory-service deploy/payment-service -n dev --replicas=0
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/api/orders/$ID/summary
```
✅ `200` — read model nằm trong orderdb, không cần 2 service kia.
 
```bash
# đơn mới trong lúc 2 service tắt → kẹt PROCESSING (không lỗi, chỉ chờ)
ID2=$(curl -s -X POST http://localhost:8090/api/orders/ -H 'Content-Type: application/json' \
  -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":1}' | jq -r .id)
sleep 5; curl -s http://localhost:8090/api/orders/$ID2/summary | jq -r .overallStatus     # PROCESSING
kubectl scale deploy/inventory-service deploy/payment-service -n dev --replicas=1
sleep 30; curl -s http://localhost:8090/api/orders/$ID2/summary | jq -r .overallStatus    # COMPLETED
```
→ Event chờ trong Kafka, service bật lại thì xử lý tiếp → read model tự đúng lại. **Chậm nhưng không mất.**
 
**4. Xem projector có bị tụt lại không (LAG):**
 
```bash
kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group order-summary-projector
```
✅ Cột `LAG` = 0 hết:
```
GROUP                    TOPIC             PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
order-summary-projector  inventory-events  0          7               7               0    consumer-order-summary-projector-1-...
order-summary-projector  inventory-events  1          5               5               0    consumer-order-summary-projector-1-...
order-summary-projector  payment-events    0          4               4               0    consumer-order-summary-projector-1-...
...
```
❌ `Consumer group 'order-summary-projector' has no active members.` + LAG > 0 → projector đang tắt (`APP_PROJECTOR_ENABLED=false`) hoặc order-service crash.
❌ LAG đứng yên > 0 dù có member → message đang retry; xem log order-service tìm exception.
 
**5. 404 cho đơn không tồn tại:**
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/api/orders/999999/summary
```
✅ `404`
 
#### C.11. Bước C9 (nâng cao, nên làm) — Xóa read model, dựng lại từ event
 
Chứng minh câu "read model chỉ là bản sao, mất thì dựng lại":
 
```bash
# 1) Tắt projector (group phải không còn member thì mới reset offset được)
kubectl set env deploy/order-service -n dev APP_PROJECTOR_ENABLED=false
kubectl rollout status deploy/order-service -n dev
 
# 2a) Xóa sạch read model + bảng chống trùng của projector
kubectl exec -n dev order-db-0 -- psql -U order_user -d orderdb \
  -c "TRUNCATE order_summary; TRUNCATE projected_events;"

# 2b) BẮT BUỘC: backfill lại dòng PENDING (giống C7). Projector chỉ UPDATE, không INSERT →
#     bỏ bước này thì mọi event replay → OrderSummaryNotFoundException → DLT, GET summary 404
kubectl exec -n dev order-db-0 -- psql -U order_user -d orderdb -c "
INSERT INTO order_summary (order_id, version, order_number, customer_name, product_sku, quantity,
                           total_amount, currency, inventory_status, payment_status, created_at, updated_at)
SELECT id, 0, order_number, customer_name, product_sku, quantity,
       total_amount, currency, 'PENDING', 'PENDING', created_at, now()
FROM orders
ON CONFLICT (order_id) DO NOTHING;"
# ✅ INSERT 0 <số đơn>   — kiểm tra: SELECT count(*) FROM order_summary; phải bằng số dòng orders
 
# 3) Đưa offset của group về đầu
kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group order-summary-projector \
  --reset-offsets --to-earliest --topic inventory-events --topic payment-events --execute
```
✅
```
GROUP                    TOPIC             PARTITION  NEW-OFFSET
order-summary-projector  inventory-events  0          0
...
```
❌ `Assignments can only be reset if the group 'order-summary-projector' is inactive` → bước 1 chưa xong (pod cũ còn chạy).
 
```bash
# 4) Bật lại → GET summary của các đơn cũ phải giống hệt trước khi xóa
kubectl set env deploy/order-service -n dev APP_PROJECTOR_ENABLED=true
```
 
> Lưu ý: chỉ reset group `order-summary-projector`. **Không** reset group của inventory/payment — chúng sẽ xử lý lại nghiệp vụ (tuy có idempotent nhưng không có lý do làm vậy).
 
#### C.12. Khi nào KHÔNG nên dùng CQRS
 
| Tình huống | Nên | Lý do |
|---|---|---|
| CRUD đơn giản (danh mục sản phẩm, cấu hình) | Không CQRS | Thêm projector, bảng, DLT... chỉ để đọc lại đúng cái vừa ghi |
| Màn hình cần dữ liệu **ngay lập tức, chính xác 100%** (số dư khi chuyển khoản) | Đọc từ write model | Read model có thể trễ |
| Team nhỏ, ít người hiểu event-driven | Cân nhắc kỹ | Debug khó hơn nhiều (Phần B) |
| Query chéo nhiều service, đọc nhiều hơn ghi rất nhiều | ✅ CQRS | Đúng bài toán của `order_summary` |
| Đọc và ghi cần scale khác nhau | ✅ CQRS | Read model có thể nằm ở DB khác (Elasticsearch, Redis...) |
 
> CQRS **không bắt buộc** Event Sourcing, cũng **không bắt buộc** 2 database. Ở đây read model nằm chung orderdb — đủ dùng.
 
---
 
### Phần D — Tự trả lời (có gợi ý đáp án)
 
Viết câu trả lời bằng lời của bạn vào cuối `docs/system-design-2.md`. Gợi ý dưới đây để đối chiếu sau khi viết.
 
#### Câu 1: "Làm sao đảm bảo không mất event?"
 
Trả lời theo **từng chặng** event đi qua — chặng nào cũng phải có cách chống mất:
 
```
[DB order]──①──►[outbox]──②──►[Kafka broker]──③──►[consumer]──④──►[DB inventory]
                                                         └──⑤──► DLT
```
 
| Chặng | Rủi ro | Cách chống trong hệ thống |
|---|---|---|
| ① Ghi đơn + ghi event | Ghi DB xong, gửi Kafka lỗi | **Outbox**: cùng 1 transaction |
| ② Relay → Kafka | Kafka chết, mạng lỗi | Dòng outbox còn `published_at IS NULL` → poll sau gửi lại; `.join()` chờ broker xác nhận mới đánh dấu |
| ② Broker nhận | Broker nhận rồi chết trước khi ghi đĩa | `acks: all` + `enable.idempotence: true` |
| Lưu trên Kafka | Mất broker | Replication. ⚠️ **Trên kind `replicas: 1` → mất broker là mất data.** Production: `replicas: 3`, `min.insync.replicas: 2` |
| ③ Consumer | Commit offset rồi mới xử lý, xử lý lỗi → mất | Spring Kafka commit offset **sau khi** listener chạy xong không lỗi |
| ④ Xử lý lỗi | Exception | Rollback + retry backoff |
| ⑤ Hết retry | Bỏ message | Không bỏ: vào **DLT**, giữ 30 ngày để xem và gửi lại |
 
Kết luận: không có "exactly-once" thật sự giữa nhiều hệ thống → hệ thống chọn **at-least-once** (không mất, có thể trùng) → nên câu 2 bắt buộc phải có lời giải.
 
#### Câu 2: "Consumer nhận trùng thì sao?"
 
Trùng đến từ: relay gửi xong nhưng chưa kịp đánh dấu; consumer xử lý xong nhưng chưa kịp commit offset (pod bị kill, rebalance).
 
3 lớp bảo vệ, lớp nào cũng có trong code tuần 9:
 
| Lớp | Cách | Ví dụ |
|---|---|---|
| 1. Theo `eventId` | Bảng `processed_events` / `projected_events`, insert **cùng transaction** với nghiệp vụ | `OrderEventsListener`, `OrderSummaryProjector` |
| 2. Theo nghiệp vụ | Kiểm tra trạng thái trước khi làm | `release` chỉ khi `RESERVED`; `charge` kiểm tra `existsByOrderId`; summary "chỉ đi tiến" |
| 3. Thao tác tự nhiên idempotent | Dùng "GÁN" thay vì "CỘNG" khi có thể | `status = RESERVED` làm 2 lần vẫn vậy; `available - 2` làm 2 lần thì sai → nên cần lớp 1, 2 |
 
Chứng minh: test "gửi trùng 3 lần" ở Chủ đề 3.
 
#### Câu 3: "Eventual consistency ảnh hưởng UX thế nào?"
 
Vấn đề: vừa POST xong, đơn chưa có kết quả; nếu màn hình hiện "Đặt hàng thành công" ngay thì có thể sai (hết hàng, thẻ bị từ chối).
 
| Cách xử lý | Trong hệ thống |
|---|---|
| Có trạng thái trung gian rõ ràng | `overallStatus = PROCESSING` → UI hiện "Đang xử lý đơn hàng..." |
| Read-your-own-writes | Dòng summary tạo cùng transaction → GET ngay không bị 404 |
| Client hỏi lại (polling) mỗi 1–2s đến khi hết `PROCESSING` | Lệnh `for` ở C10 chính là polling |
| Server đẩy về (SSE/WebSocket) | Chưa làm — biết khái niệm |
| Thông báo bất đồng bộ | Email từ notification-service (Chủ đề 4) |
| API trả `202 Accepted` thay vì `201 Created` | Đúng nghĩa hơn ("đã nhận, đang xử lý"); tuần này giữ `201` vì đơn đã được ghi thật |
| Đặt timeout | Kẹt `PROCESSING` quá lâu → tuần 10 Saga xử lý timeout (cần `updatedAt`) |
 
---
 
### `docs/system-design-2.md` — khung gợi ý
 
```markdown
# System Design 2 — Event-Driven & CQRS
 
## 1. Sơ đồ luồng event 4 service          ← chép sơ đồ Bước 6 Chủ đề 3 + projector
## 2. 3 kiểu event                          ← bảng A.2, ghi rõ hệ thống dùng ECST, vì sao
## 3. Ưu / nhược event-driven                ← bảng A.3, kèm ví dụ thật
## 4. Correlation ID                         ← sơ đồ B.2 + 1 đoạn log grep thật của bạn
## 5. CQRS: order_summary                    ← sơ đồ C.1 (3 cách), trade-off C2, guard C5
## 6. Khi nào không dùng CQRS                ← bảng C.12
## 7. Tự trả lời 3 câu hỏi                   ← Phần D bằng lời của bạn
```
 
---
 
**Output cụ thể:**
- Cả 4 service có `Correlation`, log pattern `[cid=...]`; order/inventory/payment lưu `correlation_id` trong outbox và gửi Kafka header; inventory/payment/notification/order có `CorrelationIdInterceptor`
- order-service: bảng `order_summary` + `projected_events`, `OrderSummaryProjector` (group `order-summary-projector`), `GET /api/orders/{id}/summary`
- `docs/system-design-2.md` theo khung trên
- Chứng minh:
  - `grep 'cid=demo-cid-001'` thấy log ở cả 4 service; `print.headers=true` thấy header trên cả 3 topic
  - Polling summary thấy `PROCESSING` → `COMPLETED`; 3 kịch bản đúng bảng C10
  - Tắt inventory + payment → summary vẫn trả `200`; bật lại → đơn kẹt tự `COMPLETED`
  - Xóa read model + reset offset → dựng lại giống hệt
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
- [ ] CQRS read model cho order summary (dựng lại được từ event)
- [ ] Correlation ID: grep 1 mã thấy log ở cả 4 service
**Tuần sau → Tuần 10: Saga, EKS (Terraform), deploy 4 service lên kind, tổng kết giai đoạn** 🚀