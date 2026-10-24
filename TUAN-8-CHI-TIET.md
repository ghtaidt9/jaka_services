# Tuần 8 chi tiết — Helm, Kafka trên Kubernetes & System Design (Phần 1)

> Giai đoạn gộp Tháng 2 + 3 (Tuần 5–10). **Không cần AWS** — chạy trên kind.

## Bối Cảnh & Mục Tiêu

**Đã hoàn thành:**
- ✅ Tuần 5: Terraform module + backend + test + CI
- ✅ Tuần 6–7: 2 service + Postgres + Redis trên kind, Gateway API, probes, HPA

**Vấn đề sau tuần 7:**
- ❌ ~20 file YAML, copy-paste giữa order và payment, chỉ khác tên/port
- ❌ Muốn tạo môi trường prod → copy cả thư mục, sửa tay replicas/resources
- ❌ Không có "phiên bản" cho cả bộ manifest → khó rollback nguyên release
- ❌ 2 service gọi nhau đồng bộ (REST) → payment chết thì order cũng lỗi

**Tuần 8 mục tiêu:**
- Đóng gói 2 service thành **1 Helm chart dùng chung**, values riêng cho dev/prod
- Cài Kafka trên kind bằng **Strimzi operator**
- Gửi/nhận message đầu tiên từ Spring Boot
- System Design phần 1: REST vs gRPC, Load Balancer, Scalability, Caching, CAP

---

## Tại Sao Cần Tuần 8?

```
Không có Helm:
  k8s/order-service/deployment.yaml     ┐
  k8s/payment-service/deployment.yaml   ┘ giống nhau 90%
  ❌ Sửa probe → sửa 2 chỗ (4 chỗ khi có 4 service ở tuần 9)

Có Helm:
  charts/spring-service/templates/deployment.yaml   (1 template)
  values-order.yaml, values-payment.yaml            (chỉ phần khác)
  ✅ helm upgrade --install / helm rollback theo release

Không có Kafka:
  order ──REST──► payment    (payment chết → order lỗi 500)
Có Kafka:
  order ──event──► Kafka ──► payment / inventory / notification
  ✅ Tách rời thời gian, service chết vẫn nhận event khi sống lại
```

---

## Chủ Đề 1: Helm

### Nội Dung Phải Học

- **Chart**: gói template + `values.yaml` + `Chart.yaml`
- **Release**: 1 lần cài chart vào cluster (`helm install order ./chart`) — có revision, rollback được
- **Template Go**: `{{ .Values.image.tag }}`, `{{ include "x.labels" . }}`, `if`, `range`, `default`, `required`
- **`_helpers.tpl`**: định nghĩa tên, label dùng chung
- **Values layering**: `values.yaml` (mặc định) ← `-f values-prod.yaml` ← `--set image.tag=v2`
- **Lệnh debug**: `helm lint`, `helm template` (render ra YAML xem trước), `--dry-run`
- **Dependencies**: chart phụ thuộc chart khác (`Chart.yaml` → `dependencies`)
- **Helm vs Kustomize**: Helm = template + package + release; Kustomize = patch YAML thuần. Biết cả 2, dùng Helm cho app

### Cấu Trúc Tiêu Chuẩn

```
charts/spring-service/
├── Chart.yaml
├── values.yaml                 # mặc định chung
├── templates/
│   ├── _helpers.tpl
│   ├── deployment.yaml         # probes, resources, envFrom
│   ├── service.yaml
│   ├── configmap.yaml
│   ├── hpa.yaml                # {{- if .Values.autoscaling.enabled }}
│   └── httproute.yaml
└── values/
    ├── order-dev.yaml
    ├── order-prod.yaml
    ├── payment-dev.yaml
    └── payment-prod.yaml
```

```yaml
# charts/spring-service/values.yaml
image:
  repository: ""
  tag: "v1"
  pullPolicy: IfNotPresent
port: 8080
contextPath: ""        # để "" chứ không phải "/" — template nối "{{ contextPath }}/actuator/..." sẽ ra "//actuator"
replicaCount: 2
resources:
  requests: { cpu: 250m, memory: 512Mi }
  limits:   { memory: 768Mi }
autoscaling:
  enabled: false
  minReplicas: 2
  maxReplicas: 6
  targetCPU: 50
env: {}
secretName: ""
```

