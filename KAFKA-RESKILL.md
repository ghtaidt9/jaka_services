# Kafka Reskill — Tổng hợp Tuần 8 + 9

Tài liệu ôn lại mọi thứ về Kafka đã làm trong Tuần 8–9, xếp theo **tầng**: hiểu tầng dưới rồi mới lên tầng trên. Mọi ví dụ lấy từ chính code và cluster `jaka` của bạn.

---

## 0. Cách dùng tài liệu này

### Bản đồ 6 tầng

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Tầng 6  KIẾN TRÚC        Event contract · 3 kiểu event · CQRS · Saga · cid │  "thiết kế hệ thống"
├──────────────────────────────────────────────────────────────────────────┤
│ Tầng 5  ĐỘ TIN CẬY       Outbox · Inbox (idempotent) · Retry · DLT         │  "không mất, không trùng"
├──────────────────────────────────────────────────────────────────────────┤
│ Tầng 4  SPRING KAFKA     KafkaTemplate · @KafkaListener · converter ·      │  "code Java"
│                          error handler · interceptor                       │
├──────────────────────────────────────────────────────────────────────────┤
│ Tầng 3  KAFKA TRÊN K8S   Strimzi operator · Kafka / KafkaTopic CRD         │  "hạ tầng"
├──────────────────────────────────────────────────────────────────────────┤
│ Tầng 2  ĐỘ BỀN           replication · ISR · acks · idempotence · CAP      │  "message có an toàn?"
├──────────────────────────────────────────────────────────────────────────┤
│ Tầng 1  LÕI KAFKA        broker · topic · partition · key · offset ·       │  "Kafka là gì"
│                          consumer group · retention                        │
└──────────────────────────────────────────────────────────────────────────┘
```

### Lộ trình ôn
| Buổi | Đọc | Tự làm |
|---|---|---|
| 1 | Tầng 1 + 2 | Chạy các lệnh "Tự kiểm" trong từng mục |
| 2 | Tầng 3 + 4 | Đọc lại `KafkaConfig`, `application.yaml`, `CorrelationIdInterceptor` của bạn |
| 3 | Tầng 5 | Đọc `OutboxRelay`, `OutboxWriter`, `OrderEventConsumer`; gửi trùng và gửi JSON hỏng |
| 4 | Tầng 6 + mục 7 | Theo dõi 1 đơn đi qua 4 service |
| 5 | Mục 9 | Trả lời câu hỏi tự kiểm mà không mở đáp án |

### Port-forward cần có khi ôn trên kind
```bash
kubectl port-forward -n envoy-gateway-system svc/envoy-dev-web-39aa3d06 8090:80   # API
kubectl port-forward -n dev svc/mailpit 8025:8025                                   # xem mail
```

Rút gọn lệnh Kafka trong tài liệu này:
```bash
K="kubectl exec -n kafka jaka-dual-role-0 -- bin"
```
Ví dụ: `$K/kafka-topics.sh --bootstrap-server localhost:9092 --list`.

---

## Tầng 1 — Lõi Kafka

### 1.1. Kafka là gì, một câu
Kafka là một **log chỉ ghi thêm (append-only), chia thành nhiều phần, lưu trên đĩa, nhiều người đọc độc lập**. Không phải hàng đợi kiểu "đọc xong là mất".

```
                         topic: order-events
                ┌───────────────────────────────────────────┐
 producer ──►   │ partition 0: [0][1][2][3][4][5]...        │ ──► consumer group A (inventory)
 (key=orderId)  │ partition 1: [0][1][2][3]...              │ ──► consumer group B (notification)
                │ partition 2: [0][1][2][3][4]...           │ ──► consumer group C (projector)
                └───────────────────────────────────────────┘
                  mỗi ô = 1 message, số trong ô = offset
```

### 1.2. Các khái niệm cốt lõi

| Khái niệm | Là gì | Trong project |
|---|---|---|
| **Broker** | Một server Kafka | `jaka-dual-role-0` (1 broker) |
| **Topic** | Kênh sự kiện có tên | `order-events`, `inventory-events`, `payment-events` + 3 DLT |
| **Partition** | Topic chia thành N log độc lập để chạy song song | Mỗi topic 3 partition |
| **Offset** | Số thứ tự của message **trong 1 partition** | `order-events-0` offset 38 |
| **Key** | Quyết định partition: `hash(key) % số partition` | `aggregateId` = orderId |
| **Consumer group** | Nhóm consumer chia nhau đọc 1 topic | `inventory-service`, `payment-service`, `notification-service`, `order-summary-projector` |
| **Retention** | Kafka giữ message bao lâu | 7 ngày (topic chính), 30 ngày (DLT) |
| **KRaft** | Kafka tự quản lý metadata, không cần ZooKeeper | Node pool `dual-role` = vừa controller vừa broker |

### 1.3. Partition + Key = thứ tự
- Thứ tự **chỉ được đảm bảo trong 1 partition**, không đảm bảo giữa các partition hay giữa các topic.
- Cùng key thì cùng partition. Dùng `orderId` làm key nên mọi event của **1 đơn** đi đúng thứ tự.

```
orderId=9  → hash → partition 0:  OrderCreated#9 ... (luôn theo thứ tự)
orderId=10 → hash → partition 2:  OrderCreated#10 ...
```

`order-events-0` là cách viết tắt của **topic `order-events`, partition 0**.

⚠️ Projector đọc **2 topic** (`inventory-events`, `payment-events`) nên có thể nhận `PaymentCompleted` trước `InventoryReserved` của cùng 1 đơn. Xem cách xử lý ở Tầng 6, phần CQRS.

### 1.4. Offset và consumer group — chỗ dễ nhầm nhất
- **Offset của message** là vị trí cố định của message trong partition. Không đổi.
- **Offset của group** (committed offset) là chỗ **mỗi group** đã đọc tới, lưu trong topic nội bộ `__consumer_offsets`.

```
order-events-0:  [..][35][36][37][38]
                              ▲    ▲
       group notification-service  group inventory-service
       (đã đọc tới 37)             (đã đọc tới 38)
