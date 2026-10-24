# Tuần 10 chi tiết — Saga, EKS (Terraform) & Tổng Kết Giai Đoạn Tháng 2 + 3

> Giai đoạn gộp Tháng 2 + 3 (Tuần 5–10). **Không cần AWS** — Saga chạy trên kind; EKS chỉ viết Terraform và kiểm tra bằng `terraform test` / LocalStack.

## Bối Cảnh & Mục Tiêu

**Đã hoàn thành:**
- ✅ Tuần 5: Terraform module + backend + test + CI
- ✅ Tuần 6–7: K8s cơ bản + stateful + Gateway + HPA
- ✅ Tuần 8: Helm, Kafka (Strimzi), System Design phần 1
- ✅ Tuần 9: 4 service, Outbox, idempotent consumer, DLT, CQRS

**Tuần 10 mục tiêu:**
- **Saga** cho luồng đặt hàng: kho thiếu hoặc thanh toán thất bại → bù trừ (compensate) để hệ thống nhất quán
- Viết **module Terraform EKS** — hiểu EKS khác kind thế nào, kiểm tra không cần tài khoản AWS
- Deploy **cả 4 service + Kafka + DB** lên kind bằng Helm (1 lệnh)
- Tổng kết giai đoạn, checklist tự đánh giá, chuẩn bị Tháng 4 (CI/CD + Monitoring)

---

## Tại Sao Cần Tuần 10?

```
Sau tuần 9, luồng đặt hàng:
  OrderCreated → InventoryReserved → PaymentFailed → ???
  ❌ Kho đã giữ hàng nhưng không ai trả lại
  ❌ Order kẹt mãi ở trạng thái PENDING
  ❌ Không có transaction phân tán (không thể rollback DB của 3 service)

Saga:
  ✅ Chuỗi transaction local, mỗi bước có "hành động bù trừ"
  ✅ Lỗi ở bước N → chạy bù trừ cho bước N-1 ... 1
  ✅ Order luôn kết thúc ở CONFIRMED hoặc CANCELLED
```

---

## Chủ Đề 1: Saga Pattern

### Nội Dung Phải Học

- **Vì sao không dùng 2PC (two-phase commit)**: khóa tài nguyên lâu, coordinator là điểm lỗi duy nhất, Kafka/nhiều DB không hỗ trợ tốt
- **Saga** = chuỗi local transaction + compensating transaction
- **2 kiểu**:

| | Choreography | Orchestration |
|---|---|---|
| Điều phối | Mỗi service nghe event và tự quyết | 1 orchestrator gửi command từng bước |
| Coupling | Thấp, nhưng luồng khó nhìn tổng thể | Luồng rõ ràng ở 1 chỗ |
| Phù hợp | 3–4 bước, ít nhánh | Nhiều bước, logic phức tạp |
| Công cụ | Kafka thuần | Temporal, Camunda, Spring State Machine |

- **Bài này làm Choreography** (đủ đơn giản với 4 service, tái dùng event tuần 9)
- **Compensation phải idempotent** và **không được fail vĩnh viễn** (retry đến khi thành công)
- **Semantic lock**: trạng thái trung gian `PENDING` báo cho người khác biết đơn đang xử lý
- **Timeout**: payment không trả lời sau N phút → coi như thất bại

### Cấu Trúc Tiêu Chuẩn

**Happy path:**

```
order-svc          inventory-svc          payment-svc          notification-svc
    │ OrderCreated ──────►│                     │                      │
    │   (PENDING)         │ reserve kho         │                      │
    │                     │ InventoryReserved ─►│                      │
    │                     │                     │ charge               │
    │◄──────────────────────── PaymentCompleted │─────────────────────►│ "Đặt hàng thành công"
    │ CONFIRMED           │◄─── PaymentCompleted (commit reservation)  │
```

**Nhánh lỗi 1 — hết hàng:**

```
    │ OrderCreated ──────►│
    │                     │ InventoryRejected
    │◄────────────────────│──────────────────────────────────────────►│ "Hết hàng"
    │ CANCELLED           │
```

**Nhánh lỗi 2 — thanh toán thất bại (cần bù trừ):**