```yaml
# charts/spring-service/values/order-dev.yaml
image: { repository: order-service }
port: 8081
contextPath: /api/orders
env:
  SPRING_DATASOURCE_URL: jdbc:postgresql://order-db:5432/orderdb
  SPRING_DATASOURCE_USERNAME: order_user
  SPRING_DATA_REDIS_HOST: order-redis
  KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092   # Chủ đề 3 — khác namespace nên phải có ".kafka"
secretName: order-service-secret
```

> **Tên resource do `spring-service.fullname` trong `templates/_helpers.tpl` quyết định** — mọi template dùng `name: {{ include "spring-service.fullname" . }}`. Bản mặc định do `helm create` sinh ra ghép **`<release>-<chart>`**: `helm install order-service ...` → Deployment/Service tên **`order-service-spring-service`** → DNS đổi, HTTPRoute tuần 7 (`backendRefs: order-service`) và `SPRING_DATASOURCE_URL` của service khác gãy. Muốn giữ tên cũ, chọn 1:
>
> ```yaml
> # Cách 1 — _helpers.tpl: fullname = đúng tên release (đơn giản nhất cho chart dùng chung)
> {{- define "spring-service.fullname" -}}
> {{- .Release.Name | trunc 63 | trimSuffix "-" }}
> {{- end }}
> ```
>
> ```yaml
> # Cách 2 — giữ helper mặc định, ghi đè trong values/order-dev.yaml
> fullnameOverride: order-service
> ```
>
> Rồi đặt tên release là **`order-service`** / **`payment-service`**. Kiểm tra bằng `helm template order-service charts/spring-service -f ... | Select-String "name:"` trước khi install.

> ⚠️ **Tên file bắt buộc**: Helm chỉ nhận đúng `Chart.yaml` và `values.yaml` (đuôi `.yml` bị **bỏ qua** → render lỗi `nil pointer evaluating ...`). File trong `templates/` và `values/` thì `.yml` hay `.yaml` đều được.

```yaml
# charts/spring-service/Chart.yaml — bắt buộc phải có
apiVersion: v2
name: spring-service
description: Shared chart for the Spring Boot services
type: application
version: 0.1.0
appVersion: "v1"
```

```yaml
# charts/spring-service/templates/_helpers.tpl — tên resource = tên release (xem ghi chú ở trên)
{{- define "spring-service.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
```

Template dưới đây là **bản đầy đủ** — gộp mọi thứ đã làm tay ở tuần 6–7 (labels/selector, rolling update, resources, probes, env, preStop). Mỗi `{{ ... }}` là 1 chỗ trống Helm điền từ values:

```yaml
# charts/spring-service/templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "spring-service.fullname" . }}            # → order-service
  labels: { app: {{ include "spring-service.fullname" . }} } # → app: order-service (như tuần 6)
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}                       # có HPA thì bỏ replicas (tuần 7)
  {{- end }}
  selector:
    matchLabels: { app: {{ include "spring-service.fullname" . }} }
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
  template:
    metadata:
      labels: { app: {{ include "spring-service.fullname" . }} }
      annotations:
        # ConfigMap đổi → checksum đổi → Pod tự rolling restart (tuần 6: đổi ConfigMap KHÔNG tự restart)
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    spec:
      containers:
        - name: app
          image: "{{ required "image.repository là bắt buộc" .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.port }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          envFrom:
            - configMapRef: { name: {{ include "spring-service.fullname" . }} }
            {{- with .Values.secretName }}
            - secretRef: { name: {{ . }} }
            {{- end }}
          env:
            - { name: JAVA_TOOL_OPTIONS, value: "-XX:MaxRAMPercentage=75" }
            - { name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED, value: "true" }
            - { name: MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE, value: "readinessState,db" }
          startupProbe:
            httpGet: { path: {{ .Values.contextPath }}/actuator/health/liveness, port: {{ .Values.port }} }
            periodSeconds: 5
            failureThreshold: 24
          readinessProbe:
            httpGet: { path: {{ .Values.contextPath }}/actuator/health/readiness, port: {{ .Values.port }} }
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: {{ .Values.contextPath }}/actuator/health/liveness, port: {{ .Values.port }} }
            periodSeconds: 10
            failureThreshold: 3
          lifecycle:
            preStop:
              sleep: { seconds: 5 }
```