```

| Câu hỏi | Trả lời |
|---|---|
| Message có bị xóa khi đọc xong? | **Không.** Chỉ bị xóa khi hết retention |
| Inventory đọc xong thì notification còn đọc được không? | **Được.** Mỗi group có offset riêng |
| 2 group cùng nghe 1 topic thì sao? | **Fan-out**: mỗi group nhận **đủ** mọi message |
| 2 pod cùng 1 group thì sao? | **Chia việc**: mỗi partition chỉ giao cho 1 pod trong group |
| Group mới chưa có offset đọc từ đâu? | Theo `auto-offset-reset`: `earliest` = từ đầu, `latest` = chỉ message mới |

### 1.5. Scale consumer: partition là giới hạn
```
3 partition, group payment-service:
  1 pod  → pod A: p0, p1, p2
  2 pod  → pod A: p0, p1   pod B: p2
  3 pod  → mỗi pod 1 partition
  4 pod  → 1 pod NGỒI KHÔNG (không có partition để nhận)
```
Bài học từ Tuần 8: bạn scale lên 3 pod mà chỉ thấy partition 0 và 1. Đó là vì các pod join group vào những thời điểm khác nhau nên rebalance diễn ra nhiều lần. Đọc kết quả cuối cùng bằng `--describe`, đừng chỉ nhìn log của từng pod.

**Rebalance** là lúc Kafka chia lại partition khi pod vào hoặc ra. Phần chưa commit offset có thể bị đọc lại, và đây là một nguồn gây trùng (xem Tầng 5).

### 1.6. LAG
```
LAG = LOG-END-OFFSET (message cuối) − CURRENT-OFFSET (group đã đọc tới)
```
| LAG | Ý nghĩa |
|---|---|
| 0 | Theo kịp |
| Tăng rồi giảm | Bình thường, có đợt tải |
| Tăng mãi | Consumer chậm, chết, bị tắt, hoặc **kẹt retry** |
| > 0 + "no active members" | Không còn pod nào trong group |

### 1.7. Retention — Kafka không giữ mãi
| Cấu hình | Hành vi |
|---|---|
| `cleanup.policy=delete` + `retention.ms` | Xóa theo thời gian, xóa **nguyên segment** (của bạn: 7 ngày) |
| `retention.ms=-1` | Giữ mãi |
| `cleanup.policy=compact` | Giữ bản **mới nhất mỗi key** |

Hệ quả: chỉ replay được event trong 7 ngày (ảnh hưởng tới việc dựng lại read model ở Tầng 6).

### ✅ Tự kiểm Tầng 1
```bash
$K/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-events
$K/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
$K/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-events \
  --from-beginning --timeout-ms 8000 --formatter-property print.key=true --formatter-property print.partition=true
```
Trả lời được: cùng 1 orderId thì message nằm ở partition nào? Group nào đang có LAG?

---

## Tầng 2 — Độ bền: message có an toàn không?

### 2.1. Replication và ISR
- **Replication factor (RF)**: mỗi partition có RF bản sao trên RF broker khác nhau. 1 bản là **leader** (nhận ghi và đọc), các bản còn lại là **follower** (chép theo).
- **ISR (In-Sync Replicas)**: các bản sao **đang theo kịp leader**. Follower tụt quá `replica.lag.time.max.ms` (30 giây) thì bị loại khỏi ISR.
- Leader chết: Kafka chọn leader mới **chỉ từ ISR**, để không mất message đã được ack.

Cluster kind của bạn: RF=1, `min.insync.replicas=1`, nghĩa là **broker chết thì không ghi được gì**. Production thường dùng RF=3, `min.insync.replicas=2`.

### 2.2. `acks` — producer chờ xác nhận tới đâu
| `acks` | Leader trả OK khi | Rủi ro |
|---|---|---|
| `0` | Không chờ gì | Mất nhiều |
| `1` | Leader ghi vào RAM | Leader chết trước khi follower chép thì **mất** |
| `all` (bạn dùng) | **Mọi replica trong ISR** đã có | Chỉ mất nếu toàn bộ ISR chết cùng lúc |

Kafka **không fsync từng message** xuống đĩa. Nó dựa vào replication để an toàn, nên `acks=all` mới là thứ bảo vệ message.

### 2.3. `min.insync.replicas` — ISR tối thiểu để được ghi
| RF | min.insync | 1 broker chết | Kết quả |
|---|---|---|---|
| 3 | 2 | ISR = 2 | ✅ Ghi được, vẫn có 2 bản |
| 2 | 2 | ISR = 1 | ❌ `NotEnoughReplicasException` |
| 2 | 1 | ISR = 1 | ✅ Ghi được, nhưng chỉ 1 bản → rủi ro mất |

### 2.4. `enable.idempotence=true` — chống ghi trùng do producer retry
```
producer ──msg──► leader ghi OK ──ack──✂ (mất trên đường về)
producer retry ──msg──► KHÔNG idempotence: ghi lần 2 ❌
                       CÓ idempotence: broker thấy (producerId, sequence) đã có → bỏ qua ✅
