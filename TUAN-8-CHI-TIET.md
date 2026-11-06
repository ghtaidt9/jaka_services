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
- **Type header**: mặc định producer gắn header `__TypeId__` = tên class đầy đủ (`com.jaka.order_service...`). payment-service ở package khác (`com.jaka.payment_service`) → deserialize lỗi class không tồn tại. Cách sửa: producer tắt type header, consumer khai báo `value.default.type`
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

> **Chủ đề này không viết code mới.** Mục tiêu: gọi đúng tên những thứ bạn **đã dựng** ở Tuần 6–8, hiểu cơ chế bên dưới, và trả lời được câu phỏng vấn bằng ví dụ trong chính hệ thống của bạn.
>
> Mỗi phần theo cùng 1 khuôn: **Là gì (1 câu)** → **Cơ chế** → **Trong hệ thống của bạn** → **Thử tay** → **Câu trả lời phỏng vấn**.

### Bản đồ: 1 request đi qua những lớp nào

```
curl POST /api/orders/
  │
  ▼  (port-forward 8090)
Envoy Gateway ............ L7 Load Balancer  → Phần 2
  │  HTTPRoute: /api/orders → order-service
  ▼
order-service × N pod .... Scalability (stateless, HPA) → Phần 3
  │  @Cacheable / @CacheEvict
  ├──► order-redis ........ Caching → Phần 4
  ├──► order-db ........... (qua kube-proxy = L4 LB) → Phần 2, 3
  └──► Kafka (acks=all) ... CAP & Consistency → Phần 5
          │
          ▼
       payment-service ..... REST vs gRPC vs Event → Phần 1
```

---

### Phần 1: REST vs gRPC (và vì sao hiện tại dùng Kafka)

**Là gì:** 2 cách để service A **gọi đồng bộ** service B và chờ kết quả. REST gửi JSON text qua HTTP/1.1; gRPC gửi Protobuf binary qua HTTP/2, theo 1 file contract `.proto`.

**3 kiểu giao tiếp trong hệ thống của bạn:**

| Đường đi | Kiểu | Caller có chờ không? |
|---|---|---|
| curl → Envoy → order-service | REST | Có — chờ `201` |
| order-service → payment-service | Kafka event | Không — gửi xong là xong |
| payment → order (giả định: "order 11 còn CREATED không?") | REST hoặc gRPC | Có |

**So sánh:**

| | REST | gRPC |
|---|---|---|
| Tư duy | Resource + verb: `GET /orders/11` | Method: `GetOrder(id)` |
| Payload | JSON text (`{"id":11}` = 9 byte) | Protobuf binary (`08 0B` = 2 byte) |
| Contract | Tùy chọn (OpenAPI) → hay bị copy DTO bằng tay | Bắt buộc `.proto` → sinh code cho cả 2 bên |
| Đổi field sai | Lỗi lúc **chạy** | Lỗi lúc **compile** |
| Transport | HTTP/1.1: 1 request / connection tại 1 thời điểm | HTTP/2: nhiều stream trên 1 connection |
| Streaming | Không có sẵn | Client / server / 2 chiều |
| Deadline | Tự set timeout từng client | Deadline đi kèm request qua mọi service |
| Browser / curl | Gọi trực tiếp | Cần gRPC-Web / `grpcurl` |

**Cơ chế gRPC (tóm tắt):**
1. Viết `order.proto` (message = DTO, service = interface). **1 file duy nhất**, order-service sở hữu.
2. Gradle plugin `com.google.protobuf` chạy `protoc` → sinh `Order`, `OrderServiceGrpc.OrderServiceImplBase` (server), `OrderServiceBlockingStub` (client).
3. Stub = method sinh ra + 1 **channel** trỏ tới `order-service:9090` → DNS → Service → pod (giống `payment-db:5432` trong values của bạn).

**⚠️ Bẫy trên K8s:** gRPC giữ **1 connection lâu dài**. Service K8s (kube-proxy) chọn pod **1 lần / connection** → mọi call dồn vào 1 pod. Cách sửa: L7 proxy (Envoy/mesh) hoặc headless Service + `round_robin` phía client.