```yaml
# charts/spring-service/templates/configmap.yaml — biến khối env: trong values thành ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "spring-service.fullname" . }}
data:
  SERVER_PORT: {{ .Values.port | quote }}
  {{- range $key, $value := .Values.env }}
  {{ $key }}: {{ $value | quote }}
  {{- end }}
```

```yaml
# charts/spring-service/templates/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "spring-service.fullname" . }}
spec:
  type: ClusterIP
  selector: { app: {{ include "spring-service.fullname" . }} }
  ports:
    - port: {{ .Values.port }}
      targetPort: {{ .Values.port }}
```

```yaml
# charts/spring-service/templates/hpa.yaml — chỉ sinh ra khi autoscaling.enabled: true
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "spring-service.fullname" . }}
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: {{ include "spring-service.fullname" . }} }
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: {{ .Values.autoscaling.targetCPU }} }
{{- end }}
```

> `httproute.yaml` trong cây thư mục ở trên là tùy chọn — HTTPRoute tuần 7 (`k8s/gateway`) vẫn dùng tiếp được vì tên Service giữ nguyên `order-service` / `payment-service`.
>
> Container trong template tên `app` (Deployment tay tuần 6 tên `order-service`) → sau khi chuyển sang Helm, `kubectl set image` phải dùng `app=...`; nhưng với Helm nên đổi image bằng `helm upgrade --set image.tag=v2`.

```powershell
helm lint charts/spring-service -f charts/spring-service/values/order-dev.yaml
# PowerShell không có "less" → ghi ra file để xem
helm template order-service charts/spring-service -f charts/spring-service/values/order-dev.yaml > rendered-order.yaml

# Helm KHÔNG nhận quản lý resource đã tạo bằng kubectl (báo "invalid ownership metadata")
# → xóa Deployment/Service/ConfigMap/HPA tạo tay của app trước. KHÔNG xóa Secret, DB, Redis, Gateway.
kubectl delete deploy,svc,hpa order-service payment-service -n dev --ignore-not-found
kubectl delete configmap order-service-config payment-service-config -n dev --ignore-not-found

helm upgrade --install order-service charts/spring-service -n dev -f charts/spring-service/values/order-dev.yaml
helm upgrade --install payment-service charts/spring-service -n dev -f charts/spring-service/values/payment-dev.yaml
helm list -n dev
# image v2 phải đã build + kind load (như tuần 6)
helm upgrade order-service charts/spring-service -n dev -f charts/spring-service/values/order-dev.yaml --set image.tag=v2
helm history order-service -n dev
helm rollback order-service 1 -n dev
```

**Output cụ thể:**
- 1 chart `spring-service` dùng cho cả 2 service; xóa thư mục YAML thủ công của tuần 6–7 cho app (DB/Redis giữ nguyên hoặc chart riêng)
- Chứng minh:
  - `helm template` với values dev vs prod → diff khác replicas/resources/autoscaling
  - `helm upgrade --set image.tag=v2` → `helm rollback` → `helm history` có 3 revision
  - Thiếu `image.repository` → `helm template` báo lỗi `required`

---

## Chủ Đề 2: Kafka trên Kubernetes (Strimzi)

### Nội Dung Phải Học

- **Kafka cơ bản**:
  - **Topic**: kênh sự kiện (`order-events`)
  - **Partition**: topic chia nhỏ để song song; thứ tự chỉ đảm bảo **trong 1 partition**
  - **Key**: message cùng key → cùng partition (dùng `orderId` làm key để giữ thứ tự theo đơn)
  - **Offset**: vị trí message trong partition; consumer tự commit offset
  - **Consumer group**: các instance cùng group chia nhau partition → scale consumer
  - **Replication factor**, `min.insync.replicas`, `acks=all`: độ bền dữ liệu
  - **KRaft**: Kafka mới không cần ZooKeeper
- **Operator pattern**: CRD + controller tự quản lý phần mềm phức tạp. Strimzi = operator cho Kafka
- **Strimzi CRD**: `Kafka`, `KafkaNodePool`, `KafkaTopic`, `KafkaUser`
- **Trên AWS**: Amazon MSK là Kafka managed — biết để so sánh, không cần dùng

### Cấu Trúc Tiêu Chuẩn

