# System Design 2 — Event-Driven & CQRS

Thiết kế giao tiếp giữa 4 service (order, inventory, payment, notification) bằng Kafka: kiểu event được chọn, ưu/nhược trong hệ thống thật, cách theo dấu 1 đơn qua 4 service, và read model CQRS cho màn hình chi tiết đơn. Hợp đồng từng event: [event-catalog.md](event-catalog.md).

## 1. Sơ đồ luồng event

```
                         ┌──────────────────────── Kafka ────────────────────────┐
POST /api/orders         │                                                        │
   │                     │                                                        │
order-svc ──OrderCreated─┼─► order-events ──┬──► inventory-svc: reserve           │
 (outbox)                │                  └──► notification-svc: "Đơn đã tạo"   │
                         │                                                        │
inventory-svc ───────────┼─► inventory-events ──┬──► payment-svc: charge (chỉ InventoryReserved)
 (outbox)                │  Reserved/Rejected/  ├──► notification-svc: "Hết hàng" (chỉ Rejected)
                         │  Released            └──► order-svc: projector order_summary (CQRS)
                         │                                                        │
payment-svc ─────────────┼─► payment-events ──┬──► inventory-svc: commit / release│
 (outbox)                │  Completed/Failed  ├──► notification-svc: "Thanh toán ..."
                         │                    └──► order-svc: projector order_summary (CQRS)
                         │                                                        │
                         │  *-dlt: message lỗi sau khi hết retry (giữ 30 ngày)     │
                         └────────────────────────────────────────────────────────┘
```

Mọi service phát event qua **Transactional Outbox** (ghi dữ liệu + event trong 1 transaction, relay đẩy lên Kafka sau). Mọi consumer **idempotent** theo `eventId`, retry có giới hạn, rồi đẩy vào DLT.

## 2. Ba kiểu event — hệ thống dùng Event-Carried State Transfer

| | Event Notification | Event-Carried State Transfer (ECST) | Event Sourcing |
|---|---|---|---|
| Payload | Chỉ ID | Đủ dữ liệu consumer cần | Thay đổi của từng bước |
| Consumer gọi ngược REST? | Có | Không | Không |
| Phụ thuộc lúc chạy | Cao | Thấp | Thấp |
| Dữ liệu gốc nằm ở | Bảng của producer | Bảng của producer | **Chuỗi event** |
| Độ khó | Dễ | Vừa | Khó |
| Dùng khi | Dữ liệu lớn/nhạy cảm | Mặc định cho microservices | Cần audit đầy đủ (ngân hàng, kế toán) |
| Trong hệ thống này | — | ✅ cả 6 event | — |

**Vì sao chọn ECST:**
- Consumer có đủ dữ liệu, **không gọi ngược** service khác. Ví dụ: `OrderCreated` mang `productSku`, `quantity` nên inventory không cần hỏi order-service; `InventoryReserved` chép `totalAmount`, `currency`, `customerName` từ `OrderCreated` vì payment-service không đọc `order-events`.
- Hệ quả: order-service tắt thì inventory vẫn xử lý được event đã nằm trong Kafka.
- Giá phải trả: event to hơn, dữ liệu bị chép ra nhiều nơi (bản chép có thể cũ), đổi schema phải theo luật "chỉ thêm field".

## 3. Ưu / nhược của event-driven — trong hệ thống thật