```
Giới hạn: chỉ chống trùng **trong 1 phiên producer**. Pod restart rồi relay gửi lại thì là producer mới, vẫn có thể trùng. Vì vậy consumer vẫn cần Inbox (Tầng 5).

| Cấu hình | Chống |
|---|---|
| `acks: all` | **Mất** (broker chết sau khi nhận) |
| `enable.idempotence: true` | **Trùng** (producer tự retry) |

### 2.5. CAP với Kafka
Khi mạng đứt (P), phải chọn:
- **C (nhất quán):** không chắc an toàn thì **từ chối ghi**. Cấu hình: RF=3, `min.insync.replicas=2`, `acks=all`.
- **A (sẵn sàng):** vẫn nhận ghi, chấp nhận có thể mất. Cấu hình: `acks=1`, `unclean.leader.election.enable=true`.

Giữa các service giao tiếp qua Kafka: luôn là **eventual consistency** (khớp nhau sau một khoảng trễ).

---

## Tầng 3 — Kafka trên Kubernetes (Strimzi)

### 3.1. Operator pattern
Kafka khó vận hành (broker, controller, chứng chỉ, topic...). **Operator** = CRD (bạn khai báo "muốn gì") + controller (tự làm cho thật giống khai báo).

```
bạn apply  Kafka / KafkaNodePool / KafkaTopic (YAML)
              │
              ▼
 strimzi-cluster-operator ──► tạo pod jaka-dual-role-0, Service, PVC, cấu hình
 entity-operator (topicOperator) ──► tạo / sửa topic thật trong Kafka theo KafkaTopic
```

### 3.2. Các file của bạn
| File | Khai báo |
|---|---|
| `jaka_infrastructure/k8s/kafka/kafka-single-node.yml` | `KafkaNodePool dual-role` (1 node, controller + broker, 5Gi) + `Kafka jaka` (4.3.1, listener 9092 plain, 9093 tls) |
| `jaka_infrastructure/k8s/kafka/topics.yml` | 6 `KafkaTopic`: 3 topic chính (7 ngày) + 3 DLT (30 ngày), mỗi topic 3 partition, RF 1 |

Label `strimzi.io/cluster: jaka` là thứ gắn node pool và topic vào cluster. Sai label thì Kafka không bao giờ Ready, hoặc topic không được tạo.

### 3.3. Service để kết nối
| Service | Dùng cho |
|---|---|
| `jaka-kafka-bootstrap.kafka:9092` | App kết nối vào đây (`KAFKA_BOOTSTRAP` trong values Helm) |
| `jaka-kafka-brokers` (headless) | Địa chỉ từng broker, client tự dùng sau khi bootstrap |

Client chỉ cần **1 địa chỉ bootstrap**. Kafka trả về danh sách broker, và client nói chuyện thẳng với leader của từng partition.

### ✅ Tự kiểm Tầng 3
```bash
kubectl get kafka,kafkanodepool,kafkatopic -n kafka
kubectl get svc -n kafka
```

---

## Tầng 4 — Spring Kafka

### 4.1. Dependency (Spring Boot 4)
- Dùng `spring-boot-starter-kafka`. Boot 4 tách auto-config thành module riêng, nên chỉ thêm `spring-kafka` thì **không có** `KafkaTemplate`.
- Boot 4 dùng **Jackson 3**: package `tools.jackson.*`, class `JacksonJson...`. Các class `JsonSerializer` / `JsonDeserializer` cũ dựa trên Jackson 2.

### 4.2. Tiến hóa cách serialize: Tuần 8 → Tuần 9
| | Tuần 8 | Tuần 9 (hiện tại) |
|---|---|---|
| Producer | `JacksonJsonSerializer` (object → JSON) | `StringSerializer`: relay gửi **chuỗi JSON có sẵn** trong `outbox.payload` |
| Consumer | `JacksonJsonDeserializer` + `value.default.type` cố định 1 class | `StringDeserializer` + bean `JacksonJsonMessageConverter` |
| Vấn đề cũ | Header `__TypeId__` chứa tên class của service gửi → bên nhận không có class đó | Không còn type header |
| Nhiều loại event / topic | Khó | Dễ: listener nhận `EventEnvelope<JsonNode>` rồi `switch(eventType)` |

### 4.3. Converter là gì
```
Kafka bytes ──StringDeserializer──► String ──JacksonJsonMessageConverter──► EventEnvelope<OrderCreated>
             (tầng Kafka client)              (tầng Spring, nhìn kiểu tham số của method listener)
