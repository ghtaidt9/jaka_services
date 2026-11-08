# Event Catalog

Danh mục mọi event giữa 4 service: order, inventory, payment, notification. Đây là **hợp đồng** giữa các service: mỗi service tự copy record của event nó dùng vào `dto/` của mình, không dùng thư viện chung. Đổi hợp đồng thì sửa file này trước.

## 1. Tổng quan

| Event | Topic | Producer | Consumer (group) | Ý nghĩa |
|---|---|---|---|---|
| `OrderCreated` | `order-events` | order-service | inventory-service, notification-service | Đơn mới đã được lưu (`PENDING`) |
| `OrderConfirmed` | `order-events` | order-service | — (notification bỏ qua) | Saga thành công: đơn `CONFIRMED` |
| `OrderCancelled` | `order-events` | order-service | inventory-service | Đơn `CANCELLED` (hết hàng, thanh toán lỗi, hoặc timeout) — inventory release nếu còn giữ hàng |
| `RefundRequested` | `order-events` | order-service | payment-service | `PaymentCompleted` đến sau khi đơn đã huỷ (timeout) → yêu cầu hoàn tiền |
| `InventoryReserved` | `inventory-events` | inventory-service | payment-service, order-service, order-summary-projector | Kho đã giữ hàng cho đơn |
| `InventoryRejected` | `inventory-events` | inventory-service | order-service, order-summary-projector, notification-service | Không đủ hàng, đơn không thể tiếp tục |
| `InventoryReleased` | `inventory-events` | inventory-service | order-summary-projector | Hàng đã giữ được trả lại kho (bù trừ sau `PaymentFailed` / `OrderCancelled`) |
| `PaymentCompleted` | `payment-events` | payment-service | inventory-service, order-service, order-summary-projector, notification-service | Đã thu tiền |
| `PaymentFailed` | `payment-events` | payment-service | inventory-service, order-service, order-summary-projector, notification-service | Thanh toán bị từ chối |

Mỗi consumer group chỉ nhận event của topic nó nghe; event không cần xử lý thì bỏ qua (không throw):

| Consumer group | Nghe topic | Xử lý | Bỏ qua |
|---|---|---|---|
| `order-service` (Saga) | `inventory-events`, `payment-events` | `InventoryReserved` → `AWAITING_PAYMENT` · `InventoryRejected`, `PaymentFailed` → `CANCELLED` + `OrderCancelled` · `PaymentCompleted` → `CONFIRMED` + `OrderConfirmed` (đơn đã `CANCELLED` → `RefundRequested`) | `InventoryReleased` |
| `order-summary-projector` | `inventory-events`, `payment-events` | Mọi event → cập nhật read model `order_summary` | — |
| `inventory-service` | `order-events`, `payment-events` | `OrderCreated` → reserve · `PaymentCompleted` → commit · `PaymentFailed`, `OrderCancelled` → release | `OrderConfirmed`, `RefundRequested` |
| `payment-service` | `inventory-events`, `order-events` | `InventoryReserved` → charge · `RefundRequested` → refund | `InventoryRejected`, `InventoryReleased`, các event `Order*` |
| `notification-service` | cả 3 topic | `OrderCreated`, `InventoryRejected`, `PaymentCompleted`, `PaymentFailed` → gửi mail | `InventoryReserved`, `InventoryReleased`, `OrderConfirmed`, `OrderCancelled`, `RefundRequested` |

## 2. Luồng

### 2a. Luồng chính

```
order-svc ──OrderCreated──► order-events ──► inventory-svc: reserve
                                              ├─ đủ hàng    → InventoryReserved ─► inventory-events ──► payment-svc: charge
                                              │                                                       ├─ OK  → PaymentCompleted ─► payment-events ──► inventory-svc: commit
                                              │                                                       └─ lỗi → PaymentFailed    ─► payment-events ──► inventory-svc: release
                                              │                                                                                                        └─ InventoryReleased ─► inventory-events
                                              └─ thiếu hàng → InventoryRejected ─► inventory-events

notification-svc nghe cả 3 topic và gửi mail cho OrderCreated, InventoryRejected, PaymentCompleted, PaymentFailed
```

### 2b. Saga ở order-svc

```
                         ┌─ InventoryReserved ─► AWAITING_PAYMENT
inventory-events ──┐     ├─ InventoryRejected ─► CANCELLED ─► OrderCancelled
                   ├──► order-svc
payment-events  ───┘     ├─ PaymentFailed     ─► CANCELLED ─► OrderCancelled
                         └─ PaymentCompleted ─┬─ đang AWAITING_PAYMENT ─► CONFIRMED ─► OrderConfirmed
                                              └─ đã CANCELLED (timeout) ─► RefundRequested ─► payment-svc: refund

job timeout (5 phút, AWAITING_PAYMENT) ─► CANCELLED (PAYMENT_TIMEOUT) ─► OrderCancelled ─► inventory-svc: release
```