**Trong hệ thống của bạn:** chưa cần gRPC. payment-service không cần gọi order-service vì `OrderCreatedEvent` đã chứa đủ dữ liệu. Nếu cần gọi đồng bộ → dùng REST với `@HttpExchange` trước.

**Câu trả lời phỏng vấn — "Khi nào chọn gRPC?"**
> Gọi đồng bộ **nội bộ** giữa các service mình kiểm soát cả 2 đầu, khi cần contract chặt, payload nhỏ, latency thấp, hoặc streaming. Giữ REST ở biên (browser, đối tác). Trên K8s phải có L7 hoặc client-side load balancing vì connection HTTP/2 sống lâu. Nếu caller không cần kết quả ngay → dùng event (Kafka), không dùng cả hai.

---

### Phần 2: Load Balancer — L4 vs L7

**Là gì:** thành phần chia request cho nhiều bản sao của 1 service và ngừng gửi cho bản sao bị lỗi.

**L4 / L7 nghĩa là gì:** số tầng trong mô hình OSI. LB đọc packet **sâu đến tầng nào** thì quyết định dựa trên tầng đó.

```
[ L2 Ethernet | L3 IP 10.244.1.5 | L4 TCP :8081 | L7 HTTP POST /api/orders/ ... ]
                                   ▲               ▲
                         L4 LB đọc đến đây        L7 LB đọc đến đây
```

| | L4 (Transport) | L7 (Application) |
|---|---|---|
| Đọc | IP + port | Toàn bộ HTTP: path, header, host, method |
| Quyết định | **1 lần / TCP connection** | **Mỗi request** (mỗi HTTP/2 stream) |
| Route theo path | ❌ | ✅ `/api/orders` vs `/api/payments` |
| Retry, rewrite, TLS termination | ❌ | ✅ |
| Chi phí | Rất rẻ, chỉ chuyển byte | Phải parse HTTP → tốn CPU hơn |
| Ví dụ | AWS NLB, **kube-proxy** | AWS ALB, **Envoy**, Nginx |

**Thuật toán chọn backend:**

| Thuật toán | Cách chọn | Hợp khi | Điểm yếu |
|---|---|---|---|
| Round-robin | 1 → 2 → 3 → 1 | Pod đều nhau, request đều nhau | Không nhìn tải: pod đang kẹt query chậm vẫn bị chia |
| Least request / connections | Backend ít request đang chạy nhất | Request dài ngắn khác nhau | LB phải theo dõi trạng thái |
| Random | Ngẫu nhiên | Đơn giản, đủ đều khi nhiều request | Lệch trong ngắn hạn |
| Consistent hashing | `hash(key)` → vòng tròn → backend gần nhất | Cần "dính" (cùng user → cùng pod) | Hot key dồn 1 backend |

Vì sao consistent hashing mà không `hash % N`: đổi N từ 3 → 4 thì `% N` chuyển gần **hết** key sang pod khác; vòng hash chỉ chuyển ~1/N key.

**Health check + connection draining:**

| Việc | Cơ chế trên K8s (chart của bạn) | Tương đương AWS (tháng 1) |
|---|---|---|
| Ngừng gửi cho pod lỗi | `readinessProbe` fail → pod Not Ready → bị gỡ khỏi **EndpointSlice** → kube-proxy & Envoy ngừng gửi | ALB target group health check `/actuator/health` |
| Cho request đang chạy xong | Pod Terminating → gỡ khỏi EndpointSlice **đồng thời** `preStop sleep 5s` → SIGTERM → Spring graceful shutdown | ALB deregistration delay (mặc định 300s) |

**Trong hệ thống của bạn:**

| LB | Tầng | Thuật toán mặc định | Ở đâu |
|---|---|---|---|
| Envoy Gateway | L7 | Least request | curl → `/api/orders` → order-service pod |
| kube-proxy | L4 | Random theo connection (iptables/nftables) | mọi `Service`: `order-db`, `payment-redis`, ... |
| ALB (tháng 1) | L7 | Round-robin | `/api/orders*` → order target group (EC2) |
| Kafka partitioner | (không phải LB, cùng ý tưởng) | `hash(key) % partitions` | `orderId` → `order-events-N` |