```
Bean `RecordMessageConverter kafkaMessageConverter()` trong `KafkaConfig`: Boot thấy bean này và tự gắn vào mọi `@KafkaListener`. Nhờ vậy method listener khai báo kiểu gì, Spring parse JSON thành kiểu đó.

### 4.4. Phía gửi: `KafkaTemplate`
Trong `OutboxRelay`:
```java
var record = new ProducerRecord<>("order-events", event.getAggregateId(), event.getPayload());
record.headers().add("correlationId", ...);
record.headers().add("eventType", ...);
kafka.send(record).join();     // chờ broker ack (acks=all) rồi mới đánh dấu đã gửi
```
- `send()` trả về `CompletableFuture`, tức gửi bất đồng bộ.
- `.join()` **chờ kết quả**: lỗi thì ném exception, `published_at` không được set, và lần quét sau gửi lại. Bỏ `.join()` là có thể đánh dấu "đã gửi" cho một message chưa tới được Kafka.

### 4.5. Phía nhận: `@KafkaListener`
```java
@KafkaListener(topics = "order-events", groupId = "inventory-service")
@Transactional
public void on(EventEnvelope<OrderCreated> event) { ... }
```
| Thuộc tính | Ý nghĩa |
|---|---|
| `topics` | 1 hoặc nhiều topic (`{"inventory-events", "payment-events"}`) |
| `groupId` | Consumer group; khác group = nhận riêng một bản (fan-out) |
| `autoStartup = "${app.projector.enabled:true}"` | `false` thì listener được tạo nhưng **không join group** (dùng khi backfill hoặc reset offset) |

Env `APP_PROJECTOR_ENABLED` tự map sang property `app.projector.enabled` (relaxed binding). Đổi env làm **pod template đổi**, Deployment rollout pod mới.

### 4.6. Offset được commit khi nào
Mặc định (ack mode `BATCH`): Spring commit offset **sau khi** listener xử lý xong lô message vừa poll. Listener ném exception thì error handler xử lý (4.7), offset chưa commit.

Hệ quả: **xử lý xong ≠ đã commit**. Pod chết ở giữa hai bước thì message được đọc lại. Đây là lý do có Tầng 5.

### 4.7. Error handler: retry → DLT
`KafkaConfig` của bạn:
```java
var recoverer = new DeadLetterPublishingRecoverer(template);   // gửi sang <topic>-dlt, cùng partition
var backOff = new ExponentialBackOff(1000L, 2.0);              // chờ 1s, 2s, 4s, 8s...
backOff.setMaxElapsedTime(10_000L);                            // tổng tối đa ~10s
var handler = new DefaultErrorHandler(recoverer, backOff);
handler.addNotRetryableExceptions(ProductNotFoundException.class);   // lỗi vĩnh viễn → DLT ngay
```

```
listener ném exception
  ├─ thuộc NotRetryable?  → DLT ngay
  └─ còn lại              → retry theo backoff (giữ nguyên partition, các message sau phải chờ)
                             └─ hết thời gian → DLT
DLT = topic gốc + "-dlt", cùng số partition
Header gắn thêm: kafka_dlt-exception-message, kafka_dlt-original-topic,
                 kafka_dlt-original-consumer-group, ...