| | Ý nghĩa | Thấy ở đâu |
|---|---|---|
| ✅ Tách rời | Producer không biết ai nghe | Thêm notification-service mà **không sửa dòng nào** của order-service |
| ✅ Scale độc lập | Service chậm thì tăng pod riêng | inventory chậm → tăng replica inventory; số consumer tối đa = số partition (3) |
| ✅ Chịu lỗi | Consumer chết, event chờ trong Kafka | Scale notification về 0 → đặt đơn vẫn chạy; scale lên lại → mail được gửi từ offset cũ |
| ❌ Khó debug | 1 đơn đi qua 4 service, 3 topic, log rải rác | Đơn kẹt — kẹt ở service nào? → mục 4 (Correlation ID) |
| ❌ Lỗi im lặng | Message lỗi vào DLT, log không có ERROR nếu không cấu hình | Thiếu `sentAt` ở notification: 3 đơn vào `order-events-dlt` mà log không báo → thêm `RetryListener` để log `WARN`/`ERROR` |
| ❌ Eventual consistency | Các service khớp nhau **sau một lúc** | Broker chết 30s: đơn có trong DB, payment nhận event sau 30s |
| ❌ Thứ tự chỉ trong 1 partition | Khác topic thì không có thứ tự | `PaymentCompleted` có thể đến projector trước `InventoryReserved` → mục 5 (guard "chỉ đi tiến") |

## 4. Correlation ID — theo dấu 1 đơn qua 4 service

### 3 loại ID

| ID | Ý nghĩa | Thay đổi khi nào |
|---|---|---|
| `eventId` | Định danh **1 event** — chống trùng | Mỗi event mới |
| `aggregateId` | Event nói về **đơn** nào — Kafka key | Không đổi |
| `correlationId` | **1 yêu cầu gốc** của người dùng — để trace | Không đổi trong cả chuỗi |

### Đường đi

```
curl -H "X-Correlation-Id: demo-cid-001"
  │
order-svc  CorrelationIdFilter → MDC["correlationId"]
  │        OutboxWriter đọc MDC → outbox.correlation_id
  │        OutboxRelay → Kafka header correlationId
  ▼
order-events ─► inventory-svc  CorrelationIdInterceptor: header → MDC
                   │  OutboxWriter → outbox.correlation_id → header
                   ▼
                inventory-events ─► payment-svc ─► ... (lặp lại)
                                 ─► notification-svc
```

- **MDC** là 1 `Map` gắn với thread hiện tại; Logback in nó vào mỗi dòng log qua `logging.pattern.level: "%5p [cid=%X{correlationId:-}]"`.
- Không có header (event cũ, hoặc tạo từ job nội bộ) → `OutboxWriter` sinh UUID mới.
- DLT giữ nguyên header gốc → message lỗi vẫn biết thuộc request nào.

### Bằng chứng

> _Dán output thật sau khi chạy chứng minh Phần B: `grep 'cid=demo-cid-001'` trên log của cả 4 service, và `print.headers=true` trên 3 topic._

```
(chưa chạy)
```

### Quan hệ với tracing
Đây là bản làm tay của distributed tracing. Micrometer Tracing / OpenTelemetry (dự kiến tháng 4) tự truyền `traceId` qua HTTP và Kafka header, kèm giao diện xem đường đi; khi đó bỏ code `MDC.put/remove` tự viết để 2 bên không ghi đè nhau.

## 5. CQRS: read model `order_summary`

> Trạng thái: **thiết kế, chưa triển khai.**

### Bài toán
Màn hình "Chi tiết đơn" cần thông tin đơn + kho đã giữ chưa + đã thanh toán chưa — nằm ở 3 database khác nhau.

| Cách | Mô tả | Đánh giá |
|---|---|---|
| API Composition | `GET /orders/12/summary` gọi REST sang inventory và payment lúc đọc | ❌ 1 service chết → màn hình lỗi; chậm bằng service chậm nhất; dồn tải đọc sang service khác |
| JOIN 3 database | Đọc thẳng DB của service khác | ❌ Phá database-per-service |
| **CQRS** | Projector trong order-service nghe `inventory-events` + `payment-events`, ghi bảng `order_summary` trong orderdb | ✅ 1 câu `SELECT`, không gọi ai · ❌ có thể trễ vài giây |

### Write model và read model