```
    │ OrderCreated ──────►│ InventoryReserved ─►│
    │                     │                     │ PaymentFailed
    │                     │◄────────────────────│
    │                     │ release kho  (COMPENSATE)
    │                     │ InventoryReleased
    │◄────────────────────│──────────────────────────────────────────►│ "Thanh toán thất bại"
    │ CANCELLED
```

**State machine của Order:**

```
PENDING ──InventoryReserved──► AWAITING_PAYMENT ──PaymentCompleted──► CONFIRMED
   │                                  │
   └──InventoryRejected──► CANCELLED ◄┘──PaymentFailed / timeout
```

```java
@KafkaListener(topics = {"inventory-events", "payment-events"}, groupId = "order-service")
@Transactional
public void on(EventEnvelope<JsonNode> event) {
    if (processed.existsById(event.eventId())) return;

    Order order = orders.findById(Long.valueOf(event.aggregateId())).orElseThrow();
    switch (event.eventType()) {
        case "InventoryReserved" -> order.awaitPayment();
        case "InventoryRejected" -> order.cancel("OUT_OF_STOCK");
        case "PaymentCompleted"  -> order.confirm();
        case "PaymentFailed"     -> order.cancel("PAYMENT_FAILED");
        default -> { }
    }
    // chuyển trạng thái không hợp lệ (vd CONFIRMED → CANCELLED) phải ném exception trong entity
    outbox.save(OutboxEvent.of("Order" + order.getStatus().pascalCase(), order.getId().toString(), ...));
    processed.save(new ProcessedEvent(event.eventId(), Instant.now()));
}
```

```java
// Timeout: order kẹt AWAITING_PAYMENT quá 5 phút
@Scheduled(fixedDelay = 60_000)
@Transactional
public void expireStuckOrders() {
    orders.findByStatusAndUpdatedAtBefore(AWAITING_PAYMENT, Instant.now().minus(5, MINUTES))
          .forEach(o -> { o.cancel("PAYMENT_TIMEOUT"); outbox.save(OutboxEvent.of("OrderCancelled", ...)); });
}
```

> Để giả lập thanh toán thất bại: payment-service từ chối khi `totalAmount > 1000` hoặc `customerName = "fail"` (entity `Order` dùng `customerName`, không có `customerId`).

**Những chỗ hay sót khi làm Saga này:**

- **payment-service chỉ nghe `inventory-events`** (bảng tuần 9) → event `InventoryReserved` phải **mang theo** `totalAmount`, `customerName`, `currency` (inventory lấy từ `OrderCreated`). Nếu không, payment không biết thu bao nhiêu tiền
- **Timeout cần `updatedAt`** trên `Order` (tuần 9 đã thêm) và **`@EnableScheduling`**
- **Timeout → inventory phải trả kho**: order-service phát `OrderCancelled` → inventory-service (đang nghe `order-events`) release reservation. Thiếu bước này thì nhánh timeout trả order về `CANCELLED` nhưng kho vẫn bị giữ
- **`PaymentCompleted` đến muộn sau khi đã timeout**: `order.confirm()` trên đơn `CANCELLED` ném exception → retry → rơi vào DLT. Xử lý đúng: bắt trường hợp này, không confirm, phát event yêu cầu hoàn tiền (hoặc ít nhất log + metric để xử lý tay) — đừng để nó thành "lỗi" trong DLT

**Output cụ thể:**
- Saga choreography hoàn chỉnh 3 nhánh (thành công, hết hàng, thanh toán lỗi) + timeout
- Chứng minh (viết `scripts/saga-demo.ps1` chạy cả 3 kịch bản, gọi API bằng `Invoke-RestMethod`):
  - Thành công: order `CONFIRMED`, kho giảm, email "thành công"
  - Hết hàng: order `CANCELLED`, kho không đổi
  - Thanh toán lỗi: order `CANCELLED`, kho **trả về như cũ**, reservation `RELEASED`
  - Tắt payment-service 6 phút → order tự `CANCELLED` do timeout, kho được trả
- Sơ đồ sequence 3 nhánh trong README

---

## Chủ Đề 2: EKS — Viết Terraform, Hiểu Khác Biệt Với kind

### Nội Dung Phải Học