```
namespace kafka
  ├─ strimzi-cluster-operator (Deployment)
  ├─ Kafka "jaka" + KafkaNodePool (1 node, role broker+controller, KRaft)
  │     └─ Service jaka-kafka-bootstrap:9092
  └─ KafkaTopic order-events (partitions: 3, replicas: 1)

namespace dev
  order-service ──produce──► jaka-kafka-bootstrap.kafka:9092 ──consume──► payment-service
```

```powershell
kubectl create namespace kafka
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
kubectl wait deploy/strimzi-cluster-operator -n kafka --for=condition=Available --timeout=5m

# Lấy manifest single-node đúng với phiên bản Strimzi vừa cài (apiVersion thay đổi giữa các bản):
#   https://github.com/strimzi/strimzi-kafka-operator/tree/main/examples/kafka
#   → file kafka-single-node.yaml (hoặc kafka-ephemeral-single.yaml), đổi tên cluster thành "jaka"
#   Đổi CẢ 2 chỗ: metadata.name của Kafka VÀ label "strimzi.io/cluster" trong KafkaNodePool.
#   Sót label → node pool không gắn vào cluster, Kafka không bao giờ Ready.
#   Dùng tag release khớp bản đã cài (không dùng nhánh main nếu "latest" đã khác main).
kubectl apply -f k8s/kafka/kafka-single-node.yaml -n kafka
kubectl wait kafka/jaka --for=condition=Ready --timeout=10m -n kafka
```

```yaml
# k8s/kafka/topics.yaml — kiểm tra apiVersion khớp bản Strimzi đã cài
apiVersion: kafka.strimzi.io/v1   # Strimzi 1.x chỉ còn v1; bản 0.x cũ dùng v1beta2. Kiểm tra: kubectl get crd kafkatopics.kafka.strimzi.io -o jsonpath='{.spec.versions[*].name}'
kind: KafkaTopic
metadata:
  name: order-events
  namespace: kafka
  labels: { strimzi.io/cluster: jaka }
spec:
  partitions: 3
  replicas: 1
  config:
    retention.ms: 604800000   # 7 ngày
```

```powershell
kubectl apply -f k8s/kafka/topics.yaml

# Test bằng console producer/consumer có sẵn trong image Kafka của Strimzi
$img = kubectl -n kafka get pod -l strimzi.io/cluster=jaka,strimzi.io/kind=Kafka -o jsonpath='{.items[0].spec.containers[0].image}'

# Terminal 1 — producer (gõ từng dòng, Ctrl+C để thoát)
kubectl -n kafka run producer -it --rm --restart=Never --image=$img -- bin/kafka-console-producer.sh --bootstrap-server jaka-kafka-bootstrap:9092 --topic order-events

# Terminal 2 — consumer (chạy lại dòng $img ở terminal này trước)
kubectl -n kafka run consumer -it --rm --restart=Never --image=$img -- bin/kafka-console-consumer.sh --bootstrap-server jaka-kafka-bootstrap:9092 --topic order-events --from-beginning
```

**Output cụ thể:**
- Strimzi + Kafka 1 node + topic `order-events` (3 partition)
- Chứng minh:
  - Console producer gửi 3 message → console consumer (terminal khác) nhận đủ
  - `kubectl get kafka,kafkatopic -n kafka` → `Ready`
- Ghi vào README: giải thích partition, key, consumer group bằng sơ đồ

---

## Chủ Đề 3: Spring Boot ↔ Kafka (Message Đầu Tiên)

### Nội Dung Phải Học

- **Spring for Apache Kafka**: `KafkaTemplate` (gửi), `@KafkaListener` (nhận)
- **Dự án dùng Spring Boot 4.1** (xem `build.gradle`) → 2 khác biệt so với tutorial cũ trên mạng:
  - Boot 4 tách auto-config thành từng module → phải dùng **`spring-boot-starter-kafka`**. Chỉ thêm `spring-kafka` thì **không có** auto-config, `KafkaTemplate` không được tạo
  - Boot 4 dùng **Jackson 3** → serializer JSON là `JacksonJsonSerializer` / `JacksonJsonDeserializer`. `JsonSerializer`/`JsonDeserializer` cũ dựa trên Jackson 2 (deprecated, không có Jackson 2 trên classpath sẽ lỗi lúc chạy)