## 3. Quy ước chung

### Envelope
Mọi event có cùng vỏ `EventEnvelope<T>`, payload nằm trong `payload`:

| Field | Kiểu | Ý nghĩa |
|---|---|---|
| `eventId` | String (UUID) | Định danh duy nhất của event — **khóa chống trùng** (`processed_events`, `notifications.event_id`) |
| `eventType` | String | Tên event, ví dụ `OrderCreated` — consumer `switch` theo field này |
| `version` | int | Phiên bản schema, hiện tại `1` |
| `aggregateId` | String | **orderId** — luôn là id của đơn, kể cả event của inventory và payment |
| `occurredAt` | ISO-8601 instant (UTC) | Thời điểm event được tạo — thời gian duy nhất của event (payload không có field thời gian) |
| `payload` | object | Dữ liệu của event, xem mục 4 |

### Kafka record
| Phần | Giá trị |
|---|---|
| Key | `aggregateId` (orderId) → mọi event của 1 đơn vào cùng partition, đúng thứ tự |
| Value | Envelope dạng JSON string (producer `StringSerializer`, consumer `StringDeserializer` + `JacksonJsonMessageConverter`) |
| Header `correlationId` | Mã theo dấu 1 request qua mọi service (có thể thiếu với event cũ) |
| Header `eventType` | Giống `eventType` trong envelope — để lọc mà không cần parse JSON |

### Topic
Tất cả: 3 partition, replication factor 1 (kind). Khai báo trong `jaka_infrastructure/k8s/kafka/topics.yml`.

| Topic | Retention | Ghi chú |
|---|---|---|
| `order-events`, `inventory-events`, `payment-events` | 7 ngày | |
| `order-events-dlt`, `inventory-events-dlt`, `payment-events-dlt` | 30 ngày | Message lỗi sau khi hết retry; header `kafka_dlt-original-consumer-group`, `kafka_dlt-exception-message` cho biết group nào lỗi và vì sao |

### Schema evolution
- Chỉ **thêm** field mới (consumer cũ bỏ qua field lạ — Jackson 3 mặc định không báo lỗi).
- Không đổi tên, không đổi kiểu, không xóa field; không đổi nghĩa của `eventType`.
- Thay đổi phá vỡ → tăng `version`, consumer xử lý cả 2 version trong thời gian chuyển tiếp.

## 4. Chi tiết từng event

### `OrderCreated`
Producer: order-service, trong cùng transaction với `INSERT orders` (outbox).

| Field | Kiểu | Ghi chú |
|---|---|---|
| `orderId` | long | |
| `orderNumber` | string | Mã hiển thị cho khách, ví dụ `ORD-3F9A1C2E` |
| `customerName` | string | |
| `totalAmount` | decimal | |
| `currency` | string | 3 ký tự, ví dụ `USD` |
| `productSku` | string | |
| `quantity` | int | |

```json
{
  "eventId": "f2b75329-dbc4-496b-a93f-0dc78b7192fc",
  "eventType": "OrderCreated",
  "version": 1,
  "aggregateId": "9",
  "occurredAt": "2026-10-30T13:01:22.188076878Z",
  "payload": {
    "orderId": 9,
    "orderNumber": "ORD-3F9A1C2E",
    "customerName": "Tai",
    "totalAmount": 20,
    "currency": "USD",
    "productSku": "TEST-001",
    "quantity": 2
  }
}
```

### `InventoryReserved`
Producer: inventory-service, khi đủ hàng (`available -= quantity`, `reserved += quantity`, reservation `RESERVED`). Mang theo `totalAmount`, `currency`, `customerName` vì payment-service chỉ nghe `inventory-events`.

| Field | Kiểu |
|---|---|
| `orderId` | long |
| `productSku` | string |
| `quantity` | int |
| `customerName` | string |
| `totalAmount` | decimal |
| `currency` | string |

```json
{
  "eventId": "8c1d0e6a-2b3f-4c7e-9a10-5f2e6b7c8d90",
  "eventType": "InventoryReserved",
  "version": 1,
  "aggregateId": "9",
  "occurredAt": "2026-10-30T13:01:23.402Z",
  "payload": {
    "orderId": 9,
    "productSku": "TEST-001",
    "quantity": 2,
    "customerName": "Tai",
    "totalAmount": 20,
    "currency": "USD"
  }
}
```

### `InventoryRejected`
Producer: inventory-service, khi `available < quantity`. Không thay đổi kho, không tạo reservation. SKU không tồn tại **không** sinh event này — đó là lỗi dữ liệu, message đi thẳng vào `order-events-dlt`.