- **EKS là gì**: AWS quản lý control plane (apiserver, etcd) — bạn chỉ quản lý worker node
- **Thành phần**:
  - Cluster (control plane) — tính phí theo giờ, kể cả khi không có Pod
  - **Managed Node Group**: ASG các EC2 do EKS quản lý (giống ASG tháng 1)
  - **Fargate profile**: chạy Pod không cần node (biết khái niệm)
  - **VPC CNI**: mỗi Pod có IP thật trong VPC → subnet phải đủ lớn
  - **Access entries** (thay cho `aws-auth` ConfigMap cũ): map IAM user/role → quyền K8s
  - **IRSA / EKS Pod Identity**: Pod có IAM role riêng (thay vì cả node dùng chung như tháng 1 — sửa đúng vấn đề "order đọc được secret của payment")
  - **AWS Load Balancer Controller**: biến Ingress/Gateway thành ALB thật
  - **EBS CSI driver**: StorageClass `gp3` cho PVC
- **Module chuẩn**: `terraform-aws-modules/eks/aws` — dùng lại VPC từ module `network` tuần 5 (`jaka_modularized/modules/network`)
- **Subnet tags** cho LB controller: `kubernetes.io/role/elb = 1` (public), `kubernetes.io/role/internal-elb = 1` (private)

**kind vs EKS:**

| | kind (đang dùng) | EKS |
|---|---|---|
| Control plane | Container trên laptop | AWS quản lý, HA 3 AZ |
| Node | Container | EC2 (managed node group) |
| Pod IP | Mạng Docker | IP thật trong VPC (VPC CNI) |
| LoadBalancer Service | Không có (port-forward) | NLB/ALB thật |
| Storage | local-path | EBS gp3 |
| Quyền Pod gọi AWS | Không có | IRSA / Pod Identity |
| Chi phí | 0 | ~0.10 USD/giờ control plane + EC2 + NAT |

### Cấu Trúc Tiêu Chuẩn

```
jaka_modularized/
├── modules/
│   ├── network/        (tuần 5 — thêm subnet tags cho EKS)
│   └── eks/            ← MỚI
│       ├── main.tf
│       ├── variables.tf
│       ├── outputs.tf
│       └── tests/eks.tftest.hcl
└── envs/dev-eks/
```

```hcl
# modules/eks/main.tf — kiểm tra version mới nhất của module trên registry trước khi dùng
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"

  name               = "${var.name_prefix}-eks"
  kubernetes_version = var.kubernetes_version

  vpc_id     = var.vpc_id
  subnet_ids = var.private_subnet_ids

  endpoint_public_access                   = true
  enable_cluster_creator_admin_permissions = true

  addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = { before_compute = true }
    eks-pod-identity-agent = { before_compute = true }
    aws-ebs-csi-driver     = {}
  }

  eks_managed_node_groups = {
    default = {
      instance_types = [var.node_instance_type]
      min_size       = var.node_min
      max_size       = var.node_max
      desired_size   = var.node_min
    }
  }
}
```

> Tên input của module `terraform-aws-modules/eks` thay đổi giữa v20 và v21 (ví dụ `cluster_name` → `name`). Luôn đọc trang registry của đúng version bạn pin.

```hcl
# modules/eks/tests/eks.tftest.hcl
mock_provider "aws" {}
mock_provider "tls" {}
mock_provider "time" {}

variables {
  name_prefix        = "jaka-dev"
  kubernetes_version = "1.35"   # chọn bản EKS đang "standard support" lúc bạn làm (xem trang EKS Kubernetes versions); bản hết standard support bị tính phí extended support
  vpc_id             = "vpc-123"
  private_subnet_ids = ["subnet-a", "subnet-b"]
  node_instance_type = "t3.medium"
  node_min           = 2
  node_max           = 4
}

run "node_group_toi_thieu_2_node" {
  command = plan
  assert {
    condition     = var.node_min >= 2
    error_message = "Cần ít nhất 2 node để chịu lỗi 1 AZ."
  }
}
```

> Assert trên chỉ kiểm tra **biến đầu vào** — nó luôn pass với giá trị bạn tự đặt, nên không chứng minh gì về module. Cách đúng: đặt `validation { condition = var.node_min >= 2 }` trong `variables.tf`, rồi viết 1 `run` truyền `node_min = 1` với `expect_failures = [var.node_min]` để chứng minh validation chặn được.
>
> `mock_provider` trả chuỗi ngẫu nhiên cho mọi data source (ARN, policy JSON...). Nếu module EKS gọi `split`/`jsondecode` trên các giá trị đó và `plan` lỗi, dùng `override_data` trong file test để gán giá trị hợp lệ cho data source đó.