**Thử tay:**

1. Xem EndpointSlice — danh sách pod IP mà cả kube-proxy và Envoy dùng:
   ```bash
   kubectl get endpointslice -n dev -l kubernetes.io/service-name=order-service -o wide
   ```
2. Scale lên 3, chạy lại lệnh trên → thấy 3 IP. Scale về 2 → 1 IP biến mất **trước khi** pod tắt hẳn (draining):
   ```bash
   kubectl scale deploy/order-service -n dev --replicas=3
   kubectl get endpointslice -n dev -l kubernetes.io/service-name=order-service -o wide -w
   ```
3. Xem luật L4 của kube-proxy trên node (kind node là container):
   ```bash
   docker exec jaka-worker iptables-save -t nat | grep "dev/order-db"
   # nếu trống (kube-proxy chạy chế độ nftables):
   docker exec jaka-worker nft list ruleset | grep -A3 "dev/order-db"
   ```
   Chỉ có IP + port, không có path → đó là "L4".

**Câu trả lời phỏng vấn — "L4 vs L7 LB?"**
> L4 cân bằng theo IP/port, quyết định 1 lần mỗi TCP connection — nhanh, không cần hiểu giao thức, nhưng không thấy path/header (NLB, kube-proxy). L7 parse HTTP nên route được theo path/header, retry, terminate TLS, và cân bằng từng request — cần thiết cho connection sống lâu như gRPC (ALB, Envoy). Production hay dùng cả hai: NLB L4 phía trước (throughput, IP tĩnh), Envoy L7 phía sau (routing).

---

### Phần 3: Scalability

**Là gì:** khả năng chịu thêm tải bằng cách thêm tài nguyên.

| | Vertical (scale up) | Horizontal (scale out) |
|---|---|---|
| Cách | Pod/máy **to hơn**: tăng `resources.limits` | **Nhiều** pod hơn: `replicas`, HPA |
| Giới hạn | Trần phần cứng của 1 node | Gần như không, nếu service stateless |
| Downtime | Thường phải restart | Không |
| Ví dụ của bạn | Tăng memory khi gặp OOMKilled (Tuần 7) | HPA order-service 2 → 5 pod (Tuần 7) |

**Điều kiện để scale ngang: service phải stateless** — pod nào cũng trả lời được mọi request vì không giữ dữ liệu riêng trong memory.

| Thành phần | Stateless? | Scale ngang thế nào |
|---|---|---|
| order-service, payment-service | ✅ (data ở Postgres, cache ở Redis) | Tăng `replicas` / HPA |
| order-db (StatefulSet + PVC) | ❌ | **Không** chỉ tăng `replicas`: mỗi pod có PVC riêng, không tự đồng bộ → cần Postgres replication (read replica), hoặc sharding |
| Kafka consumer | ✅ nhưng bị giới hạn | Tối đa **= số partition** (3). Pod thứ 4 ngồi không — bạn đã thấy `partitions assigned` |
| Kafka broker | ❌ | Thêm broker qua `KafkaNodePool.replicas` + chuyển partition |

**Bẫy hay gặp khi scale ngang: connection pool.** Mỗi pod Spring mở pool Hikari mặc định **10** connection tới Postgres. Postgres mặc định `max_connections = 100`.

```
10 pod × 10 connection = 100 → pod thứ 11 không lấy được connection
```

→ Scale app không có nghĩa scale được DB. Cách xử lý: giảm `maximum-pool-size`, thêm PgBouncer, hoặc read replica.

**Thử tay:**
```bash
kubectl exec -n dev order-db-0 -- psql -U order_user -d orderdb -c "show max_connections;"
kubectl exec -n dev order-db-0 -- psql -U order_user -d orderdb \
  -c "select usename, count(*) from pg_stat_activity group by usename;"
```
Scale order-service lên 4 → chạy lại lệnh 2 → số connection của `order_user` tăng ~10 mỗi pod.