| Field | Kiểu | Ghi chú |
|---|---|---|
| `orderId` | long | |
| `productSku` | string | |
| `quantity` | int | |
| `customerName` | string | |
| `totalAmount` | decimal | |
| `reason` | string | Hiện tại `Insufficient stock` |

```json
{
  "eventId": "3e9b7a52-6d41-4f0c-8b2a-1c7d9e0f4a63",
  "eventType": "InventoryRejected",
  "version": 1,
  "aggregateId": "10",
  "occurredAt": "2026-10-30T13:01:25.118Z",
  "payload": {
    "orderId": 10,
    "productSku": "SOLD-OUT-001",
    "quantity": 2,
    "customerName": "Tai",
    "totalAmount": 20,
    "reason": "Insufficient stock"
  }
}
```

### `InventoryReleased`
Producer: inventory-service, sau `PaymentFailed` (bù trừ: `reserved -= quantity`, `available += quantity`, reservation `RELEASED`). Chỉ phát khi reservation đang `RESERVED` — nhận `PaymentFailed` lần 2 không phát lại.

| Field | Kiểu | Ghi chú |
|---|---|---|
| `orderId` | long | |
| `productSku` | string | |
| `quantity` | int | |
| `customerName` | string | |
| `reason` | string | Chép từ `PaymentFailed.reason`, ví dụ `CARD_DECLINED` |

```json
{
  "eventId": "b47f2c18-0e9d-4a35-a6c2-7d8e1f3b5c04",
  "eventType": "InventoryReleased",
  "version": 1,
  "aggregateId": "11",
  "occurredAt": "2026-10-30T13:01:28.774Z",
  "payload": {
    "orderId": 11,
    "productSku": "TEST-001",
    "quantity": 2,
    "customerName": "fail",
    "reason": "CARD_DECLINED"
  }
}
```

### `PaymentCompleted`
Producer: payment-service, khi thanh toán được chấp nhận (giả lập: `totalAmount ≤ 1000` và `customerName ≠ "fail"`). Mỗi order chỉ thu 1 lần (`existsByOrderId`).

| Field | Kiểu | Ghi chú |
|---|---|---|
| `orderId` | long | |
| `paymentReference` | string | Mã giao dịch, ví dụ `PAY-7B2E9D41` |
| `amount` | decimal | |
| `currency` | string | |
| `customerName` | string | |

```json
{
  "eventId": "5a0c9e27-71b4-4d86-bf3e-2e6a8d1c9f15",
  "eventType": "PaymentCompleted",
  "version": 1,
  "aggregateId": "9",
  "occurredAt": "2026-10-30T13:01:24.051Z",
  "payload": {
    "orderId": 9,
    "paymentReference": "PAY-7B2E9D41",
    "amount": 20,
    "currency": "USD",
    "customerName": "Tai"
  }
}
```

### `PaymentFailed`
Producer: payment-service, khi thanh toán bị từ chối. Từ chối là kết quả nghiệp vụ, không phải lỗi: `Payment` vẫn được lưu với trạng thái `FAILED`.

| Field | Kiểu | Ghi chú |
|---|---|---|
| `orderId` | long | |
| `amount` | decimal | |
| `currency` | string | |
| `customerName` | string | |
| `reason` | string | Hiện tại `CARD_DECLINED` |

```json
{
  "eventId": "e8d3b6f1-4c2a-49e7-8f05-9b1a7c6d2e38",
  "eventType": "PaymentFailed",
  "version": 1,
  "aggregateId": "11",
  "occurredAt": "2026-10-30T13:01:27.963Z",
  "payload": {
    "orderId": 11,
    "amount": 20,
    "currency": "USD",
    "customerName": "fail",
    "reason": "CARD_DECLINED"
  }
}
```

## 5. Record Java theo service

Mỗi service copy đúng các record nó gửi hoặc nhận, trong `dto/`:

| Record | order | inventory | payment | notification |
|---|---|---|---|---|
| `EventEnvelope<T>` | ✅ | ✅ | ✅ | ✅ |
| `OrderCreated` | gửi | nhận | — | nhận |
| `InventoryReserved` | — | gửi | nhận | — |
| `InventoryRejected` | — | gửi | — | nhận |
| `InventoryReleased` | — | gửi | — | — |
| `PaymentCompleted` | — | nhận | gửi | nhận |
| `PaymentFailed` | — | nhận | gửi | nhận |

## 6. Kế hoạch (tuần 10)

Saga thêm vào danh mục — cập nhật file này khi làm:
- order-service nghe `inventory-events` + `payment-events` (group `order-service`) để cập nhật `orders.status`
- Event mới trên `order-events`: `OrderConfirmed`, `OrderCancelled` (inventory release khi nhận `OrderCancelled`, ví dụ timeout)