```bash
cd modules/eks
terraform init
terraform validate
terraform test
```

> **Giới hạn khi không có AWS:** `validate` + `test` (mock) kiểm tra được cú pháp và logic input. LocalStack Pro có giả lập EKS (chạy k3s bên trong) nhưng không có managed node group thật, VPC CNI hay LB controller thật. Việc `apply` thật + `kubectl` vào EKS để dành cho lần có tài khoản AWS (nhớ `destroy` ngay, EKS + NAT tốn tiền theo giờ).

**Output cụ thể:**
- Module `eks` gọi được từ `envs/dev-eks`, dùng VPC của module `network`
- `terraform validate` + `terraform test` pass; thêm vào CI tuần 5
- Chứng minh: CI xanh với module mới
- Ghi vào README: bảng kind vs EKS, giải thích IRSA/Pod Identity sửa vấn đề chung IAM role của tháng 1

---

## Chủ Đề 3: Deploy Toàn Bộ Lên kind Bằng 1 Lệnh

### Nội Dung Phải Học

- **Umbrella chart** hoặc **helmfile**: cài nhiều release theo thứ tự
- **Thứ tự phụ thuộc**: Kafka + DB phải Ready trước app → dùng readiness probe + app tự retry kết nối (không cần ép thứ tự cứng)
- **Namespace tách biệt**: `kafka`, `dev`, `envoy-gateway-system`
- **Script `cluster-up.ps1`** idempotent: chạy lại không lỗi (PowerShell, giống tuần 6 — máy Windows)
- **"Từ máy trống" nghĩa là script phải tự làm mọi thứ đã làm tay ở tuần 6–8**: Secret (không commit vào Git), Envoy Gateway (cài bằng Helm), metrics-server (cho HPA), Job tạo DB role. Thiếu 1 cái là hệ thống không lên

### Cấu Trúc Tiêu Chuẩn

```powershell
# scripts/cluster-up.ps1 — chạy từ thư mục gốc repo infra (nơi có kind-config.yaml, k8s/, charts/)
$ErrorActionPreference = 'Stop'
$services = 'order', 'payment', 'inventory', 'notification'
$appRoot  = '..'     # thư mục chứa order-service/order-service/... — sửa theo cấu trúc repo của bạn

# 1. Cluster
if (-not ((kind get clusters) -contains 'jaka')) { kind create cluster --config kind-config.yaml }

# 2. Image
foreach ($svc in $services) {
    docker build -t "$svc-service:v1" "$appRoot/$svc-service/$svc-service"
    kind load docker-image "$svc-service:v1" --name jaka
}

# 3. Namespace + Secret (dry-run | apply = idempotent; mật khẩu lấy từ biến môi trường, không hardcode)
foreach ($ns in 'dev', 'kafka') { kubectl create namespace $ns --dry-run=client -o yaml | kubectl apply -f - }
foreach ($svc in $services) {
    $pass = [Environment]::GetEnvironmentVariable("$($svc.ToUpper())_DB_PASS")
    kubectl create secret generic "$svc-service-secret" -n dev --from-literal=SPRING_DATASOURCE_PASSWORD=$pass --dry-run=client -o yaml | kubectl apply -f -
    kubectl create secret generic "$svc-db-admin" -n dev --from-literal=password=$env:DB_ADMIN_PASS --dry-run=client -o yaml | kubectl apply -f -
}

# 4. Add-on: metrics-server, Envoy Gateway, Strimzi
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
$msArgs = kubectl get deploy metrics-server -n kube-system -o jsonpath='{.spec.template.spec.containers[0].args}'
if ($msArgs -notmatch 'kubelet-insecure-tls') {     # patch "add" chạy lần 2 sẽ thêm trùng arg → chỉ patch khi chưa có
    kubectl patch deploy metrics-server -n kube-system --type=json --patch-file k8s/metrics-server-patch.json
}
helm upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version <version> -n envoy-gateway-system --create-namespace --wait
# "kubectl create" chạy lần 2 báo AlreadyExists → kiểm tra trước thay vì nuốt lỗi bằng 2>$null
# (trong PowerShell 5.1, 2>$null trên lệnh ngoài + ErrorActionPreference=Stop sẽ DỪNG script)
if (-not (kubectl get deploy strimzi-cluster-operator -n kafka --ignore-not-found)) {
    kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
}
kubectl wait deploy/strimzi-cluster-operator -n kafka --for=condition=Available --timeout=5m

# 5. Kafka + DB/Redis (thư mục theo cấu trúc tuần 6–7)
kubectl apply -f k8s/kafka/ -n kafka
foreach ($svc in $services) { if (Test-Path "k8s/$svc-db") { kubectl apply -f "k8s/$svc-db/" } }
foreach ($svc in 'order', 'payment') { kubectl apply -f "k8s/$svc-redis/" }
foreach ($svc in $services) { if (Test-Path "k8s/$svc-db") { kubectl rollout status "statefulset/$svc-db" -n dev --timeout=5m } }
kubectl wait kafka/jaka -n kafka --for=condition=Ready --timeout=10m

# 6. App (tên release = tên service, như tuần 8) + Gateway
foreach ($svc in $services) {
    helm upgrade --install "$svc-service" charts/spring-service -n dev -f "charts/spring-service/values/$svc-dev.yaml"
}
kubectl apply -f k8s/gateway/
kubectl wait --for=condition=Available deploy --all -n dev --timeout=10m
```