**Câu trả lời phỏng vấn — "Scale service stateful ra sao?"**
> Trước hết đẩy state ra ngoài để service thành stateless (session/cache → Redis, data → DB) rồi scale ngang bằng replicas/HPA. Phần stateful còn lại (DB, broker) scale bằng cơ chế riêng của nó: read replica cho đọc, partition/sharding cho ghi, connection pooling để app không làm cạn connection DB. Trên K8s dùng StatefulSet để mỗi pod giữ identity + volume riêng, nhưng StatefulSet **không** tự sao chép dữ liệu.

---

### Phần 4: Caching

**Là gì:** giữ bản sao dữ liệu ở nơi đọc nhanh hơn (Redis, RAM) để không phải hỏi DB mỗi lần.

**3 pattern ghi/đọc:**

| Pattern | Đọc | Ghi | Rủi ro |
|---|---|---|---|
| **Cache-aside** (bạn đang dùng) | App hỏi cache → miss → đọc DB → ghi vào cache | App ghi DB, rồi **xóa** cache | Có thể đọc dữ liệu cũ trong khoảng ngắn |
| Write-through | Đọc từ cache | Ghi cache **và** DB cùng lúc | Ghi chậm hơn |
| Write-behind | Đọc từ cache | Ghi cache, DB được ghi **sau** (bất đồng bộ) | Cache chết → mất dữ liệu chưa ghi |

**Cache-aside trong code của bạn** ([OrderService.java](order-service/order-service/src/main/java/com/jaka/order_service/service/OrderService.java)):

```java
@Cacheable("orders")                              // đọc: có trong Redis → trả luôn, không query DB
public List<Order> getAllOrders() { ... }

@CacheEvict(value = "orders", allEntries = true)  // ghi: lưu DB xong → xóa toàn bộ cache "orders"
public Order create(CreateOrderRequest req) { ... }
```

```
GET lần 1:  Redis miss → SELECT * FROM orders → ghi Redis key "orders::SimpleKey []"
GET lần 2:  Redis hit  → không chạm DB
POST:       INSERT → xóa key → GET kế tiếp lại miss
```

**Invalidation — 2 cách làm cache hết hạn:**
- **Evict khi ghi** — đang dùng (`@CacheEvict`).
- **TTL** — **chưa cấu hình** → key sống mãi. Nếu ai đó sửa DB trực tiếp (psql), cache sai mãi mãi. Thêm vào `application.yaml`:
  ```yaml
  spring:
    cache:
      redis:
        time-to-live: 10m
  ```

**3 vấn đề kinh điển:**

| Vấn đề | Chuyện gì xảy ra | Cách xử lý |
|---|---|---|
| Stale data | Cache còn bản cũ sau khi DB đổi | TTL + evict khi ghi |
| Cache stampede | Key hot hết hạn → 1000 request cùng miss → cùng đập DB | Lock / `@Cacheable(sync = true)`, TTL ngẫu nhiên |
| Hot key | 1 key bị đọc quá nhiều → 1 node Redis quá tải | Cache local trong app (2 tầng), nhân bản key |

**Thử tay:**
```bash
kubectl exec -n dev order-redis-0 -- redis-cli KEYS 'orders*'
kubectl exec -n dev order-redis-0 -- redis-cli TTL 'orders::SimpleKey []'   # -1 = không hết hạn
```
Gọi `GET /api/orders/` 2 lần, xem log order-service: chỉ lần đầu có câu `select`. POST 1 order → chạy lại `KEYS` → key biến mất.

**Câu trả lời phỏng vấn — "Cache-aside hoạt động thế nào?"**
> App là người điều phối: đọc thì hỏi cache trước, miss thì đọc DB rồi nạp vào cache; ghi thì ghi DB rồi xóa key trong cache (xóa chứ không cập nhật, để tránh 2 lần ghi chạy đua làm cache sai). Kết hợp TTL để giới hạn thời gian dữ liệu cũ tồn tại. Trong Spring là `@Cacheable` + `@CacheEvict` với Redis.

---

### Phần 5: CAP & Consistency

**Là gì:** khi mạng giữa các node bị đứt (**P**artition), hệ thống phân tán phải chọn 1 trong 2:
- **C**onsistency: mọi lần đọc thấy dữ liệu mới nhất — chấp nhận **từ chối/chờ** request.
- **A**vailability: luôn trả lời — chấp nhận dữ liệu **có thể cũ**.