```

| Loại lỗi | Ví dụ | Xử lý |
|---|---|---|
| Tạm thời | Mất kết nối DB, `ObjectOptimisticLockingFailureException` | Retry |
| Vĩnh viễn (dữ liệu sai) | SKU không tồn tại, không có reservation, không có summary | `addNotRetryableExceptions` → DLT ngay |
| JSON hỏng | `{not json` | Lỗi convert → DLT |

⚠️ Bẫy đã gặp 2 lần: viết `addRetryableExceptions` (có retry) thay vì `addNotRetryableExceptions` (không retry).

**`RetryListener`** (đang có ở notification): log từng lần thất bại (`failedDelivery`) và lúc bỏ cuộc, gửi DLT (`recovered`). Thiếu nó thì lỗi chỉ hiện trong DLT, log của app im lặng. Bạn đã gặp đúng chuyện này khi notification thiếu cột `status` / `sentAt`.

### 4.8. Interceptor: chạy trước và sau mỗi message
`CorrelationIdInterceptor implements RecordInterceptor`:
- `intercept()` (trước listener): đọc header `correlationId` và đưa vào MDC.
- `afterRecord()` (sau listener): trả MDC về như cũ.

Đánh dấu `@Component` là Boot tự gắn interceptor vào listener container.

---

## Tầng 5 — Độ tin cậy: không mất, không trùng

### 5.1. Vấn đề gốc: dual write
```java
orders.save(order);                  // ghi DB
kafka.send("order-events", event);   // ghi Kafka
```
Hai hệ thống khác nhau, **không chung transaction**:
| Chuyện xảy ra | Kết quả |
|---|---|
| DB commit, Kafka lỗi (broker chết) | Có đơn, không có event → inventory không bao giờ biết |
| Gửi Kafka trước, DB rollback | Có event cho một đơn không tồn tại |

Đây là thí nghiệm "xóa pod broker rồi POST" ở Tuần 8, Phần 5.

### 5.2. Transactional Outbox — chống MẤT (phía producer)
```
OrderService.create()  @Transactional
  ├─ INSERT orders
  ├─ INSERT order_summary
  └─ OutboxWriter.write → INSERT outbox (published_at = NULL)       ← cùng 1 transaction
COMMIT

OutboxRelay  @Scheduled(fixedDelay=1000) @Transactional
  ├─ SELECT ... WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
  ├─ kafka.send(record).join()
  └─ published_at = now()
```
| Chi tiết | Vì sao |
|---|---|
| Cùng transaction với nghiệp vụ | Có đơn thì chắc chắn có dòng outbox, và ngược lại |
| `ORDER BY id` | Gửi đúng thứ tự tạo |
| `FOR UPDATE SKIP LOCKED` | 2 pod chạy relay không lấy cùng dòng |
| `.join()` trước khi set `published_at` | Chỉ đánh dấu khi Kafka đã ack |
| `@EnableScheduling` | Thiếu thì `@Scheduled` không chạy (bẫy đã gặp) |

**Outbox đảm bảo at-least-once**: relay gửi xong nhưng chết trước khi commit `published_at` thì lần sau gửi lại. Vậy là **có thể trùng**.

### 5.3. Nguồn gây trùng ở phía consumer
1. Relay gửi lại (5.2).
2. Xử lý xong nhưng pod chết trước khi commit offset.
3. Rebalance khi scale.
4. Reset offset bằng tay.
5. Retry của error handler sau khi đã ghi một phần.

Kafka mặc định đảm bảo **at-least-once**. Consumer **phải tự** chống trùng.

### 5.4. Inbox / Idempotent consumer — chống TRÙNG (phía consumer)
```
@Transactional
  1. processed.existsById(eventId)?  có → return
  2. xử lý nghiệp vụ
  3. processed.save(new ProcessedEvent(eventId, now))      ← cùng transaction với bước 2
COMMIT
```
| Service | Bảng chống trùng | Lớp thứ 2 (guard nghiệp vụ) |
|---|---|---|
| inventory | `processed_events` | reservation phải đang `RESERVED` mới commit / release |
| payment | `processed_events` | `existsByOrderId` (mỗi đơn chỉ thu 1 lần) |
| notification | `notifications.event_id` UNIQUE + `flush()` trước khi gửi mail | — |
| order projector | `projected_events` | Guard "chỉ đi tiến" + `@Version` |

Quy tắc:
- **Mỗi consumer group một bảng inbox riêng.** Dùng chung thì group này ghi `eventId`, group kia thấy "đã có" nên bỏ qua nhầm.
- Inbox chỉ chắc chắn khi tác dụng nằm **trong cùng DB**. Gửi mail hay gọi API ngoài vẫn có khe hở nhỏ, chỉ giảm được bằng idempotency key ở phía bên ngoài.
- Bảng inbox lớn dần. Thực tế cần job xóa các dòng cũ hơn retention của Kafka.

### 5.5. Outbox + Inbox = hiệu lực đúng 1 lần
```
Producer                      Kafka                      Consumer
INSERT nghiệp vụ + outbox ──relay──► (có thể trùng) ──► check inbox → xử lý + INSERT inbox
     "không mất"                                              "không trùng"
```

### 5.6. Concurrency trên cùng 1 dòng: optimistic lock
2 pod cùng sửa 1 dòng (`products` của inventory, `order_summary` của projector):
```sql
UPDATE ... SET ..., version = 6 WHERE id = ? AND version = 5
```
- 0 dòng khớp: `ObjectOptimisticLockingFailureException` → retry → đọc bản mới → áp lại.
- Chỉ cần field `@Version`. Hibernate tự thêm điều kiện `WHERE version = ?`.

### ✅ Tự kiểm Tầng 5
TUAN-9 Chủ đề 3, proof 1–3:
- Gửi trùng 3 lần → kho chỉ trừ 1.
- Gửi JSON hỏng → nằm trong DLT, consumer vẫn chạy tiếp.
- Đặt 2 đơn đồng thời cho SKU còn 1 → 1 `Reserved`, 1 `Rejected`.

---

## Tầng 6 — Kiến trúc

### 6.1. Event contract: envelope + payload
```json
{
  "eventId": "uuid",            ← chống trùng (inbox)
  "eventType": "PaymentCompleted", ← switch để biết payload là record nào
  "version": 1,                 ← đổi schema an toàn
  "aggregateId": "9",           ← orderId, dùng làm key Kafka → đúng thứ tự theo đơn
  "occurredAt": "2026-...Z",    ← thời điểm duy nhất của event
  "payload": { ... }            ← dữ liệu nghiệp vụ, khác nhau theo eventType
}
```
- Vỏ dùng cho **hạ tầng** (dedup, định tuyến, thứ tự, version), ruột dùng cho **nghiệp vụ**.
- Mỗi service **tự copy** record nó dùng vào `dto/` của mình (không dùng thư viện chung).
- Đổi schema: chỉ **thêm** field. Đổi tên, đổi kiểu hay xóa field thì phải tăng `version`.
- Danh mục đầy đủ: `docs/event-catalog.md`.

Header Kafka (`correlationId`, `eventType`) nằm **ngoài** value: lọc được mà không cần parse JSON.

### 6.2. Luồng event của hệ thống
```
order-svc ──OrderCreated──► order-events ──► inventory: reserve
                                               ├─ đủ hàng   → InventoryReserved ─► inventory-events ─► payment: charge
                                               │                                                     ├─ OK  → PaymentCompleted ─► payment-events ─► inventory: commit
                                               │                                                     └─ lỗi → PaymentFailed    ─► payment-events ─► inventory: release
                                               │                                                                                                      └─ InventoryReleased
                                               └─ thiếu hàng → InventoryRejected ─► inventory-events
notification: nghe cả 3 topic → gửi mail
order projector: nghe inventory-events + payment-events → cập nhật order_summary
```

### 6.3. Các pattern — mỗi cái trả lời một câu hỏi khác nhau
| Pattern | Câu hỏi | Một câu | Project |
|---|---|---|---|
| **Event Notification** | Event chứa gì? | Chỉ có id, cần thêm thì gọi REST | — |
| **Event-Carried State Transfer** | Event chứa gì? | Mang đủ dữ liệu, consumer không gọi ai | ✅ `InventoryReserved` có `totalAmount` |
| **Event Sourcing** | Lưu trạng thái thế nào? | Lưu chuỗi event thay cho trạng thái, replay để biết hiện tại | — (vẫn lưu `orders.status`) |
| **CQRS** | Đọc và ghi chung mô hình? | Tách bảng ghi và bảng đọc, bảng đọc cập nhật từ event | ✅ `orders` / `order_summary` |
| **Outbox** | Gửi sao cho không mất? | Ghi event vào DB cùng transaction | ✅ |
| **Inbox** | Nhận sao cho không trùng? | Ghi `eventId` đã xử lý cùng transaction | ✅ |
| **Saga** | Giao dịch nhiều service? | Chuỗi bước, lỗi thì bù trừ | ✅ `PaymentFailed` → `InventoryReleased` (choreography) |

Hay nhầm:
- **Dùng Kafka ≠ Event Sourcing.**
- **Outbox ≠ event store.** Outbox là hàng đợi gửi đi, nguồn sự thật vẫn là bảng.
- **CQRS không cần Event Sourcing**, cũng **không cần 2 database**.

### 6.4. CQRS trong project
```
           WRITE (nguồn sự thật)                         READ (bản sao để đọc nhanh)
POST ──► orders, reservations, payments ──event──► OrderSummaryProjector ──► order_summary ◄── GET /{id}/summary
         chuẩn hóa, đúng nghiệp vụ          Kafka   (group riêng)            phi chuẩn hóa, trễ vài giây
```
| Thành phần | Vai trò |
|---|---|
| `OrderService.create` | INSERT `order_summary` (PENDING/PENDING) **cùng transaction** với đơn → POST xong GET được ngay (read-your-own-writes) |
| `OrderSummaryProjector` | Chỉ **UPDATE**: `findById` không thấy thì `OrderSummaryNotFoundException` → DLT |
| Guard "chỉ đi tiến" | `onInventoryReserved` chỉ áp khi đang `PENDING` → bản trùng đến muộn không kéo trạng thái lùi lại |
| `@Version` | 2 topic cập nhật cùng dòng song song → không mất cập nhật |
| `projected_events` | Inbox riêng của projector |
| Read model **chỉ biết những gì có event** | Inventory commit không phát event → enum kho không có `COMMITTED` |

**Dựng lại read model từ event (C9):**
```
1. Tắt projector (APP_PROJECTOR_ENABLED=false, chờ rollout xong → group không còn member)
2. TRUNCATE order_summary, projected_events
3. INSERT backfill từ orders (PENDING)      ← BẮT BUỘC: projector không INSERT
4. Reset offset group projector --to-earliest
5. Bật projector → replay → giống hệt trước khi xóa
```
Chỉ reset group của projector. Các group khác không bị ảnh hưởng vì offset là của từng group.

### 6.5. Correlation ID: theo dấu 1 request qua 4 service
```
HTTP header X-Correlation-Id
  → CorrelationIdFilter: đưa vào MDC
  → OutboxWriter: lưu outbox.correlation_id
  → OutboxRelay: Kafka header "correlationId"
  → CorrelationIdInterceptor ở service nhận: header → MDC
  → service đó ghi outbox của nó với cùng cid → ... lan tiếp
Log pattern: "%5p [cid=%X{correlationId:-}]"
```
| ID | Phạm vi |
|---|---|
| `eventId` | 1 event (chống trùng) |
| `aggregateId` | 1 đơn (key, thứ tự) |
| `correlationId` | 1 request của người dùng, xuyên mọi service (debug) |

### 6.6. Eventual consistency — hệ quả phải chấp nhận
- POST xong, summary hiện `PROCESSING` khoảng 1–2 giây rồi mới `COMPLETED`.
- Inventory và payment tắt: summary vẫn đọc được (`200`), đơn mới kẹt `PROCESSING`. Bật lại thì tự đúng. **Chậm nhưng không mất.**

---

## 7. Theo dấu 1 đơn hàng: trọn vòng

Đơn `Tai`, `TEST-001`, qty 2, 20 USD → id 9:

| # | Ở đâu | Chuyện gì | Bảng / topic |
|---|---|---|---|
| 1 | order: `OrderService.create` | INSERT 3 bảng trong 1 transaction | `orders`, `order_summary` (PENDING/PENDING), `outbox` |
| 2 | order: `OutboxRelay` (≤1s) | Gửi key=`9`, header cid | `order-events` p? |
| 3 | inventory: `OrderEventConsumer` | Check inbox → reserve (`available -= 2`) → outbox `InventoryReserved` | `processed_events`, `products`, `reservations`, `outbox` |
| 4 | notification | Mail "Đơn ... đã được tạo" | `notifications` |
| 5 | inventory relay | Gửi `InventoryReserved` | `inventory-events` |
| 6 | payment: `InventoryEventConsumer` | Check inbox → charge → outbox `PaymentCompleted` | `processed_events`, `payments`, `outbox` |
| 6' | order projector | `inventoryStatus = RESERVED` | `order_summary`, `projected_events` |
| 7 | payment relay | Gửi `PaymentCompleted` | `payment-events` |
| 8 | inventory: `PaymentEventConsumer` | commit reservation (không phát event) | `reservations` |
| 8' | notification | Mail "Thanh toán đơn #9 thành công" | `notifications` |
| 8'' | order projector | `paymentStatus = COMPLETED` → `overallStatus = COMPLETED` | `order_summary` |

Cả chuỗi mang **cùng 1 correlationId**:
```bash
kubectl logs -n dev -l 'app in (order-service,inventory-service,payment-service,notification-service)' \
  --prefix --tail=500 | grep "cid=<id-của-bạn>"
```

---

## 8. Bẫy đã gặp trong Tuần 8–9

| # | Triệu chứng | Nguyên nhân | Cách nhận ra |
|---|---|---|---|
| 1 | Lỗi vĩnh viễn vẫn retry ~10s | `addRetryableExceptions` thay vì `addNotRetryableExceptions` | Log retry, DLT đến chậm |
| 2 | Outbox đầy, không gì được gửi | Thiếu `@EnableScheduling` | `published_at` toàn NULL |
| 3 | Bean không được tạo | `OutboxWriter` thiếu `@Component` | App không khởi động |
| 4 | Consumer mới đọc message cũ sai format → DLT hàng loạt | Group mới + `auto-offset-reset: earliest` | Reset offset `--to-latest` **trước** khi deploy |
| 5 | Message vào DLT, log app im lặng | Không có `RetryListener` | Xem header `kafka_dlt-exception-message` |
| 6 | Payload sai kiểu | `create` bọc envelope 2 lần / truyền entity thay vì record | Đọc value trên topic |
| 7 | Trùng `orderNumber` khi gửi đồng thời | `currentTimeMillis` giống nhau | Dùng một đoạn UUID |
| 8 | Replay → 404 + DLT | Quên INSERT backfill trước khi bật projector | `count(order_summary)` = 0 |
| 9 | Kafka từ chối reset offset | Group còn member | Chờ `rollout status` xong |
| 10 | Rollout treo `ImagePullBackOff` | Build `v8`, helm set tag `8` | `kubectl describe pod` |
| 11 | `kubectl set env` bị mất sau `helm upgrade` | Helm không biết thay đổi ngoài nó | Dùng `--set env.X=...` |
| 12 | Thêm giá trị enum → lỗi CHECK constraint | `ddl-auto: update` không sửa constraint cũ | `ALTER TABLE ... DROP CONSTRAINT` |
| 13 | Console tool báo cờ không hợp lệ | Kafka 4.3 dùng `--formatter-property` / `--reader-property`; Kafka 4.1 dùng `--property` | Đọc `--help` |
| 14 | Summary đứng mãi PENDING | Event không bao giờ tới (SKU sai → DLT) | Read model chỉ biết những gì có event |

---

## 9. Câu hỏi tự kiểm

Tự trả lời trước, rồi mới mở đáp án.

<details><summary>1. Inventory đã đọc message, notification có đọc lại được không?</summary>

Được. Mỗi consumer group có offset riêng, message chỉ bị xóa khi hết retention.
</details>

<details><summary>2. Vì sao dùng orderId làm key?</summary>

Cùng key thì cùng partition, nên mọi event của 1 đơn giữ đúng thứ tự.
</details>

<details><summary>3. Scale payment-service lên 5 pod với 3 partition thì sao?</summary>

3 pod có partition, 2 pod ngồi không. Partition là giới hạn số consumer làm việc trong 1 group.
</details>

<details><summary>4. acks=all có nghĩa là chờ tất cả replica?</summary>

Không, chờ tất cả replica **trong ISR**. Kèm `min.insync.replicas` để quy định ISR tối thiểu được ghi.
</details>

<details><summary>5. Đã bật idempotence ở producer, consumer còn cần chống trùng không?</summary>

Có. Idempotence chỉ chống trùng do producer retry trong 1 phiên. Relay gửi lại sau khi restart, rebalance, chết trước khi commit offset... vẫn gây trùng.
</details>

<details><summary>6. Vì sao relay phải .join()?</summary>

Để chỉ đánh dấu `published_at` khi Kafka đã ack. Không chờ thì có thể đánh dấu một message chưa tới Kafka, và message đó mất.
</details>

<details><summary>7. Outbox giải quyết mất hay trùng? Inbox thì sao?</summary>

Outbox chống **mất** (có thể gây trùng). Inbox chống **trùng**. Ghép lại thì mỗi event có hiệu lực đúng 1 lần.
</details>

<details><summary>8. Message lỗi đi vào DLT nào, do đâu quyết định?</summary>

`DeadLetterPublishingRecoverer` mặc định: topic gốc + `-dlt`, cùng partition. Header ghi group nào lỗi và vì sao.
</details>

<details><summary>9. Khi nào exception nên NotRetryable?</summary>

Khi retry bao nhiêu lần kết quả vẫn vậy (dữ liệu sai): SKU không tồn tại, không có reservation, không có summary. Lỗi tạm thời (DB, optimistic lock) thì để retry.
</details>

<details><summary>10. Vì sao projector dùng group riêng và bảng inbox riêng?</summary>

Để reset offset hoặc dựng lại riêng mà không ảnh hưởng các consumer khác (Saga Tuần 10), và để `eventId` của group này không làm group kia bỏ qua nhầm.
</details>

<details><summary>11. Project có làm Event Sourcing không?</summary>

Không. Nguồn sự thật vẫn là bảng. Chỉ read model được dựng lại từ event, đó là mượn kỹ thuật replay. Hơn nữa Kafka chỉ giữ 7 ngày.
</details>

<details><summary>12. Vì sao summary của đơn SKU "fail" đứng mãi PENDING?</summary>

Inventory ném `ProductNotFoundException` → DLT, không phát event nào. Read model chỉ biết những gì có event.
</details>

<details><summary>13. Projector nhận PaymentCompleted trước InventoryReserved có sao không?</summary>

Không sao. Hai field độc lập, guard "chỉ đi tiến" và `@Version` đảm bảo kết quả cuối đúng. Thứ tự chỉ được đảm bảo trong 1 partition của 1 topic.
</details>

<details><summary>14. Vì sao không dựng dòng summary đầu tiên từ event OrderCreated?</summary>

Để POST xong GET được ngay (read-your-own-writes). Đi qua relay, Kafka rồi projector thì có thể bị 404 trong khoảng 1 giây đầu.
</details>

---

## 10. CLI cheat sheet

Mọi lệnh chạy trong pod broker. Kafka 4.3 dùng `--formatter-property` cho consumer và `--reader-property` cho producer.

### Topic
```bash
$K/kafka-topics.sh --bootstrap-server localhost:9092 --list
$K/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-events
$K/kafka-configs.sh --bootstrap-server localhost:9092 --entity-type topics --entity-name order-events --describe
```

### Đọc message (kèm key, partition, header)
```bash
$K/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-events --from-beginning \
  --timeout-ms 8000 --formatter-property print.key=true --formatter-property print.partition=true \
  --formatter-property print.headers=true
```

### Đọc DLT (xem lỗi ở header)
```bash
$K/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-events-dlt --from-beginning \
  --timeout-ms 8000 --formatter-property print.headers=true
```

### Gửi message bằng tay (key:value)
```bash
kubectl exec -it -n kafka jaka-dual-role-0 -- bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic order-events --reader-property parse.key=true --reader-property key.separator=:
```

### Consumer group, LAG, reset offset
```bash
$K/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
$K/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group inventory-service
# reset: group phải KHÔNG còn member; bỏ --execute để xem trước (dry run)
$K/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group order-summary-projector \
  --reset-offsets --to-earliest --topic inventory-events --topic payment-events --execute
```

### Strimzi
```bash
kubectl get kafka,kafkanodepool,kafkatopic -n kafka
kubectl apply -f jaka_infrastructure/k8s/kafka/topics.yml
```

---

## 11. Sau Tuần 9

| Chủ đề | Liên quan tới gì đã học |
|---|---|
| Tuần 10: Saga (orchestration vs choreography), timeout, `OrderCancelled` | Dùng lại `processed_events` của order-service, group `order-service` |
| Exactly-once của Kafka (transactional producer, `read_committed`) | Chỉ áp dụng cho Kafka → Kafka; DB → Kafka vẫn cần Outbox |
| Debezium (CDC) thay relay polling | Đọc WAL của Postgres để gửi outbox, không cần `@Scheduled` |
| Schema Registry (Avro / Protobuf) | Thay quy ước "chỉ thêm field" bằng công cụ kiểm tra tương thích |
| KEDA scale theo LAG | Dùng LAG (Tầng 1.6) làm chỉ số autoscale |