> Job tạo role (tuần 7) nằm trong `k8s/<svc>-db/` nên đi cùng bước 5. Chạy script lần 2 khi Job đã xong: `apply` Job đã tồn tại với spec không đổi → `unchanged`, không lỗi; nếu Job đã bị TTL xóa thì nó chạy lại — vô hại vì SQL idempotent.
>
> `$ErrorActionPreference = 'Stop'` **không** dừng script khi lệnh ngoài (`kubectl`, `helm`) lỗi trong Windows PowerShell 5.1 — chỉ áp dụng cho cmdlet. Muốn dừng ngay, kiểm tra `$LASTEXITCODE` sau các bước quan trọng.

**Output cụ thể:**
- `scripts/cluster-up.ps1` + `scripts/cluster-down.ps1`
- Chứng minh:
  - Từ máy trống (`kind delete cluster --name jaka`) → `cluster-up.ps1` → toàn bộ hệ thống chạy, không thao tác tay
  - `scripts/saga-demo.ps1` pass cả 3 kịch bản **trên kind** (qua Gateway, port-forward `8090` như tuần 7)
  - Screenshot `kubectl get pods -A`

---

## Chủ Đề 4: Tổng Kết Giai Đoạn Tháng 2 + 3

### Kiến Trúc Cuối Giai Đoạn

```
Laptop :8090 (port-forward)
   │
   ▼
Envoy Gateway (HTTPRoute: /api/orders, /api/payments, /api/inventory, /api/notifications)
   │
   ├─► order-service (HPA 2–6) ──┐
   ├─► payment-service (HPA) ────┤        ┌─────────── Kafka (Strimzi, KRaft) ───────────┐
   ├─► inventory-service ────────┼──────► │ order-events | inventory-events | payment-events │
   └─► notification-service ─────┘        │                 + *-dlt                          │
         │                                └──────────────────────────────────────────────────┘
         ▼
   StatefulSets: order-db, payment-db, inventory-db, notification-db (PVC) + order-redis, payment-redis

Terraform (jaka_modularized, không apply): modules/network, security, storage, alb, postgres, redis, service, observability, eks
CI: GitHub Actions — fmt, validate, tflint, checkov, terraform test
```

### Tiến Độ Theo Tuần

| Tuần | Tập trung | Output chính | Repo |
|---|---|---|---|
| 5 | Terraform nâng cao | Project mới jaka_modularized: module, backend S3 + lock, dev/prod, test, CI | jaka_modularized |
| 6 | K8s cơ bản | 2 service trên kind, ConfigMap/Secret | springboot-k8s |
| 7 | K8s stateful + traffic | StatefulSet, Job, probes, Gateway, HPA | springboot-k8s |
| 8 | Helm + Kafka + SD1 | Chart dùng chung, Strimzi, event đầu tiên | springboot-k8s |
| 9 | Event-driven + SD2 | 4 service, Outbox, idempotent, DLT, CQRS | springboot-event-driven |
| 10 | Saga + EKS | Saga 3 nhánh, module EKS, deploy 1 lệnh | springboot-event-driven + jaka_modularized |