| | Write model | Read model |
|---|---|---|
| Bảng | `orders` | `order_summary` |
| Thiết kế cho | Đúng nghiệp vụ, chuẩn hóa | Đọc nhanh cho 1 màn hình, phi chuẩn hóa |
| Ai ghi | `OrderService.create` | Dòng đầu tạo cùng transaction với đơn; sau đó `OrderSummaryProjector` (group `order-summary-projector`) |
| Sai/mất thì sao | Mất dữ liệu thật | Xóa đi, dựng lại từ event (reset offset projector) |

### Trạng thái

| Cột | Giá trị |
|---|---|
| `inventoryStatus` | `PENDING` → `RESERVED` / `REJECTED`; `RESERVED` → `RELEASED` |
| `paymentStatus` | `PENDING` → `COMPLETED` / `FAILED`; `SKIPPED` khi hết hàng |
| `overallStatus` (tính lúc đọc) | `OUT_OF_STOCK` · `PAYMENT_FAILED` · `COMPLETED` · còn lại `PROCESSING` |

| Kịch bản | `inventoryStatus` | `paymentStatus` | `overallStatus` |
|---|---|---|---|
| Thành công | `RESERVED` | `COMPLETED` | `COMPLETED` |
| Hết hàng | `REJECTED` | `SKIPPED` | `OUT_OF_STOCK` |
| Thẻ bị từ chối | `RELEASED` | `FAILED` | `PAYMENT_FAILED` |

### Vì sao cần guard "chỉ đi tiến" và `@Version`
- **Thứ tự:** projector đọc 2 topic, giữa 2 topic không có thứ tự. Relay còn có thể gửi trùng. Guard: mỗi trạng thái chỉ được chuyển tiếp từ trạng thái trước nó (ví dụ `RESERVED` chỉ áp khi đang `PENDING`) → bản trùng đến muộn không kéo trạng thái lùi lại.
- **Ghi song song:** event của 2 topic có thể vào 2 pod khác nhau và cùng `UPDATE` 1 dòng. `@Version` làm pod commit sau bị `ObjectOptimisticLockingFailureException` → retry → đọc lại dòng mới → không mất cập nhật.
- **Chống trùng:** bảng `projected_events` riêng của projector (lớp 1), guard trạng thái (lớp 2).

### Quan hệ với Saga (tuần 10)
Saga thêm listener thứ 2 trong order-service (group `order-service`) đọc cùng 2 topic nhưng ghi `orders.status` (write model) và phát `OrderConfirmed`/`OrderCancelled`. 2 group độc lập = fan-out. Sau đó trạng thái trong `order_summary` lặp một phần `orders.status` — chấp nhận được: read model chép dữ liệu cho tiện đọc, nguồn sự thật vẫn là `orders`.

## 6. Khi nào không nên dùng CQRS

| Tình huống | Nên | Lý do |
|---|---|---|
| CRUD đơn giản (danh mục, cấu hình) | Không CQRS | Thêm projector, bảng, DLT chỉ để đọc lại đúng cái vừa ghi |
| Cần dữ liệu **ngay và chính xác 100%** (số dư khi chuyển khoản) | Đọc từ write model | Read model có thể trễ |
| Team nhỏ, ít người quen event-driven | Cân nhắc kỹ | Debug khó hơn nhiều |
| Query chéo nhiều service, đọc nhiều hơn ghi rất nhiều | ✅ CQRS | Đúng bài toán `order_summary` |
| Đọc và ghi cần scale khác nhau | ✅ CQRS | Read model có thể nằm ở DB khác (Elasticsearch, Redis) |

CQRS không bắt buộc Event Sourcing, cũng không bắt buộc 2 database — ở đây read model nằm chung orderdb.

## 7. Câu hỏi system design

### 7.1. Làm sao đảm bảo không mất event?

Xét từng chặng event đi qua:

```
[DB order]──①──►[outbox]──②──►[Kafka]──③──►[consumer]──④──►[DB inventory]
                                                   └──⑤──► DLT
```