- **Type header**: mặc định producer gắn header `__TypeId__` = tên class đầy đủ (`com.example.order_service...`). payment-service ở package khác (`com.jaka.payment_service`) → deserialize lỗi class không tồn tại. Cách sửa: producer tắt type header, consumer khai báo `value.default.type`
- **Producer config**: `acks=all`, `enable.idempotence=true`
- **Consumer config**: `group-id`, `auto-offset-reset=earliest`
- **Test local**: Testcontainers Kafka hoặc thêm Kafka vào `docker-compose.yml` của service

### Cấu Trúc Tiêu Chuẩn

```groovy
// build.gradle (cả 2 service) — Spring Boot 4
implementation 'org.springframework.boot:spring-boot-starter-kafka'
testImplementation 'org.springframework.boot:spring-boot-starter-kafka-test'
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:testcontainers-kafka'   // Testcontainers 2.x đổi tên artifact (trước là org.testcontainers:kafka)
```

```yaml
# application.yaml (order-service)
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JacksonJsonSerializer
      properties:
        enable.idempotence: true
        spring.json.add.type.headers: false     # không gắn tên class của order-service vào message
```

```yaml
# application.yaml (payment-service)
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      group-id: payment-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JacksonJsonDeserializer
      properties:
        spring.json.value.default.type: com.jaka.payment_service.event.OrderCreatedEvent
        spring.json.trusted.packages: com.jaka.payment_service.event
```

> Nhớ thêm `KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092` vào values `payment-dev.yaml` như `order-dev.yaml`.

**Bước trước: order-service chưa có API tạo order.** Hiện `OrderController` chỉ có `GET` → phải thêm `POST` trước khi có gì để publish. Lưu ý `getAllOrders()` đang `@Cacheable("orders")` → tạo order xong phải `@CacheEvict`, nếu không `GET` vẫn trả list cũ từ Redis.

```java
// order-service — OrderService (thêm vào class hiện có)
@Transactional
@CacheEvict(value = "orders", allEntries = true)
public Order create(CreateOrderRequest req) {
    Order order = orderRepository.save(Order.builder()
            .orderNumber("ORD-" + System.currentTimeMillis())
            .customerName(req.customerName())
            .status(OrderStatus.CREATED)        // enum hiện có: CREATED, PAID, SHIPPED, DELIVERED, CANCELLED
            .totalAmount(req.totalAmount())
            .currency(req.currency())
            .createdAt(LocalDateTime.now())
            .build());
    publisher.publish(OrderCreatedEvent.from(order));   // tuần 9 thay bằng Outbox
    return order;
}
// OrderController: @PostMapping @ResponseStatus(HttpStatus.CREATED) public Order create(@RequestBody CreateOrderRequest req)
```

```java
// Field khớp entity Order hiện tại (chưa có sku/quantity — tuần 9 mới thêm cho inventory)
public record OrderCreatedEvent(String eventId, Long orderId, String orderNumber,
                                String customerName, BigDecimal totalAmount, String currency,
                                Instant occurredAt) {
    public static OrderCreatedEvent from(Order o) {
        return new OrderCreatedEvent(UUID.randomUUID().toString(), o.getId(), o.getOrderNumber(),
                o.getCustomerName(), o.getTotalAmount(), o.getCurrency(), Instant.now());
    }
}

@Service
@RequiredArgsConstructor
public class OrderEventPublisher {
    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    public void publish(OrderCreatedEvent event) {
        // key = orderId → mọi event của cùng 1 đơn vào cùng partition, giữ thứ tự
        kafkaTemplate.send("order-events", event.orderId().toString(), event);
    }
}
```

```java
// payment-service — copy record OrderCreatedEvent sang package com.jaka.payment_service.event
@Slf4j
@Component
public class OrderEventsListener {
    @KafkaListener(topics = "order-events", groupId = "payment-service")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreated orderId={}", event.orderId());
    }
}
```

**Output cụ thể:**
- order-service publish `OrderCreatedEvent` khi tạo order; payment-service log khi nhận
- Chứng minh:
  - `POST /api/orders` → log payment-service in `Received OrderCreated`:

```powershell
# PowerShell: dùng Invoke-RestMethod thay cho curl -d (tránh lỗi escape dấu ")
Invoke-RestMethod -Method Post -Uri http://localhost:8090/api/orders -ContentType 'application/json' -Body '{"customerName":"Tai","totalAmount":120.50,"currency":"USD"}'
kubectl logs -n dev -l app.kubernetes.io/instance=payment-service --tail=20    # đổi label theo _helpers.tpl của bạn
```
  - Scale payment-service lên 3 replica → mỗi Pod nhận 1 partition (xem log)
  - Scale payment-service về 0 → tạo 5 order → scale lên lại → nhận đủ 5 event

---

## Chủ Đề 4: System Design — Phần 1

### Nội Dung Phải Học

**REST vs gRPC**

| | REST (JSON/HTTP 1.1) | gRPC (Protobuf/HTTP 2) |
|---|---|---|
| Payload | Text, dễ đọc | Binary, nhỏ, nhanh |
| Contract | OpenAPI (tùy chọn) | `.proto` bắt buộc, sinh code |
| Streaming | Hạn chế | Bi-directional streaming |
| Browser | Trực tiếp | Cần gRPC-Web |
| Dùng khi | API public, client đa dạng | Service-to-service nội bộ, latency thấp |

**Load Balancer**
- L4 (TCP, NLB) vs L7 (HTTP, ALB, Envoy): L7 route theo path/header được
- Thuật toán: round-robin, least-connections, consistent hashing
- Health check + connection draining
- Đã gặp ở đâu: ALB tháng 1, kube-proxy (L4), Envoy Gateway (L7)

**Scalability**
- Vertical (to hơn) vs Horizontal (nhiều hơn)
- Stateless service mới scale ngang dễ → session để ở Redis, không ở memory
- DB scale: read replica, partition/sharding, connection pooling (Hikari)

**Caching**
- Pattern: cache-aside (đang dùng với `@Cacheable`), write-through, write-behind
- Invalidation: TTL, evict khi update (`@CacheEvict`)
- Vấn đề: cache stampede, stale data, hot key

**CAP & Consistency**
- Khi có network partition: chọn Consistency hoặc Availability
- Postgres đơn (CP-ish), Kafka (tùy `acks`/`min.insync.replicas`), Redis replica (async → có thể mất dữ liệu)
- Strong vs eventual consistency — microservices + event = **eventual** (tuần 9–10)

**Output cụ thể:**
- `docs/system-design-1.md`: mỗi chủ đề 1 đoạn giải thích + 1 ví dụ **trong chính hệ thống của bạn**
- Vẽ 1 sơ đồ: request đi qua những lớp LB/cache/DB nào
- Tự trả lời 5 câu phỏng vấn: "Khi nào chọn gRPC?", "Cache-aside hoạt động thế nào?", "Scale service stateful ra sao?", "CAP áp dụng cho Kafka thế nào?", "L4 vs L7 LB?"

---

## Portfolio Cuối Tuần — Repo `springboot-k8s` (tiếp tục)

```
springboot-k8s/
├── charts/spring-service/     # chart dùng chung + values/
├── k8s/
│   ├── kafka/                 # kafka-single-node.yaml, topics.yaml
│   └── ... (db, redis, gateway)
├── docs/system-design-1.md
└── README.md
```

**README.md phải trả lời được:**
1. Vì sao dùng 1 chart cho nhiều service? Khi nào nên tách?
2. `helm rollback` khác `kubectl rollout undo` thế nào?
3. Partition + key giữ thứ tự message ra sao?
4. Consumer chết rồi sống lại thì có mất event không? Vì sao?

---

## 📋 Quick Reference — Tuần 8

| Lệnh | Dùng để |
|---|---|
| `helm lint` / `helm template` | Kiểm tra + render trước khi cài |
| `helm upgrade --install` | Cài hoặc nâng cấp (idempotent) |
| `helm history` / `helm rollback` | Quản lý revision |
| `kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka'` | Cài Strimzi |
| `kubectl get kafka,kafkatopic -n kafka` | Trạng thái Kafka |
| `kafka-console-producer/consumer.sh` | Test gửi/nhận |

## 🎯 Key Learnings

- [ ] 1 Helm chart dùng cho 2 service, values dev/prod
- [ ] Rollback bằng Helm
- [ ] Kafka chạy trên kind bằng operator
- [ ] Spring Boot gửi/nhận event, consumer group scale đúng
- [ ] Trả lời được 5 câu System Design phần 1

**Tuần sau → Tuần 9: inventory + notification service, event-driven, Outbox, CQRS** 🚀