### So Với Roadmap Gốc

| Roadmap gốc | Giai đoạn gộp | Ghi chú |
|---|---|---|
| Tuần 5–6 Terraform (2 tuần) | Tuần 5 (1 tuần) | Terraform cơ bản đã xong ở tháng 1 |
| Tuần 7–8 K8s cơ bản | Tuần 6–7 | |
| Tuần 9–10 Helm + EKS | Tuần 8 (Helm) + tuần 10 (EKS Terraform) | EKS không apply được vì không có AWS |
| Tuần 11–12 System Design + Kafka + Saga | Tuần 8–10 (song song) | |
| **8 tuần** | **6 tuần** | **Dư 2 tuần** → dồn cho Tháng 5–6 (AI) hoặc buffer |

### Checkpoint Giai Đoạn

- [ ] Terraform: module + remote backend + `terraform test` + CI xanh
- [ ] K8s: giải thích được vòng đời Pod → ReplicaSet → Deployment → Service
- [ ] Hệ thống 4 microservice chạy trên kind: Gateway, HPA, StatefulSet
- [ ] Kafka event-driven: Outbox + idempotent consumer + DLT
- [ ] Saga xử lý đúng 3 nhánh + timeout
- [ ] Module EKS viết xong, hiểu khác biệt kind vs EKS
- [ ] Trả lời được các câu System Design: REST/gRPC, LB, scalability, caching, CAP, event-driven, Saga, CQRS
- [ ] 3 repo có README đầy đủ + screenshot

### Câu Hỏi Phỏng Vấn Tự Luyện (Senior Backend)

1. Thiết kế luồng đặt hàng qua 4 service, đảm bảo không trừ kho sai khi thanh toán lỗi.
2. Vì sao không dùng distributed transaction (2PC)?
3. Consumer nhận trùng message thì xử lý thế nào?
4. Làm sao đảm bảo ghi DB và gửi event nhất quán?
5. Choreography vs Orchestration — khi nào chọn cái nào?
6. Pod bị CrashLoopBackOff — bạn debug theo thứ tự nào?
7. readiness vs liveness probe — cấu hình sai gây hậu quả gì?
8. Terraform state bị khóa/bị hỏng — xử lý thế nào?

---

## Portfolio Cuối Giai Đoạn

```
GitHub
├── springboot-docker                  (tháng 1, tuần 1)
├── springboot-aws-networking          (tháng 1, tuần 2)
├── springboot-aws-compute-database    (tháng 1, tuần 3–4)
├── jaka_modularized                   (tuần 5, 10)  ← module + test + CI + EKS
├── springboot-k8s                     (tuần 6–8)    ← K8s + Helm + Kafka
└── springboot-event-driven            (tuần 9–10)   ← 4 service + Saga
```

**README tổng (`springboot-event-driven`) phải có:**
1. Sơ đồ kiến trúc cuối giai đoạn
2. Event catalog + sơ đồ sequence Saga 3 nhánh
3. Cách chạy: `.\scripts\cluster-up.ps1` → `.\scripts\saga-demo.ps1`
4. Link sang repo Terraform và K8s
5. Mục "Nếu có AWS": các bước apply module EKS + cài AWS LB Controller + chuyển Gateway sang ALB

---

## 📋 Quick Reference — Tuần 10

| Khái niệm | Một câu |
|---|---|
| Saga | Chuỗi local transaction + bù trừ thay cho transaction phân tán |
| Compensating transaction | Hành động "hoàn tác" nghiệp vụ, phải idempotent |
| Choreography | Service tự nghe event và phản ứng |
| Orchestration | 1 bộ điều phối gửi command từng bước |
| EKS managed node group | ASG EC2 do EKS quản lý |
| IRSA / Pod Identity | IAM role cho từng Pod |
| AWS Load Balancer Controller | Ingress/Gateway → ALB thật |

## 🎯 Key Learnings

- [ ] Saga 3 nhánh + timeout chạy đúng trên kind
- [ ] Module EKS pass validate/test trong CI
- [ ] Toàn bộ hệ thống dựng bằng 1 script
- [ ] Hoàn thành checkpoint giai đoạn

**Giai đoạn tiếp theo → Tháng 4: CI/CD (GitHub Actions → image → kind/EKS), ArgoCD, Prometheus, Grafana, Loki, Tracing** 🚀