| Chặng | Rủi ro | Cách chống |
|---|---|---|
| ① Ghi đơn + ghi event | Ghi DB xong, gửi Kafka lỗi | Outbox: cùng 1 transaction |
| ② Relay → Kafka | Kafka chết, mạng lỗi | Dòng outbox còn `published_at IS NULL` → lần poll sau gửi lại; `.join()` chờ broker xác nhận mới đánh dấu |
| ② Broker nhận | Ack mất → producer gửi lại → trùng | `acks: all` + `enable.idempotence: true` |
| Lưu trên Kafka | Mất broker | Replication. Trên kind `replicas: 1` → mất broker là mất dữ liệu; production: `replicas: 3`, `min.insync.replicas: 2` |
| ③ Consumer | Commit offset trước khi xử lý xong | Spring Kafka commit offset **sau khi** listener chạy xong không lỗi |
| ④ Xử lý lỗi | Exception | Rollback + retry có backoff |
| ⑤ Hết retry | Bỏ message | Không bỏ: vào DLT, giữ 30 ngày để điều tra và gửi lại |

Kết luận: giữa nhiều hệ thống không có exactly-once thật sự → chọn **at-least-once** (không mất, có thể trùng) → consumer bắt buộc chống trùng (7.2).

### 7.2. Consumer nhận trùng thì sao?

Trùng đến từ: relay gửi xong nhưng chưa kịp đánh dấu; consumer xử lý xong nhưng chưa kịp commit offset (pod bị kill, rebalance).

| Lớp | Cách | Trong hệ thống |
|---|---|---|
| 1. Theo `eventId` | Bảng chống trùng, insert **cùng transaction** với nghiệp vụ | `processed_events` (inventory, payment); `notifications.event_id` unique (notification) |
| 2. Theo nghiệp vụ | Kiểm tra trạng thái trước khi làm | `commit`/`release` chỉ khi reservation `RESERVED`; `charge` kiểm tra `existsByOrderId` |
| 3. Thao tác tự nhiên idempotent | "Gán" thay vì "cộng" khi có thể | `status = COMMITTED` làm 2 lần vẫn vậy; `available - 2` làm 2 lần thì sai → cần lớp 1, 2 |

Bằng chứng: gửi cùng `eventId` 3 lần → kho giảm đúng 1 (`TEST-001`: 100 → 99), `processed_events` có 1 dòng, chỉ 1 `InventoryReserved` + 1 `PaymentCompleted`.

Riêng email (side effect ra ngoài, không rollback được): ghi `notifications` + `flush()` **trước**, gửi mail **sau** → bản trùng bị unique constraint chặn trước khi gửi; chỉ còn trường hợp hiếm "mail đã gửi nhưng commit lỗi" là có thể gửi 2 lần.

### 7.3. Eventual consistency ảnh hưởng UX thế nào?

Vừa POST xong, đơn chưa có kết quả kho và thanh toán. Hiện "Đặt hàng thành công" ngay là có thể sai (hết hàng, thẻ bị từ chối).

| Cách xử lý | Trong hệ thống |
|---|---|
| Trạng thái trung gian rõ ràng | `overallStatus = PROCESSING` → UI hiện "Đang xử lý đơn hàng..." |
| Read-your-own-writes | Dòng `order_summary` tạo cùng transaction với đơn → GET ngay không bị 404 |
| Client polling 1–2s đến khi hết `PROCESSING` | `GET /api/orders/{id}/summary` |
| Server đẩy về (SSE/WebSocket) | Chưa làm |
| Thông báo bất đồng bộ | Email từ notification-service |
| `202 Accepted` thay vì `201 Created` | Đúng nghĩa hơn ("đã nhận, đang xử lý"); hiện giữ `201` vì đơn đã được ghi thật |
| Timeout | Đơn kẹt quá lâu → Saga tuần 10 hủy và trả kho |