P không phải lựa chọn — mạng chắc chắn sẽ có lúc đứt. Nên câu hỏi thật là: "khi đứt, chọn C hay A?"

**Trong hệ thống của bạn:**

| Thành phần | Khi node/mạng có sự cố | Nghiêng về |
|---|---|---|
| order-db (Postgres 1 node) | DB chết → app lỗi, không có dữ liệu sai | C (nhưng không có A vì chỉ 1 node) |
| Kafka `acks=all` + `min.insync.replicas=2` | Thiếu replica → producer bị **từ chối** ghi | C |
| Kafka `acks=1` | Leader ghi xong là OK; leader chết trước khi replica chép → mất message | A |
| Redis có replica | Replica chép **bất đồng bộ** → đọc replica có thể cũ, failover có thể mất ghi | A |
| order → Kafka → payment | payment thấy order **sau** vài ms đến vài phút (bạn đã thấy LAG 5 → 0) | **Eventual consistency** |

**Strong vs eventual:**
- **Strong:** ghi xong thì mọi người đọc thấy ngay (1 DB, 1 transaction).
- **Eventual:** các service **cuối cùng** sẽ khớp nhau, nhưng có khoảng trễ. Microservices + event = eventual. Tuần 9 (outbox) và Tuần 10 (saga) xử lý hệ quả của nó.

**Thử tay — thấy lỗ hổng mà Tuần 9 sẽ vá:**
```bash
kubectl delete pod -n kafka jaka-dual-role-0          # broker duy nhất chết
curl -i -X POST http://localhost:8090/api/orders/ \
  -H 'Content-Type: application/json' \
  -d '{"customerName":"CAP-test","totalAmount":1,"currency":"USD"}'
```
Quan sát và tự giải thích:
- POST có trả `201` không? (order đã được commit vào Postgres)
- Log order-service có lỗi gửi Kafka không? payment có nhận event không, sau khi broker sống lại?
- Nếu DB có order mà Kafka không có event → 2 hệ thống **không khớp**. Đó chính là bài toán **dual write** → Transactional Outbox ở Tuần 9.

**Câu trả lời phỏng vấn — "CAP áp dụng cho Kafka thế nào?"**
> Kafka để mình tự chọn C hay A bằng cấu hình. **Muốn C** (không mất message): đặt RF=3, `min.insync.replicas=2` cho topic, `acks=all` cho producer — khi chỉ còn 1 bản sao sống, Kafka từ chối ghi thay vì nhận message có thể mất. **Muốn A** (luôn ghi được): đặt `acks=1`, hoặc bật `unclean.leader.election.enable` — Kafka vẫn nhận ghi khi có sự cố, đổi lại có thể mất message khi leader chết. Còn giữa các service giao tiếp qua Kafka thì hệ thống luôn là eventual consistency.

---

### Output cụ thể

| # | Sản phẩm | Đạt khi |
|---|---|---|
| 1 | `docs/system-design-1.md` | Mỗi phần (1–5) có: định nghĩa 1 câu, cơ chế, **1 ví dụ trỏ tới file/lệnh thật** trong repo của bạn |
| 2 | 1 sơ đồ request | Vẽ lại "Bản đồ" ở đầu chủ đề, ghi rõ: tầng L4/L7 nào, cache ở đâu, chỗ nào là eventual |
| 3 | Kết quả 4 bài "Thử tay" | Dán output thật (EndpointSlice, `max_connections`, `TTL`, thí nghiệm CAP) vào doc |
| 4 | 5 câu phỏng vấn | Tự trả lời **không nhìn** phần trên, mỗi câu ≤ 4 câu nói, có 1 ví dụ của hệ thống mình |

5 câu phỏng vấn:
1. "Khi nào chọn gRPC?" → Phần 1
2. "L4 vs L7 LB?" → Phần 2
3. "Scale service stateful ra sao?" → Phần 3
4. "Cache-aside hoạt động thế nào?" → Phần 4
5. "CAP áp dụng cho Kafka thế nào?" → Phần 5

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
