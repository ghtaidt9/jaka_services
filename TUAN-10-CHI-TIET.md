# Tuần 10 chi tiết — Saga, EKS (Terraform) & Tổng Kết Giai Đoạn Tháng 2 + 3

> Giai đoạn gộp Tháng 2 + 3 (Tuần 5–10). **Mọi bước làm + kiểm tra được không cần AWS** — Saga chạy trên kind; EKS viết bằng Terraform, kiểm tra bằng `terraform validate` / `terraform test` (mock) / `helm template` / `kubectl --dry-run=server`. Chỉ Bước 7b của Chủ đề 4 cần tài khoản AWS (tốn tiền theo giờ, `destroy` ngay sau khi chứng minh).
>
> **Cách đọc:** đọc **Bản Đồ Hạ Tầng** trước để biết toàn cảnh (có gì, vì sao, nối với nhau thế nào), rồi làm Chủ đề 1 → 4 theo thứ tự. Mỗi bước có: file nào → viết gì → giá trị lấy từ đâu → vì sao → lệnh kiểm tra → lỗi hay gặp.

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
- Đưa **4 service lên EKS**: RDS/ElastiCache thay StatefulSet, ECR, secret qua External Secrets, NLB thay port-forward (đúng output tháng 3 của `roadmap-6-thang.md`)
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

## Bản Đồ Hạ Tầng (đọc trước Chủ đề 2–4)

### 1. Ba tầng × ba môi trường

Mọi thứ trong repo thuộc 1 trong 3 tầng. Code app **không đổi** giữa các môi trường — chỉ tầng dưới đổi:

| Tầng | Câu hỏi nó trả lời | kind (laptop) | AWS EC2 (tháng 1, `stack/`) | AWS EKS (tuần 10, `envs/dev-eks`) |
|---|---|---|---|---|
| **Hạ tầng** (infrastructure) | Máy, mạng, DB nằm ở đâu? | Container Docker đóng vai node | VPC + ASG EC2 + RDS + ElastiCache | VPC + **EKS** (control plane + node group) + RDS ×4 + ElastiCache + ECR |
| **Nền tảng** (platform) | Ai đưa traffic vào, ai cấp ổ đĩa, ai cấp secret? | Envoy Gateway, Strimzi, metrics-server, `kubectl create secret`, port-forward | ALB, user-data đọc SSM | Envoy Gateway, Strimzi, metrics-server + **LB Controller** (NLB), **EBS CSI** (ổ EBS), **ESO** (secret) |
| **Ứng dụng** (application) | 4 service chạy thế nào? | Helm chart `spring-service` + `values/<svc>-dev.yml` | Docker container trên EC2 | **Cùng chart** + `values/<svc>-eks.yml` |

### 2. Đường đi trên EKS

**Request của người dùng:**

```
Internet
  │
  ▼
NLB (public subnet, do LB Controller tạo)          ← Tầng 4: chỉ chuyển TCP, không đọc URL
  │
  ▼
Envoy Gateway Pod (ns envoy-gateway-system)        ← Tầng 7: đọc path, HTTPRoute /api/orders → order-service
  │
  ▼
Service order-service :8081 → Pod order-service (ns dev, private subnet, IP thật trong VPC nhờ VPC CNI)
  │
  ├──► RDS order-db       (5432, TLS)   ← SG rds mở cho SG node (data-access.tf)
  ├──► ElastiCache Redis  (6379, TLS + AUTH)
  └──► Kafka jaka (Strimzi, trong cluster, ổ EBS gp3)
```

> Tháng 1: ALB vừa là cửa vào **vừa** đọc path (`/api/orders*` → target group order). EKS: Envoy đã đọc path (HTTPRoute) → load balancer của AWS chỉ cần chuyển TCP → **NLB**, rẻ và đơn giản hơn ALB. `HTTPRoute` chạy y hệt trên kind.

**Mật khẩu (không ai gõ tay):**

```
Terraform (random_password) / RDS (manage_master_user_password)
  → Secrets Manager (mã hóa bằng KMS key của modules/security)
  → ESO Pod (quyền IAM qua Pod Identity) đọc theo ExternalSecret
  → k8s Secret <svc>-service-secret / <svc>-db-admin  (cùng tên, cùng key như kind)
  → Pod app (envFrom) và Job tạo role (secretKeyRef)
```

**Image:**

```
docker build trên laptop → docker push lên ECR (modules/ecr) → node EKS pull (quyền ECR có sẵn trong role của node group)
```

### 3. Danh mục Terraform — `envs/dev-eks`

| Thành phần | Là gì | Giải quyết vấn đề gì | File | Bước |
|---|---|---|---|---|
| `module "network"` | VPC, 2 public + 2 private subnet, NAT, 4 security group | Chỗ để mọi thứ khác nằm. Private subnet `/20` vì VPC CNI cấp **1 IP thật cho mỗi Pod**. Subnet có tag `kubernetes.io/role/elb` để LB Controller biết đặt NLB ở đâu | `main.tf` → `modules/network` | CĐ2 B1, B4 |
| `module "eks"` | Control plane (AWS quản lý) + managed node group (EC2 `t3.medium` ×2–4) + 5 add-on của EKS | Có cluster Kubernetes thật mà không phải tự vận hành apiserver/etcd | `main.tf` → `modules/eks` | CĐ2 B2 |
| ↳ add-on `vpc-cni` | Plugin mạng | Pod có IP trong VPC → nói chuyện thẳng với RDS/Redis | `modules/eks/main.tf` | CĐ2 B2 |
| ↳ add-on `eks-pod-identity-agent` | DaemonSet trên mỗi node | Đổi "Pod dùng ServiceAccount X" thành credential IAM tạm thời cho Pod đó | `modules/eks/main.tf` | CĐ2 B2 |
| ↳ add-on `aws-ebs-csi-driver` + role `ebs_csi` | Driver tạo/gắn ổ EBS | PVC (Kafka) được cấp ổ đĩa thật. Thiếu role → PVC `Pending` mãi | `modules/eks/main.tf` | CĐ4 B3a |
| `module "security"` | 1 KMS key | Mã hóa mọi secret trong Secrets Manager bằng key của mình, xoay vòng tự động | `main.tf` → `modules/security` | CĐ4 B2a |
| `module "storage"` | S3 bucket (versioning, chặn public, lifecycle theo prefix) | Giữ cho `envs/dev-eks` giống `stack/`. **Chưa service nào dùng trên EKS** | `main.tf` → `modules/storage` | CĐ4 B2a |
| `module "ecr"` | 4 repository + lifecycle policy | Node EKS không đọc được image trên laptop (khác `kind load`) → image phải nằm trong registry | `main.tf` → `modules/ecr` | CĐ4 B1, B2a |
| `module "redis"` | 1 ElastiCache (TLS + AUTH token), token trong Secrets Manager | Redis managed thay StatefulSet: AWS lo vá lỗi, failover. Dùng chung cho order + payment | `main.tf` → `modules/redis` | CĐ4 B2b |
| `module "postgres"` ×4 | 1 RDS mỗi service; admin password **do RDS quản lý**; app password sinh bằng `random_password` | Database-per-service, có backup/vá lỗi. Mật khẩu admin không bao giờ nằm trong state | `main.tf` → `modules/postgres` | CĐ4 B2b |
| 2 `aws_vpc_security_group_ingress_rule` | Mở 5432 (RDS) và 6379 (Redis) cho SG của node EKS | SG `rds`/`redis` tháng 1 chỉ mở cho SG `ec2`. Pod đi ra bằng SG của node | `data-access.tf` | CĐ4 B2c |
| `pod_trust` (data) | Trust policy: "dịch vụ `pods.eks.amazonaws.com` được nhận role" | Dùng chung cho **mọi** role của Pod (4 app, LB Controller, ESO) | `pod-identity.tf` | CĐ2 B6 |
| Role + association ×4 (app) | 1 IAM role / service, gắn với ServiceAccount `<svc>-service` | Mỗi Pod có quyền **riêng** — sửa lỗi tháng 1: instance profile gắn cho cả máy, service nào trên máy cũng có mọi quyền | `pod-identity.tf` | CĐ2 B6 |
| Role LB Controller | Policy JSON chính thức + role + association | Controller gọi được API ELB/EC2 để tạo NLB | `addons-iam.tf`, `policies/` | CĐ4 B3b |
| Role ESO | Policy inline: đọc đúng 9 secret + `kms:Decrypt` | ESO đọc được Secrets Manager, không đọc được gì khác | `addons-iam.tf` | CĐ4 B3c |
| `outputs.tf` | Region, cluster, ECR URL, endpoint DB/Redis, ARN secret | Giá trị chỉ có **sau** `apply` → script đọc bằng `terraform output`, không ai gõ tay | `outputs.tf` | CĐ4 B2d |

**Không** gọi `modules/service`, `modules/alb`, `modules/observability` của tháng 1: Deployment thay ASG, NLB + Envoy thay ALB, Prometheus (tháng 4) thay CloudWatch agent.

### 4. Danh mục Kubernetes trên EKS

| Object | Là gì | Giải quyết vấn đề gì | File / ai cài | Bước |
|---|---|---|---|---|
| AWS Load Balancer Controller | Controller (Helm, `kube-system`) | Biến Service `LoadBalancer` thành NLB thật. Kubernetes tự nó không tạo được tài nguyên AWS | `eks-up.sh` (helm) | CĐ4 B3b, B7a |
| External Secrets Operator | Controller + webhook + 3 CRD (Helm, `external-secrets`) | Đồng bộ Secrets Manager → k8s Secret. Chart app không cần biết AWS | `eks-up.sh` (helm) | CĐ4 B3c, B4 |
| Envoy Gateway | Controller Gateway API + Pod Envoy | Đọc `HTTPRoute`, định tuyến theo path — giống hệt kind | `eks-up.sh` (helm) | CĐ3, CĐ4 B5d |
| Strimzi + `Kafka`/`KafkaNodePool`/`KafkaTopic` | Operator tạo Kafka từ CR | Kafka trong cluster (MSK đắt cho dev) | `k8s/kafka/` | CĐ3 |
| metrics-server | Thu CPU/RAM của Pod | HPA cần số liệu để scale | `eks-up.sh` | CĐ3 |
| `StorageClass gp3` (default) | "Loại ổ đĩa" cho PVC | PVC Kafka không ghi `class:` → cần 1 class **mặc định** gọi EBS CSI | `k8s/eks/storageclass-gp3.yml` | CĐ4 B5c |
| `ClusterSecretStore` | Cách ESO nói chuyện với AWS (region; quyền = của Pod ESO) | 1 store dùng cho mọi namespace | `k8s/eks/cluster-secret-store.yml` | CĐ4 B4a |
| `ExternalSecret` ×8 | "Lấy key X từ Secrets Manager → ghi vào k8s Secret Z" | Tạo đúng 2 Secret/service mà kind đang dùng | `k8s/eks/external-secrets.yml.tpl` | CĐ4 B4b |
| `Job` ×4 | `psql` tạo `<svc>_user` trong RDS | `modules/postgres` chỉ sinh mật khẩu, **không** tạo user trong DB | `k8s/eks/create-app-role.yml.tpl` | CĐ4 B5b |
| `EnvoyProxy nlb` | Annotation cho Service của Envoy | Bảo LB Controller tạo NLB **internet-facing**, target = IP Pod | `k8s/eks/envoyproxy-nlb.yml` | CĐ4 B5d |
| `GatewayClass`/`Gateway`/`HTTPRoute` | Cửa vào + luật định tuyến theo path | Dùng chung với kind, không sửa | `k8s/gateway.yml` | CĐ3 |
| Deployment, Service, ConfigMap, ServiceAccount, HPA | 1 release Helm / service | App chạy, có tên DNS, có config, có danh tính IAM (SA), tự scale | `charts/spring-service` + `values/<svc>-eks.yml` | CĐ4 B5a |
| Mailpit | SMTP giả | notification-service gửi mail không cần SMTP thật | `k8s/notification-service/mailpit.yml` | CĐ4 B7a |

### 5. Thứ tự phụ thuộc (vì sao script đi theo thứ tự này)

```
terraform apply (VPC → EKS → RDS/Redis/ECR → IAM role + association)
  │
  ├─► add-on Helm: LB Controller (webhook phải chạy trước mọi Service LoadBalancer)
  │                ESO (CRD phải có trước ClusterSecretStore/ExternalSecret)
  │                Envoy Gateway (CRD trước gateway.yml), Strimzi (CRD trước Kafka)
  ├─► StorageClass gp3 (trước PVC Kafka) · ClusterSecretStore (trước ExternalSecret)
  ├─► ExternalSecret → k8s Secret (trước Job và app)
  ├─► Job tạo <svc>_user (trước khi app login DB)
  ├─► Kafka Ready (trước app)
  ├─► helm 4 app
  └─► Gateway + EnvoyProxy → NLB có DNS
```

Quy tắc chung: **CRD trước CR**, **Secret trước Pod đọc nó**, **quyền IAM trước controller gọi AWS**.

### 6. Khuôn lặp lại — gặp lại nhiều lần trong code

**Nối module bằng output → input:** module chỉ "nhìn thấy" biến của nó; muốn dùng thứ module khác tạo thì module kia phải `output`, root truyền vào:

```hcl
module "redis" {
  private_subnet_ids = module.network.private_subnet_ids   # output của network → input của redis
  kms_key_arn        = module.security.kms_key_arn
}
```

**`for_each` trên map → output là map:** 1 khối tạo N bản, truy cập bằng `[key]`:

```hcl
module "postgres" {
  for_each = local.apps          # { order = {...}, payment = {...}, ... }
  service  = each.key            # "order"
  db_name  = each.value.db_name  # "orderdb"
}

output "db_endpoints" {
  value = { for k, db in module.postgres : k => db.address }
}
# viết tay = { order = module.postgres["order"].address, payment = module.postgres["payment"].address, ... }
```

**IAM role cho 1 Pod = 3 mảnh:**

```
aws_iam_role                      assume_role_policy = pod_trust.json   → AI được nhận role (dịch vụ Pod Identity)
+ quyền                           policy JSON (inline hoặc attachment)  → ĐƯỢC LÀM GÌ
+ aws_eks_pod_identity_association namespace + service_account          → POD NÀO nhận
```

**Giá trị chỉ có sau `apply`:** `outputs.tf` → `terraform output -json` → `jq` → `helm --set` hoặc `envsubst` vào template. Không bao giờ chép tay vào file.

**Lệnh chạy lại được (idempotent):** `kubectl create ... --dry-run=client -o yaml | kubectl apply -f -`, `helm upgrade --install`, lệnh không có bản idempotent thì **kiểm tra trước rồi mới chạy**.

**Template YAML cho `envsubst`:** `---` ở dòng 1 (các bản render nối nhau) · `${VAR}` viết dạng khối, không trong `{ }` · luôn liệt kê biến: `envsubst '${SVC} ${X}'` để `$` khác giữ nguyên.

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
- Saga choreography hoàn chỉnh 3 nhánh (thành công, hết hàng, thanh toán lỗi) + timeout + hoàn tiền khi `PaymentCompleted` đến muộn
- Chứng minh bằng `jaka_infrastructure/scripts/saga-demo.sh` (bash + `curl` + `jq`, chạy qua Gateway; `BASE_URL` đổi được để chạy cả trên EKS):
  - Thành công: order `CONFIRMED`, kho giảm, email "thành công"
  - Hết hàng: order `CANCELLED`, kho không đổi
  - Thanh toán lỗi: order `CANCELLED`, kho **trả về như cũ**, reservation `RELEASED`
  - `--timeout`: tắt payment-service 6 phút → order tự `CANCELLED` do timeout, kho được trả
- Event mới của saga (`OrderCancelled`, `RefundRequested`, `PaymentRefunded`) có trong `docs/event-catalog.md`
- Sơ đồ sequence các nhánh trong README

---

## Chủ Đề 2: EKS — Viết Terraform, Hiểu Khác Biệt Với kind

> Mọi đường dẫn trong chủ đề này tính từ gốc repo `jaka_infrastructure/` (nơi đã có `modules/`, `stack/`, `envs/dev/`, `bootstrap/`, `.github/workflows/terraform.yml`).

### Nội Dung Phải Học

- **EKS (Elastic Kubernetes Service)**: AWS quản lý control plane (apiserver, etcd), bạn chỉ quản lý worker node
- **Thành phần**:
  - **Cluster** (control plane) — tính phí theo giờ, kể cả khi không có Pod
  - **Managed Node Group**: ASG các EC2 do EKS quản lý (giống ASG của `modules/service`, nhưng 1 node chạy Pod của **nhiều** service)
  - **Fargate profile**: chạy Pod không cần node, mỗi Pod 1 microVM (biết khái niệm — không chạy được StatefulSet dùng EBS)
  - **VPC CNI**: mỗi Pod có IP thật trong subnet → subnet phải đủ lớn, mỗi loại EC2 có giới hạn số Pod
  - **Access entries** (thay cho `aws-auth` ConfigMap cũ): map IAM user/role → quyền K8s
  - **IRSA / EKS Pod Identity**: Pod có IAM role riêng — sửa đúng vấn đề của `modules/service/iam.tf`: instance profile gắn cho cả máy
  - **AWS Load Balancer Controller**: biến Ingress/Gateway thành ALB thật, Service `LoadBalancer` thành NLB
  - **EBS CSI driver**: StorageClass `gp3` cho PVC (thay `standard` của kind). Trên EKS chỉ Kafka còn dùng PVC — DB chuyển sang RDS (Chủ đề 4)
- **Module chuẩn**: `terraform-aws-modules/eks/aws`, bọc trong `modules/eks/` của bạn, dùng lại VPC từ `modules/network`
- **Subnet tags** cho LB controller: `kubernetes.io/role/elb = 1` (public), `kubernetes.io/role/internal-elb = 1` (private) — `modules/network` hiện **chưa có**

**kind vs EKS:**

| | kind (đang dùng) | EKS |
|---|---|---|
| Control plane | Container trên laptop (`jaka-control-plane`) | AWS quản lý, HA 3 AZ |
| Node | Container (`jaka-worker`, `jaka-worker2`) | EC2 (managed node group) |
| Pod IP | Mạng Docker (kindnet) | IP thật trong VPC (VPC CNI) |
| Gateway / LoadBalancer | Không có địa chỉ (`AddressNotAssigned`) → port-forward 8090 | NLB/ALB thật |
| Storage | `standard` (local-path) | EBS `gp3` |
| Quyền Pod gọi AWS | Không có | IRSA / Pod Identity |
| Chi phí | 0 | ~0.10 USD/giờ control plane + EC2 + NAT |

### ⚠️ Xung đột version provider — đọc trước khi viết code

| | Ràng buộc hiện tại |
|---|---|
| `modules/network/versions.tf` (và mọi module khác, `stack/`, `envs/dev/`) | `aws ~> 5.50` = `>= 5.50, < 6.0` |
| `terraform-aws-modules/eks` **v21** | cần `aws >= 6.0` |

Gọi cả `module "network"` lẫn EKS v21 trong cùng 1 root (`envs/dev-eks`) → `terraform init` báo **không tìm được version aws thỏa cả hai**.

| Cách | Làm gì | Nhận xét |
|---|---|---|
| **A (khuyên)** | Đổi `modules/network/versions.tf` thành `version = ">= 5.50"`; root `envs/dev-eks` tự pin `~> 6.0` | Đúng quy ước: **module khai báo mức tối thiểu, root mới pin**. `envs/dev` vẫn `~> 5.50`, lock file không đổi |
| B | Pin EKS module `~> 20.0` (chạy với aws 5.x) | Không đụng module cũ, nhưng v20 dùng tên input cũ (`cluster_name`, `cluster_version`, `cluster_addons`, `cluster_endpoint_public_access`) và sẽ hết được cập nhật |

> Tên input của `terraform-aws-modules/eks` đổi giữa v20 và v21. Luôn đọc trang registry của **đúng version bạn pin**.

### Cấu Trúc Tiêu Chuẩn

```
jaka_infrastructure/
├── modules/
│   ├── network/              (tuần 5 — SỬA: thêm biến subnet tags, nới version aws)
│   └── eks/                  ← MỚI (giống mọi module khác: có versions.tf)
│       ├── versions.tf
│       ├── main.tf
│       ├── variables.tf
│       ├── outputs.tf
│       └── tests/eks.tftest.hcl
├── envs/
│   ├── dev/                  (giữ nguyên — stack EC2 của tháng 1)
│   └── dev-eks/              ← MỚI (cùng khuôn với envs/dev)
│       ├── versions.tf  backend.hcl  providers.tf
│       ├── main.tf      variables.tf outputs.tf
│       ├── pod-identity.tf   (Bước 6: IAM role riêng cho từng service)
│       └── terraform.tfvars
└── .github/workflows/terraform.yml   (SỬA: thêm modules/eks, envs/dev-eks)
```

`envs/dev-eks` gọi **thẳng** `modules/network` + `modules/eks`, không qua `stack/` — `stack/` là bộ EC2 + ASG + RDS + ElastiCache của tháng 1, không liên quan EKS.

#### Bước 1 — `modules/network`: thêm subnet tags (không phá `envs/dev`)

`variables.tf` — thêm 2 biến, default rỗng nên stack cũ không đổi gì:

```hcl
variable "public_subnet_tags" {
  description = "Extra tags for the public subnets, e.g. kubernetes.io/role/elb = 1 for EKS"
  type        = map(string)
  default     = {}
}

variable "private_subnet_tags" {
  description = "Extra tags for the private subnets, e.g. kubernetes.io/role/internal-elb = 1 for EKS"
  type        = map(string)
  default     = {}
}
```

`main.tf` — `merge` vào tags hiện có của `aws_subnet.public` / `aws_subnet.private`:

```hcl
tags = merge({
  Name = "${var.name_prefix}-public-${each.value + 1}"
  Tier = "public"
}, var.public_subnet_tags)
```

`versions.tf` — cách A:

```hcl
aws = {
  source  = "hashicorp/aws"
  version = ">= 5.50"
}
```

Kiểm tra stack cũ vẫn nguyên:

```bash
cd envs/dev
terraform init -backend=false
terraform validate
```

#### Bước 2 — `modules/eks`

```hcl
# modules/eks/versions.tf — tflint preset "recommended" bắt buộc có file này
terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
    }
  }
}
```

```hcl
# modules/eks/main.tf — kiểm tra version mới nhất trên registry trước khi pin
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"

  name               = var.name_prefix          # jaka-mod-dev-eks = tên cluster (aws eks update-kubeconfig --name ...)
  kubernetes_version = var.kubernetes_version

  vpc_id     = var.vpc_id
  subnet_ids = var.private_subnet_ids        # node nằm private subnet, không có IP public

  # Pod Identity (Bước 6) thay IRSA -> không cần OIDC provider
  enable_irsa = false

  endpoint_public_access                   = true   # kubectl từ laptop gọi được API server
  enable_cluster_creator_admin_permissions = true   # người chạy apply là admin cluster (access entry)

  addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = { before_compute = true }
    eks-pod-identity-agent = { before_compute = true }
    aws-ebs-csi-driver     = {}   # Chủ đề 4 Bước 3a thêm IAM role cho nó
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

Từng khối giải quyết gì:

| Khối | Là gì | Thiếu thì sao |
|---|---|---|
| `name`, `kubernetes_version` | Tên + version control plane (AWS chạy apiserver, etcd ở 3 AZ, tính 0.10 USD/giờ) | — bắt buộc |
| `subnet_ids` = private | Node và ENI của control plane đặt ở đâu | Node ở public subnet = có IP public, lộ ra internet |
| `enable_cluster_creator_admin_permissions` | Tạo **access entry** (thay `aws-auth` ConfigMap cũ) cho IAM của người chạy `apply` | Cluster lên nhưng `kubectl` báo `Unauthorized` |
| `coredns` | DNS trong cluster (`order-db.dev.svc`, `jaka-kafka-bootstrap.kafka`) | Pod không phân giải được tên Service |
| `kube-proxy` | Luật iptables cho ClusterIP | Gọi Service không tới Pod |
| `vpc-cni` (`before_compute`) | Cấp IP VPC cho Pod. Cài **trước** node | Node lên trước CNI → `NotReady` |
| `eks-pod-identity-agent` (`before_compute`) | Đổi ServiceAccount → credential IAM | Pod Identity không hoạt động, mọi lệnh AWS `AccessDenied` |
| `aws-ebs-csi-driver` | Tạo/gắn ổ EBS cho PVC | PVC `Pending` mãi |
| `eks_managed_node_groups` | ASG EC2 do EKS quản lý (AMI, join cluster, nâng cấp cuốn chiếu) | Không có máy chạy Pod |

`variables.tf` — theo kiểu `modules/network/variables.tf` (description + validation):

```hcl
variable "node_min" {
  description = "Minimum (and initial) node count of the managed node group"
  type        = number

  validation {
    condition     = var.node_min >= 2
    error_message = "At least 2 nodes are required to survive losing one AZ."
  }
}

variable "private_subnet_ids" {
  description = "Private subnets for the nodes, from module.network"
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "EKS needs subnets in at least 2 AZs."
  }
}
# + name_prefix, kubernetes_version, vpc_id, node_instance_type, node_max (validation node_max >= node_min)
```

`outputs.tf`:

| Output | Lấy từ | Ai dùng |
|---|---|---|
| `cluster_name` | `module.eks.cluster_name` | Pod Identity association, `eks-up.sh` (`update-kubeconfig`, LB Controller) |
| `cluster_endpoint` | `module.eks.cluster_endpoint` | thông tin, `kubeconfig` |
| `node_security_group_id` | `module.eks.node_security_group_id` | `data-access.tf` (Chủ đề 4) mở RDS/Redis cho node |

Không output `oidc_provider_arn`: dự án dùng Pod Identity (Bước 6), `enable_irsa = false` → không có OIDC provider.

**IRSA vs Pod Identity** (2 cách cho Pod 1 IAM role):

| | IRSA (cũ) | Pod Identity (dùng ở đây) |
|---|---|---|
| Cần | OIDC provider của cluster + trust policy ghi URL OIDC + annotation trên ServiceAccount | add-on `eks-pod-identity-agent` + 1 resource `aws_eks_pod_identity_association` |
| Trust policy | Mỗi cluster 1 kiểu (chứa URL OIDC) | Giống nhau mọi cluster: `pods.eks.amazonaws.com` → 1 `pod_trust` dùng chung |
| Ghép SA ↔ role | Annotation `eks.amazonaws.com/role-arn` trong YAML | Ở phía AWS (Terraform), YAML không cần biết ARN |

#### Bước 3 — Test: `modules/eks/tests/eks.tftest.hcl`

**Vì sao test được không cần AWS:** `mock_provider` thay provider thật bằng bản giả — `plan` chạy hết logic Terraform (biến, validation, `for_each`, module con) nhưng không gọi API nào.

```hcl
mock_provider "aws" {
  # Module EKS đọc vài data source rồi dùng kết quả làm ARN/JSON.
  # Mock mặc định trả chuỗi ngẫu nhiên → không phải ARN/JSON hợp lệ → plan lỗi bên trong module.
  # mock_data = "data source này luôn trả giá trị cố định, đúng định dạng"
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/test"
      user_id    = "AIDATEST"
    }
  }
  mock_data "aws_iam_session_context" {
    defaults = { issuer_arn = "arn:aws:iam::123456789012:user/test" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws", dns_suffix = "amazonaws.com" }
  }
}

# Module EKS kéo thêm provider khác. Sau "terraform init", chạy "terraform providers"
# và thêm 1 mock_provider cho MỖI provider trong danh sách:
mock_provider "tls" {}
mock_provider "time" {}
mock_provider "cloudinit" {}
mock_provider "null" {}

variables {
  name_prefix        = "jaka-mod-dev-eks"
  kubernetes_version = "1.35"   # chọn bản đang "standard support" lúc bạn làm (trang EKS Kubernetes versions)
  vpc_id             = "vpc-123"
  private_subnet_ids = ["subnet-a", "subnet-b"]
  node_instance_type = "t3.medium"
  node_min           = 2
  node_max           = 4
}

run "block_node_min_less_than_2" {
  command = plan
  variables {
    node_min = 1
  }
  expect_failures = [var.node_min]       # PASS khi validation của node_min CHẶN được giá trị 1
}

run "block_1_subnet" {
  command = plan
  variables {
    private_subnet_ids = ["subnet-a"]
  }
  expect_failures = [var.private_subnet_ids]
}

run "valid" {
  command = plan                         # input đúng → plan chạy hết module EKS không lỗi
}
```

Kết quả đúng: `3 passed, 0 failed`.

> Đừng viết `assert { condition = var.node_min >= 2 }` — nó chỉ kiểm tra giá trị bạn tự đặt, luôn pass. Chứng minh đúng là **validation chặn được input sai** (`expect_failures`).
>
> Nếu `run "valid"` lỗi bên trong module EKS vì 1 data source khác trả giá trị sai định dạng: thêm `mock_data` cho data source đó (như trên), hoặc `override_module { target = module.eks }` để bỏ qua hẳn module con — bạn chỉ cần test lớp bọc của mình.

```bash
cd modules/eks
terraform init
terraform validate
terraform test
```

#### Bước 4 — `envs/dev-eks` (cùng khuôn `envs/dev`)

`versions.tf` — giống `envs/dev/versions.tf` (`required_version >= 1.10.0`, `backend "s3" {}` partial) nhưng `aws ~> 6.0` (EKS module v21 cần aws 6). Chưa cần `random` — Chủ đề 4 Bước 2 thêm lại khi gọi `redis`/`postgres`.

**Vì sao 1 env + 1 state riêng** (không thêm EKS vào `envs/dev`): `envs/dev` là stack EC2 tháng 1, pin aws 5.x; EKS cần aws 6.x. Tách state còn có nghĩa `destroy` EKS (tốn tiền theo giờ) không đụng gì tới stack cũ.

`backend.hcl` — **cùng bucket** của `bootstrap/`, **khác key**:

```hcl
bucket       = "jaka-mod-tfstate-<ACCOUNT_ID>"
key          = "dev-eks/terraform.tfstate"
region       = "ap-southeast-1"
encrypt      = true
use_lockfile = true
```

`main.tf`:

```hcl
locals {
  project     = "jaka-mod"
  environment = "dev-eks"
  name_prefix = "${local.project}-${local.environment}"
}

module "network" {
  source = "../../modules/network"

  name_prefix          = local.name_prefix
  vpc_cidr             = "10.0.0.0/16"
  azs                  = ["${var.region}a", "${var.region}b"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.32.0/20", "10.0.48.0/20"]   # /20 ≈ 4091 IP — VPC CNI cấp IP cho từng Pod
  single_nat_gateway   = true

  public_subnet_tags  = { "kubernetes.io/role/elb" = "1" }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }
}

module "eks" {
  source = "../../modules/eks"

  name_prefix        = local.name_prefix
  kubernetes_version = var.kubernetes_version
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids
  node_instance_type = "t3.medium"
  node_min           = 2
  node_max           = 4
}
```

- Private subnet của `envs/dev` là `/24` (~251 IP) — đủ cho EC2, **chật** cho EKS: `t3.medium` tối đa 17 Pod/node, mỗi Pod ăn 1 IP.
- `modules/network` vẫn tạo 4 security group của tháng 1 (`alb`, `ec2`, `rds`, `redis`) — trong env này chúng thừa nhưng vô hại; EKS tự tạo SG riêng cho cluster và node.
- `providers.tf` — chép `envs/dev/providers.tf` (có `default_tags`).

```bash
cd envs/dev-eks
terraform init -backend=false
terraform validate
```

#### Bước 5 — CI: `.github/workflows/terraform.yml`

```yaml
  test:
    strategy:
      matrix:
        dir:
          - modules/network
          # ...
          - modules/observability
          - modules/eks          # ← thêm
          - stack

  validate-envs:
    strategy:
      matrix:
        env: [dev, dev-eks]      # ← thêm
```

- **tflint** (`call_module_type = "local"`): kiểm tra `modules/eks` của bạn, **không** đi vào module registry
- **checkov**: mặc định không tải module ngoài → chỉ quét resource của bạn. Nếu bật tải module ngoài, sẽ có cảnh báo kiểu "EKS public endpoint" (`endpoint_public_access = true`) — sửa bằng `endpoint_public_access_cidrs` hoặc ghi chú `#checkov:skip=<ID>:<lý do>`
- **fmt**: `terraform fmt -recursive` trước khi push

#### Bước 6 — Pod Identity cho từng service: `envs/dev-eks/pod-identity.tf` + ServiceAccount trong chart

**Vấn đề nó sửa:** tháng 1, quyền AWS gắn vào **EC2 instance profile** (`modules/service/iam.tf`) → mọi process trên máy đều có quyền đó. Trên EKS 1 node chạy Pod của **nhiều** service → nếu dùng role của node, order-service đọc được thứ của payment-service. Pod Identity cho **mỗi ServiceAccount 1 role riêng**.

**Cơ chế (khi Pod gọi AWS SDK):**

```
Pod order-service (serviceAccountName: order-service, ns dev)
  → SDK hỏi credential ở agent trên node (add-on eks-pod-identity-agent)
  → EKS tra association: (cluster, ns dev, SA order-service) → role jaka-mod-dev-eks-order-service
  → STS AssumeRole (trust policy cho phép pods.eks.amazonaws.com) → credential tạm thời
```

**6a. `envs/dev-eks/main.tf` — thêm vào `locals`:**

```hcl
services = toset(["order-service", "payment-service", "inventory-service", "notification-service"])
```

(Chủ đề 4 Bước 2.0 đổi dòng này thành suy ra từ `local.apps` — kết quả giống hệt.)

**6b. `envs/dev-eks/pod-identity.tf` (MỚI) — 5 khối:**

```hcl
data "aws_caller_identity" "current" {}        # account ID, để viết ARN trong policy

data "aws_iam_policy_document" "pod_trust" {   # AI được nhận role: dịch vụ Pod Identity
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]   # TagSession: EKS gắn tag cluster/ns/SA vào phiên
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "pod_access" {  # ĐƯỢC LÀM GÌ: mỗi service 1 policy
  for_each = local.services
  statement {
    actions   = ["ssm:GetParameter", "ssm:GetParametersByPath"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/jaka-mod/dev-eks/${each.key}/*"]
  }
}

resource "aws_iam_role" "pod" {
  for_each           = local.services
  name               = "${local.name_prefix}-${each.key}"            # jaka-mod-dev-eks-order-service
  assume_role_policy = data.aws_iam_policy_document.pod_trust.json   # .json: resource cần chuỗi JSON, không phải object
}

resource "aws_iam_role_policy" "pod" {           # inline: policy nằm TRONG role, xóa role là xóa theo
  for_each = local.services
  role     = aws_iam_role.pod[each.key].name     # role nhận TÊN
  policy   = data.aws_iam_policy_document.pod_access[each.key].json
}

resource "aws_eks_pod_identity_association" "pod" {   # POD NÀO nhận role
  for_each        = local.services
  cluster_name    = module.eks.cluster_name
  namespace       = "dev"
  service_account = each.key                     # = tên ServiceAccount chart tạo
  role_arn        = aws_iam_role.pod[each.key].arn   # association nhận ARN
}
```

| Thuộc tính | Nhận | Vì sao |
|---|---|---|
| `aws_iam_role_policy.role` | `.name` | API `PutRolePolicy` nhận tên role |
| `aws_eks_pod_identity_association.role_arn` | `.arn` | API EKS nhận ARN |
| `assume_role_policy`, `policy` | `.json` | data source là object; resource cần chuỗi JSON |

**Quyền hiện tại là khung, chưa dùng:** app trên EKS nhận config qua env + Secret (ESO), **không** gọi SSM, và path `/jaka-mod/dev-eks/<svc>-service/*` chưa có parameter nào. Giá trị của bước này là **mỗi service đã có role riêng**: ngày app cần gọi AWS — ví dụ bật `cloudwatch.metrics.enabled=true` (order/payment có sẵn `CloudWatchMetricsConfig`) → thêm `cloudwatch:PutMetricData` vào **đúng** `pod_access` của service đó, service khác không bị ảnh hưởng.

**6c. Chart — ServiceAccount tên = tên release** (đã có trong `charts/spring-service`):

- `templates/serviceaccount.yml`: tạo SA tên `{{ include "spring-service.fullname" . }}` = tên release (`order-service`)
- `templates/deployment.yml`: `serviceAccountName:` cùng tên
- Release tên `<svc>-service` (`helm upgrade --install "$svc-service" ...`) → SA `order-service` khớp `service_account = each.key`. **Đổi tên release là Pod mất role.** Trên kind SA vẫn được tạo nhưng không ai dùng — vô hại

Kiểm tra:

```bash
cd envs/dev-eks
terraform validate
helm template order-service ../../charts/spring-service -f ../../charts/spring-service/values/order-dev.yml | grep -n "kind: ServiceAccount" -A2
```

> **Giới hạn khi không có AWS:** `validate` + `test` (mock) kiểm tra cú pháp và logic input. `envs/dev/localstack.tfvars` không giúp được ở đây: LocalStack Pro có giả lập EKS (k3s bên trong) nhưng không có managed node group thật, VPC CNI hay LB controller thật. `apply` thật + `kubectl` vào EKS để dành cho lần có tài khoản AWS — nhớ `destroy` ngay, EKS + NAT tốn tiền theo giờ.

**Output cụ thể:**
- `modules/network`: 2 biến subnet tags, `aws >= 5.50`; `envs/dev` vẫn `validate` pass
- `modules/eks/` đủ 5 file, `terraform test` pass (3 run)
- `envs/dev-eks/` gọi `network` + `eks`, `validate` pass
- CI xanh với `modules/eks` + `envs/dev-eks` trong matrix
- `README.md` của `jaka_infrastructure`: bảng kind vs EKS, giải thích Pod Identity sửa vấn đề instance profile dùng chung của `modules/service/iam.tf`

---

## Chủ Đề 3: Deploy Toàn Bộ Lên kind Bằng 1 Lệnh

### Nội Dung Phải Học

- **Gom lại các bước tay của `LOCAL-K8S-SETUP.md`** (Step 1–8) thành 1 script — không phát minh gì mới, chỉ tự động hóa và làm cho chạy lại được
- **Idempotent**: chạy lần 2 không lỗi, không tạo trùng
  - `kubectl create ... --dry-run=client -o yaml | kubectl apply -f -` thay cho `kubectl create`
  - `helm upgrade --install` thay cho `helm install`
  - Lệnh không có bản idempotent (Strimzi install, metrics-server patch) → **kiểm tra trước** rồi mới chạy
- **Thứ tự phụ thuộc**: CRD trước CR (Envoy Gateway trước `gateway.yml`, Strimzi trước `kafka-single-node.yml`), Kafka + DB Ready trước app. App vẫn tự retry kết nối, nên không cần ép thứ tự cứng cho mọi thứ
- **Namespace**: `dev` (app, DB, Redis, Mailpit, Gateway), `kafka`, `envoy-gateway-system`
- **"Từ máy trống"** = script tự làm mọi thứ đã làm tay ở tuần 6–9: Secret (không commit vào Git), Envoy Gateway, metrics-server, Strimzi, Job tạo DB role, image của 4 service. Thiếu 1 cái là hệ thống không lên
- Máy đang dùng là **Linux** → viết **bash** với `set -euo pipefail`: lệnh nào lỗi là dừng ngay (không có cái bẫy `$ErrorActionPreference` + lệnh ngoài của PowerShell 5.1)

### Bản đồ: thứ gì trong repo được dùng ở bước nào

| Bước | File / lệnh có sẵn trong `jaka_infrastructure` |
|---|---|
| 1. Cluster | `kind-config.yaml` (tên `jaka`, 1 control-plane + 2 worker, host 8080 → 30080) |
| 2. Image | `../<svc>-service/<svc>-service/Dockerfile` |
| 3. Namespace + Secret | Tên Secret mà manifest/chart đang đọc: `<svc>-service-secret` (key `SPRING_DATASOURCE_PASSWORD`), `<svc>-db-admin` (key `password`) |
| 4. Add-on | `k8s/metrics-server-patch.json`; metrics-server `v0.9.1`; Envoy Gateway `v1.9.2`; Strimzi `1.3.1` (Kafka `4.3.1`) |
| 5. Kafka | `k8s/kafka/kafka-single-node.yml` (không ghi namespace → `-n kafka`), `k8s/kafka/topics.yml` (6 topic) |
| 6. DB / Redis / Job / Mailpit | `k8s/<svc>-service/` — mỗi thư mục có `<svc>-db.yml` + `create-app-role-job.yml`; order/payment thêm `<svc>-redis.yml`; notification thêm `mailpit.yml` |
| 7. App | `charts/spring-service` + `charts/spring-service/values/<svc>-dev.yml` (đuôi **`.yml`**), release = `<svc>-service` |
| 8. Gateway | `k8s/gateway.yml` (GatewayClass `eg`, Gateway `web`, HTTPRoute 4 path) |

### Cấu Trúc Tiêu Chuẩn

```
jaka_infrastructure/
└── scripts/
    ├── cluster-up.sh      ← MỚI
    ├── cluster-down.sh    ← MỚI
    └── redeploy.sh        ← MỚI (tuỳ chọn: build + load + upgrade 1 service khi sửa code)
```

```bash
#!/usr/bin/env bash
# scripts/cluster-up.sh — chạy từ gốc jaka_infrastructure/
set -euo pipefail                            # lệnh lỗi / biến chưa gán / lỗi giữa pipe → dừng ngay
cd "$(dirname "$0")/.."                      # chạy từ đâu cũng được: nhảy về gốc repo
if [[ -f .env ]]; then                       # mật khẩu để trong .env (đã .gitignore), không commit
  set -a; source .env; set +a                # set -a: mọi biến đọc vào đều được export
fi

services=(order payment inventory notification)
app_root=..                                  # nơi có order-service/order-service/...
tag=${TAG:-dev-$(date +%s)}                  # tag mới mỗi lần: chart dùng IfNotPresent

# Thiếu biến nào là dừng ngay, trước khi tạo gì
for svc in "${services[@]}"; do
  for suffix in DB_PASS DB_ADMIN_PASS; do
    name="${svc^^}_${suffix}"                # ${svc^^} = viết hoa → ORDER_DB_PASS
    [[ -n "${!name:-}" ]] || { echo "missing env $name" >&2; exit 1; }   # ${!name} = giá trị của biến có TÊN là $name
  done
done

# 1. Cluster
kind get clusters | grep -qx jaka || kind create cluster --config kind-config.yaml

# 1b. Image bên thứ 3: pull trên máy 1 lần, nạp vào node (node kind pull chậm / Docker 29 lỗi kind load với image đa nền tảng)
cache_dir=${TMPDIR:-/tmp}/kind-images
mkdir -p "$cache_dir"
for img in postgres:16 redis:7-alpine axllent/mailpit:latest \
           quay.io/strimzi/kafka:1.3.1-kafka-4.3.1 \
           quay.io/strimzi/operator:1.3.1 \
           registry.k8s.io/metrics-server/metrics-server:v0.9.1 \
           docker.io/envoyproxy/gateway:v1.9.2; do
  docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img"
  f="$cache_dir/$(echo "$img" | tr '/:' '__').tar"
  docker save --platform linux/amd64 "$img" -o "$f"   # chỉ lấy bản amd64 → tránh lỗi "content digest not found"
  kind load image-archive "$f" --name jaka
done

# 2. Image của 4 service
for svc in "${services[@]}"; do
  docker build -t "$svc-service:$tag" "$app_root/$svc-service/$svc-service"
  kind load docker-image "$svc-service:$tag" --name jaka
done

# 3. Namespace + Secret (dry-run | apply = idempotent)
for ns in dev kafka; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
done
for svc in "${services[@]}"; do
  up=${svc^^}
  app_var="${up}_DB_PASS"; admin_var="${up}_DB_ADMIN_PASS"
  kubectl create secret generic "$svc-service-secret" -n dev \
    --from-literal=SPRING_DATASOURCE_PASSWORD="${!app_var}" --dry-run=client -o yaml | kubectl apply -f -
  kubectl create secret generic "$svc-db-admin" -n dev \
    --from-literal=password="${!admin_var}" --dry-run=client -o yaml | kubectl apply -f -
done

# 4. Add-on — pin version, không dùng "latest" (latest đổi theo thời gian → script hôm nay chạy, mai hỏng)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.1/components.yaml
if ! kubectl get deploy metrics-server -n kube-system \
     -o jsonpath='{.spec.template.spec.containers[0].args}' | grep -q kubelet-insecure-tls; then
  kubectl patch deploy metrics-server -n kube-system --type=json --patch-file k8s/metrics-server-patch.json
fi
helm upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version v1.9.2 \
  -n envoy-gateway-system --create-namespace --wait
if [[ -z "$(kubectl get deploy strimzi-cluster-operator -n kafka --ignore-not-found -o name)" ]]; then
  curl -sL https://github.com/strimzi/strimzi-kafka-operator/releases/download/1.3.1/strimzi-cluster-operator-1.3.1.yaml \
    | sed 's/namespace: myproject/namespace: kafka/' \
    | kubectl create -f - -n kafka
fi
kubectl wait deploy/strimzi-cluster-operator -n kafka --for=condition=Available --timeout=5m

# 5. Kafka
kubectl apply -f k8s/kafka/kafka-single-node.yml -n kafka
kubectl wait kafka/jaka -n kafka --for=condition=Ready --timeout=10m
kubectl apply -f k8s/kafka/topics.yml

# 6. DB, Redis, Job tạo role, Mailpit
for svc in "${services[@]}"; do kubectl apply -f "k8s/$svc-service/"; done
for svc in "${services[@]}"; do
  kubectl rollout status "statefulset/$svc-db" -n dev --timeout=5m
done

# 7. App
for svc in "${services[@]}"; do
  helm upgrade --install "$svc-service" charts/spring-service -n dev \
    -f "charts/spring-service/values/$svc-dev.yml" --set image.tag="$tag"
done

# 8. Gateway
kubectl apply -f k8s/gateway.yml
kubectl wait --for=condition=Accepted gateway/web -n dev --timeout=2m
kubectl wait --for=condition=Available deploy --all -n dev --timeout=10m

echo "Done. Mở Gateway ở terminal khác:"
echo '  svc=$(kubectl get svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web -o name)'
echo '  kubectl port-forward -n envoy-gateway-system $svc 8090:80'

# 9. Dọn image cũ của 4 service trên node — CHỈ image dev-*, giữ tag vừa deploy
for n in jaka-control-plane jaka-worker jaka-worker2; do
  docker exec "$n" crictl images -o json \
    | jq -r '.images[].repoTags[]' \
    | grep -E '/(order|payment|inventory|notification)-service:dev-' \
    | grep -v ":$tag$" \
    | xargs -r docker exec "$n" crictl rmi || true
done
```

```bash
#!/usr/bin/env bash
# scripts/cluster-down.sh
set -euo pipefail
kind delete cluster --name jaka
```

Ghi chú:
- **`.env`** (gốc `jaka_infrastructure/`, có trong `.gitignore`): 8 dòng `ORDER_DB_PASS=...`, `ORDER_DB_ADMIN_PASS=...`… Đây là chỗ duy nhất trên kind có mật khẩu — trên EKS thay bằng Secrets Manager + ESO
- **Job tạo role** (`create-app-role-job.yml`) đi cùng bước 6. Chạy lại khi Job còn → `unchanged`; Job đã bị TTL (300 giây) xóa → `apply` tạo lại và chạy lại — vô hại vì SQL idempotent (`\if :role_exists ... ALTER ROLE`). Không cần `kubectl wait job`: app chưa login được thì readiness fail và tự retry
- **Strimzi** dùng `kubectl create` (CRD quá lớn cho `apply`) → phải kiểm tra trước, đừng nuốt lỗi. File release ghi namespace `myproject` → `sed` đổi thành `kafka`
- **Tag theo timestamp**: mỗi lần chạy lại script là rollout lại cả 4 service. Muốn tránh → `TAG=v5 ./scripts/cluster-up.sh`, nhưng nhớ: tag cũ + `IfNotPresent` = vẫn chạy image cũ
- **Bước 9 — dọn image có chọn lọc.** ⚠️ **Đừng** dùng `crictl rmi --prune`: nó xóa **mọi** image không có container đang chạy, kể cả image hệ thống mà kind nạp sẵn (`kindnetd`, `local-path-provisioner`…) và image vừa preload ở 1b → lần sau Pod hệ thống không khởi động lại được. Chỉ xóa đúng image `*-service:dev-*` cũ
- Không port-forward vào `8080` — `kind-config.yaml` đã map host 8080 → node control-plane

**Output cụ thể:**
- `scripts/cluster-up.sh` + `scripts/cluster-down.sh` (+ `redeploy.sh` nếu làm)
- Chứng minh:
  - Từ máy trống → toàn bộ hệ thống chạy, không thao tác tay:

```bash
./scripts/cluster-down.sh
./scripts/cluster-up.sh          # đọc .env
kubectl get pods -A
```

  - Chạy `./scripts/cluster-up.sh` **lần 2** ngay sau đó → không lỗi
  - `./scripts/saga-demo.sh` (và `--timeout`) pass **trên kind**, qua Gateway port-forward `8090`
  - Screenshot `kubectl get pods -A`

---

## Chủ Đề 4: 4 Service Trên EKS — Data Layer AWS, ECR, Secret, Load Balancer

> Roadmap tháng 3 yêu cầu **"deploy toàn bộ 4 service lên EKS, có Ingress + Gateway"** và checkpoint **"có autoscaling, ingress, giao tiếp event-driven"**. Chủ đề 2 mới dựng **cluster rỗng** (`network` + `eks`); Chủ đề 3 chạy đủ hệ thống nhưng trên **kind**. Chủ đề này nối 2 cái lại: `envs/dev-eks` có đủ thứ để 4 service chạy được trên EKS. Viết + kiểm tra không cần AWS; `apply` thật để dành cho Bước 7.

### Nội Dung Phải Học

- **Giữ dịch vụ managed của tháng 1 cho dữ liệu**: trên kind, Postgres/Redis là StatefulSet cho tiện. Trên EKS thật dùng lại **RDS** (`modules/postgres`) và **ElastiCache** (`modules/redis`) — có backup, Multi-AZ, vá lỗi; tự chạy DB trong cluster là gánh nặng vận hành không cần thiết
- **Kafka vẫn chạy trong cluster** (Strimzi, giống kind) — chỉ đổi storage sang **EBS gp3** qua EBS CSI driver. (MSK là lựa chọn managed, nhưng đắt cho dev)
- **ECR**: EKS không đọc được image trên laptop như `kind load`; image phải nằm trong registry. ECR hiện **bị nhốt trong `modules/service`** (cùng ASG/launch template) → tách ra module riêng
- **Secret từ AWS vào Pod**: **External Secrets Operator (ESO)** đọc Secrets Manager → tạo k8s Secret cùng tên/key mà chart đang dùng (`<svc>-service-secret` / `SPRING_DATASOURCE_PASSWORD`) → **chart không phải sửa**. ESO lấy quyền AWS qua **Pod Identity**
- **Mật khẩu admin RDS do AWS quản lý** (`manage_master_user_password = true` trong `modules/postgres`) → AWS tự xoay vòng, không bao giờ nằm trong state. Job tạo role đọc nó qua ESO, không qua `.env`
- **Add-on cần IAM**: EBS CSI driver và AWS Load Balancer Controller gọi AWS API → mỗi cái cần 1 IAM role qua Pod Identity. Thiếu role → PVC `Pending` mãi / Service `LoadBalancer` không có địa chỉ
- **Load balancer**: giữ nguyên Envoy Gateway + `k8s/gateway.yml` như kind. Khác biệt: Service của Envoy là `type: LoadBalancer` → **AWS Load Balancer Controller** tạo **NLB** thật → không còn port-forward 8090. Cần thêm 1 `EnvoyProxy` mang annotation (`internet-facing`, target `ip`) — thiếu thì controller bỏ qua hoặc tạo NLB internal. NLB (tầng 4) đủ vì Envoy đã định tuyến theo path (tầng 7) — khác tháng 1, ALB làm cả 2 việc
- **Autoscaling 2 tầng**: HPA scale **Pod** (bật trong `*-eks.yml`: min 1, max 3); node group scale **máy** (`node_min`..`node_max`) — cần Cluster Autoscaler hoặc Karpenter mới tự thêm node, biết khái niệm là đủ
- **Giới hạn số Pod/node**: `t3.medium` tối đa 17 Pod (mỗi Pod 1 IP, số ENI × IP/ENI có hạn). 2 node = 34 Pod, riêng hệ thống + add-on + Kafka đã ~22 → app để 1 replica lúc đầu

### kind (Chủ đề 3) → EKS: thứ gì đổi

| | kind | EKS (`envs/dev-eks`) |
|---|---|---|
| Postgres ×4 | StatefulSet `k8s/<svc>-service/<svc>-db.yml` | `module "postgres"` ×4 (RDS) |
| Redis | StatefulSet `order-redis`, `payment-redis` | `module "redis"` (ElastiCache, TLS + AUTH token) |
| Kafka | Strimzi, PVC `standard` | Strimzi, PVC `gp3` (EBS CSI) |
| Image | `docker build` + `kind load` | `docker push` lên ECR (`module "ecr"`) |
| Secret | `cluster-up.sh` đọc `.env` | ESO đọc Secrets Manager (mật khẩu do Terraform/RDS sinh) |
| Job tạo role | `psql -h order-db` | `PGHOST=<RDS endpoint>`, `PGSSLMODE=require` (template `envsubst`) |
| Helm values | `values/<svc>-dev.yml` (host = tên Service trong cluster) | `values/<svc>-eks.yml` + `--set` (host = endpoint AWS, chỉ có sau `apply`) |
| Quyền AWS của Pod | Không có | Pod Identity: app ×4, LB Controller, ESO, EBS CSI |
| Mailpit | Deployment | Giữ nguyên (SMTP giả, dev) |
| Vào hệ thống | port-forward 8090 | DNS của NLB (LB Controller tạo theo annotation trong `EnvoyProxy`) |
| Script | `scripts/cluster-up.sh` | `scripts/eks-up.sh` (cùng thứ tự bước) |

### Cấu Trúc Tiêu Chuẩn

```
jaka_infrastructure/
├── modules/
│   ├── ecr/                      ← MỚI (tách từ modules/service)                     Bước 1
│   ├── service/                  (SỬA: bỏ ECR, nhận repository_url qua biến)          Bước 1
│   ├── security/ storage/ redis/ postgres/   (SỬA: versions.tf ~> 5.50 → >= 5.50)    Bước 2.0
│   └── eks/                      (SỬA: EBS CSI có IAM role)                           Bước 3a
├── stack/main.tf, outputs.tf, moved.tf   (SỬA: gọi module "ecr" + moved {})          Bước 1
├── envs/dev-eks/
│   ├── versions.tf               (SỬA: + provider random)                             Bước 2.0
│   ├── main.tf                   (SỬA: locals.apps + security, storage, ecr, redis, postgres ×4)   Bước 2
│   ├── data-access.tf            ← MỚI: SG rule node EKS → RDS/Redis                  Bước 2c
│   ├── outputs.tf                (SỬA: ECR URL, endpoint DB/Redis, ARN secret)         Bước 2d
│   ├── addons-iam.tf             ← MỚI: role LB Controller + ESO                       Bước 3b, 3c
│   └── policies/aws-load-balancer-controller.json   ← MỚI (tải về)                   Bước 3b
├── k8s/eks/                      ← MỚI: chỉ những gì khác kind
│   ├── cluster-secret-store.yml                                                       Bước 4a
│   ├── external-secrets.yml.tpl  (template ExternalSecret, render ×4 bằng envsubst)   Bước 4b
│   ├── create-app-role.yml.tpl   (template Job tạo role trỏ RDS, render ×4)           Bước 5b
│   ├── storageclass-gp3.yml                                                           Bước 5c
│   └── envoyproxy-nlb.yml        (annotation để LB Controller tạo NLB internet-facing) Bước 5d
├── charts/spring-service/values/<svc>-eks.yml   ← MỚI ×4                              Bước 5a
└── scripts/eks-up.sh             ← MỚI (giống cluster-up.sh, bỏ phần kind)            Bước 7a
```

#### Bước 1 — `modules/ecr`: tách ECR khỏi `modules/service` (không phá `envs/dev`)

**Vì sao:** EKS cần ECR nhưng **không** cần ASG/launch template. Hiện ECR nằm trong `modules/service` → muốn có repo là phải kéo theo cả EC2. Tách ra để `stack/` (EC2) và `envs/dev-eks` (EKS) cùng gọi được.

**Trước và sau:**

```
TRƯỚC                                         SAU
module.service["order"]                       module.ecr
  ├── aws_ecr_repository.this                   ├── aws_ecr_repository.this["order"]
  ├── aws_ecr_lifecycle_policy.this             ├── aws_ecr_repository.this["payment"]
  └── launch template dùng repo ở trên          ├── aws_ecr_lifecycle_policy.this["order"]
module.service["payment"]                       └── aws_ecr_lifecycle_policy.this["payment"]
  └── (giống trên)                            module.service["order"]  ← nhận repository_url qua biến
```

Một module ECR tạo **tất cả** repo (`for_each`), thay vì mỗi `module.service` tự tạo 1 repo.

**1a. `modules/ecr/` — 4 file**

| File | Viết gì |
|---|---|
| `versions.tf` | Chép `modules/service/versions.tf`, đổi `version = ">= 5.50"` (module mới khai báo mức tối thiểu, để `envs/dev-eks` dùng aws 6.x được) |
| `variables.tf` | `name_prefix` (string) · `services` (`set(string)`, ví dụ `["order", "payment"]`) · `keep_tagged_images` (number, default 10 — chuyển từ `ecr_keep_tagged_images` của `modules/service`) |
| `main.tf` | 2 resource đã cắt sang, sửa 3 chỗ (bảng dưới) |
| `outputs.tf` | `repository_urls` = map tên service → URL: `{ for k, r in aws_ecr_repository.this : k => r.repository_url }` |

3 chỗ phải sửa trong `main.tf` vừa dán từ `modules/service`:

| Đang là | Đổi thành | Vì sao |
|---|---|---|
| (không có) | `for_each = var.services` ở **cả 2** resource | 1 repo cho mỗi service |
| `name = "${local.full_name}-service"` | `name = "${var.name_prefix}-${each.key}-service"` | `local.full_name` (= `name_prefix-service`) chỉ có trong `modules/service`. Tên phải **giữ nguyên** như cũ (`jaka-mod-dev-order-service`) — đổi tên = AWS tạo repo mới |
| `repository = aws_ecr_repository.this.name` | `repository = aws_ecr_repository.this[each.key].name` | Giờ `this` là map |
| `var.ecr_keep_tagged_images` (2 chỗ) | `var.keep_tagged_images` | Tên biến mới của module |

**1b. `modules/service/` — sửa 3 file**

| File | Sửa |
|---|---|
| `variables.tf` | Xóa `ecr_keep_tagged_images` (dòng 119). Thêm `repository_url` (string, "ECR repository URL from modules/ecr") |
| `compute.tf` dòng 79 | `image_repo = aws_ecr_repository.this.repository_url` → `image_repo = var.repository_url` |
| `outputs.tf` | Xóa output `ecr_repository_url` (repo không còn thuộc module này) |

`locals.ecr_registry` trong `main.tf` vẫn giữ — user-data dùng nó để `docker login`.

**1c. `stack/` — sửa 2 file, thêm 1 file**

`main.tf` — thêm `module "ecr"` (đặt cạnh `storage`, nó không cần module nào khác), rồi truyền URL vào `module "service"`:

```hcl
module "ecr" {
  source = "../modules/ecr"

  name_prefix = local.name_prefix
  services    = toset(keys(var.services))   # ["order", "payment"]
}

module "service" {
  # ... giữ nguyên các dòng cũ, thêm:
  repository_url = module.ecr.repository_urls[each.key]
}
```

Nếu `stack/main.tf` đang truyền `ecr_keep_tagged_images` vào `module "service"` thì chuyển dòng đó sang `module "ecr"` (đổi tên thành `keep_tagged_images`).

`outputs.tf` — `ecr_repository_urls` đang đọc từ `module.service`; đổi thành:

```hcl
value = module.ecr.repository_urls
```

`moved.tf` (MỚI) — báo Terraform rằng 4 resource **chỉ đổi địa chỉ**, không phải xóa rồi tạo lại. Thiếu file này → `apply` sẽ **xóa repo cũ (mất toàn bộ image)** rồi tạo repo mới cùng tên:

```hcl
moved {
  from = module.service["order"].aws_ecr_repository.this
  to   = module.ecr.aws_ecr_repository.this["order"]
}
moved {
  from = module.service["order"].aws_ecr_lifecycle_policy.this
  to   = module.ecr.aws_ecr_lifecycle_policy.this["order"]
}
# + 2 block y hệt cho "payment"
```

`moved` không dùng được `for_each` — mỗi service × mỗi resource là 1 block. Thêm service mới sau này **không** cần `moved` (nó chưa từng ở địa chỉ cũ).

**1d. Kiểm tra**

```bash
cd modules/ecr
terraform init -backend=false
terraform validate
```

```bash
cd ../../stack
terraform init -backend=false
terraform validate
```

```bash
cd ../envs/dev
terraform init -backend=false
terraform validate
```

Lỗi hay gặp lúc validate:
- `Reference to undeclared resource "aws_ecr_repository" "this"` trong `modules/service` → còn sót chỗ dùng repo cũ (`compute.tf`, `outputs.tf`)
- `Unsupported argument "ecr_keep_tagged_images"` ở `stack` → `stack/main.tf` vẫn truyền biến đã xóa
- `Reference to undeclared local value "full_name"` ở `modules/ecr` → chưa đổi `name`

`tests/ecr.tftest.hcl` (mock, như `modules/network`): 1 run `command = plan` với `services = ["order", "payment"]`, assert `length(aws_ecr_repository.this) == 2` và tên repo là `jaka-mod-test-order-service`. Thêm `modules/ecr` vào matrix `test` của CI.

> Không có AWS thì không thấy được `moved` hoạt động (cần state thật). Khi có AWS: `terraform plan` trong `envs/dev` phải hiện `has moved to`, **không** có dòng `destroy` nào cho ECR.

#### Bước 2 — `envs/dev-eks`: thêm data layer

**Mục tiêu:** `envs/dev-eks` tạo đủ chỗ chứa dữ liệu cho 4 service — giống `stack/` của tháng 1 nhưng **bỏ phần EC2** (`service`, `alb`, `observability`). Hầu hết là **chép khối `module` từ `stack/main.tf`** rồi đổi giá trị cho dev.

```
envs/dev-eks/main.tf
├── module "network"      (đã có)
├── module "eks"          (đã có)
├── module "security"     ← 2a  KMS key cho secret
├── module "storage"      ← 2a  S3 bucket
├── module "ecr"          ← 2a  4 repository (module Bước 1)
├── module "redis"        ← 2b  1 ElastiCache dùng chung
└── module "postgres" ×4  ← 2b  1 RDS mỗi service
envs/dev-eks/data-access.tf  ← 2c  mở RDS/Redis cho node EKS
envs/dev-eks/outputs.tf      ← 2d  giá trị cho Helm values
```

**2.0 Chuẩn bị — 3 việc, không làm thì `init`/`validate` lỗi**

| Việc | File | Vì sao |
|---|---|---|
| Đổi `version = "~> 5.50"` → `">= 5.50"` | `modules/security`, `storage`, `redis`, `postgres` — mỗi module file `versions.tf` | `envs/dev-eks` dùng aws **6.x**; module khóa `< 6.0` → `init` báo không có version nào thỏa (lỗi giống `modules/network` ở Chủ đề 2). `envs/dev` vẫn tự pin `~> 5.50` nên không bị ảnh hưởng |
| Thêm provider `random` (`~> 3.6`) | `envs/dev-eks/versions.tf` | `redis` và `postgres` sinh mật khẩu bằng `random_password` |
| Đổi `locals` | `envs/dev-eks/main.tf` | Xem dưới |

`locals` hiện có `services = ["order-service", ...]` — đó là **tên ServiceAccount** cho `pod-identity.tf`. Data layer cần **tên ngắn** (`order`) và **tên DB**. Đổi thành 1 map làm gốc, tên ServiceAccount suy ra từ nó:

```hcl
locals {
  project     = "jaka-mod"
  environment = "dev-eks"
  name_prefix = "${local.project}-${local.environment}"

  # key = tên ngắn (dùng cho DB, ECR, S3); db_name khớp k8s/<svc>-service/<svc>-db.yml
  apps = {
    order        = { db_name = "orderdb" }
    payment      = { db_name = "paymentdb" }
    inventory    = { db_name = "inventorydb" }
    notification = { db_name = "notificationdb" }
  }

  # tên ServiceAccount = tên Helm release = "<key>-service"
  services = toset([for k in keys(local.apps) : "${k}-service"])
}
```

`pod-identity.tf` vẫn dùng `local.services` như cũ → không phải sửa.

**2a. `security`, `storage`, `ecr` — không cần input từ module khác**

Chép khối tương ứng trong `stack/main.tf`, đổi `../modules` → `../../modules` và `var.services` → `local.apps`:

| Module | Input | Giá trị cho dev-eks |
|---|---|---|
| `security` | `name_prefix` | `local.name_prefix` |
| | `create_ssm_caller_user` | `false` — không có EC2 để SSM vào |
| `storage` | `name_prefix` | `local.name_prefix` |
| | `tiered_prefixes` | `[for k in keys(local.apps) : "${k}s"]` → `["orders", "payments", ...]` (giống `stack`) |
| | `force_destroy` | `true` — `destroy` xóa được bucket còn file |
| `ecr` | `name_prefix` | `local.name_prefix` |
| | `services` | `toset(keys(local.apps))` |

**2b. `redis`, `postgres` — lấy input từ `network` và `security`**

Chép từ `stack/main.tf` (dòng `module "redis"` và `module "postgres"`). Cột "Lấy từ" chính là dây nối giữa các module:

`redis` (1 cái, dùng chung):

| Input | Lấy từ / giá trị |
|---|---|
| `name_prefix` | `local.name_prefix` |
| `private_subnet_ids` | `module.network.private_subnet_ids` |
| `security_group_id` | `module.network.redis_sg_id` |
| `kms_key_arn` | `module.security.kms_key_arn` |
| `num_cache_clusters` | `1` — dev, không cần replica |
| `secret_recovery_window_in_days` | `0` — `destroy` xóa secret ngay, tạo lại được cùng tên |

`postgres` (`for_each = local.apps` → 4 RDS):

| Input | Lấy từ / giá trị |
|---|---|
| `name_prefix` | `local.name_prefix` |
| `service` | `each.key` (`order`) |
| `db_name` | `each.value.db_name` (`orderdb`) |
| `admin_username` | `"${each.key}_admin"` |
| `app_username` | `"${each.key}_user"` |
| `private_subnet_ids` | `module.network.private_subnet_ids` |
| `security_group_id` | `module.network.rds_sg_id` |
| `kms_key_id` | `module.security.kms_key_id` |
| `kms_key_arn` | `module.security.kms_key_arn` |
| `multi_az` | `false` |
| `skip_final_snapshot` | `true` |
| `deletion_protection` | `false` |
| `secret_recovery_window_in_days` | `0` |

Các input còn lại (`engine_version`, `instance_class`, `allocated_storage`, ...) có default trong `modules/postgres/variables.tf` — bỏ qua.

**2c. `data-access.tf` — cho node EKS vào RDS/Redis**

SG `rds` và `redis` (tạo trong `modules/network`) chỉ mở cho SG `ec2` của tháng 1. Pod trên EKS đi ra bằng **SG của node** (VPC CNI cấp IP từ ENI của node) → thêm 2 rule:

```hcl
resource "aws_vpc_security_group_ingress_rule" "rds_from_eks_nodes" {
  security_group_id            = module.network.rds_sg_id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "Postgres from EKS nodes"
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_eks_nodes" {
  security_group_id            = module.network.redis_sg_id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "Redis from EKS nodes"
}
```

`node_security_group_id` là output đã có của `modules/eks` (Chủ đề 2).

**2d. `outputs.tf` — giá trị cho Bước 4–5**

Thêm vào output đang có. Toàn bộ là output có sẵn của module, chỉ gom lại thành map theo service:

| Output | `value` | Dùng ở |
|---|---|---|
| `ecr_repository_urls` | `module.ecr.repository_urls` | Bước 5: `image.repository` |
| `db_endpoints` | `{ for k, db in module.postgres : k => db.address }` | Bước 5: `SPRING_DATASOURCE_URL`, Job tạo role |
| `db_admin_secret_arns` | `{ for k, db in module.postgres : k => db.admin_secret_arn }` | Bước 4: ExternalSecret `<svc>-db-admin` |
| `db_app_secret_arns` | `{ for k, db in module.postgres : k => db.app_secret_arn }` | Bước 4: ExternalSecret `<svc>-service-secret` |
| `redis_primary_endpoint` | `module.redis.primary_endpoint` | Bước 5: `SPRING_DATA_REDIS_HOST` |
| `redis_auth_secret_arn` | `module.redis.auth_secret_arn` | Bước 4 |

Bản viết tay của 1 dòng `for` ở trên, cho dễ hình dung:

```hcl
db_endpoints = {
  order        = module.postgres["order"].address
  payment      = module.postgres["payment"].address
  inventory    = module.postgres["inventory"].address
  notification = module.postgres["notification"].address
}
```

**Không** gọi `modules/service`, `modules/alb`, `modules/observability` — Deployment, NLB và Prometheus (tháng 4) thay chúng.

**2e. Kiểm tra**

```bash
cd envs/dev-eks
terraform init -backend=false -upgrade
terraform validate
```

```bash
cd ../dev
terraform init -backend=false -upgrade
terraform validate
```

Lỗi hay gặp:
- `no available releases match the given constraints` → còn module trong 2.0 chưa đổi `~> 5.50`
- `Missing required argument "tiered_prefixes"` / `"db_name"` → thiếu input bắt buộc (bảng 2a/2b)
- `Reference to undeclared local value "apps"` → chưa đổi `locals`
- `envs/dev` lỗi sau khi nới version → không thể xảy ra nếu chỉ đổi `~>` thành `>=`; nếu có thì gửi output

#### Bước 3 — IAM role cho 3 add-on: EBS CSI, Load Balancer Controller, ESO

**Vì sao:** 3 add-on này là Pod chạy trong cluster nhưng phải **gọi AWS API** — tạo ổ EBS, tạo NLB, đọc Secrets Manager. Giống app ở `pod-identity.tf`: mỗi cái cần 1 IAM role + 1 Pod Identity association. Thiếu role → add-on chạy nhưng mọi lệnh gọi AWS bị `AccessDenied`.

| Add-on | Gọi AWS để | Thiếu role thì | ServiceAccount (namespace) | Viết ở |
|---|---|---|---|---|
| EBS CSI driver | tạo/gắn/xóa ổ EBS | PVC của Kafka `Pending` mãi | `ebs-csi-controller-sa` (`kube-system`) | `modules/eks/` (addon của module) |
| AWS Load Balancer Controller | tạo NLB cho Service `LoadBalancer` | Service của Envoy không có `EXTERNAL-IP` | `aws-load-balancer-controller` (`kube-system`) | `envs/dev-eks/addons-iam.tf` |
| External Secrets Operator | đọc Secrets Manager | ExternalSecret báo `SecretSyncedError` | `external-secrets` (`external-secrets`) | `envs/dev-eks/addons-iam.tf` |

Mọi role đều dùng **chung 1 trust policy** đã có: `data.aws_iam_policy_document.pod_trust` trong `envs/dev-eks/pod-identity.tf` (tin `pods.eks.amazonaws.com`). Cùng thư mục → `addons-iam.tf` dùng lại được, không viết lại.

Mỗi role = 3 resource lặp lại khuôn của `pod-identity.tf`:

```
aws_iam_role                     (ai được nhận: pod_trust)
+ quyền: aws_iam_role_policy_attachment (managed policy) hoặc aws_iam_role_policy (inline)
+ aws_eks_pod_identity_association (ghép ServiceAccount ↔ role)
```

**3a. EBS CSI — trong `modules/eks/main.tf`**

Đặt trong module vì addon `aws-ebs-csi-driver` được khai báo ở đó, và env nào dùng `modules/eks` cũng cần nó.

Thêm 3 thứ vào `modules/eks/main.tf`:

1. Trust policy (module không thấy `pod_trust` của env → viết riêng, giống hệt):

```hcl
data "aws_iam_policy_document" "ebs_csi_trust" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}
```

2. Role + gắn managed policy của AWS (AWS viết sẵn đủ quyền cho driver, không cần tự liệt kê):

| Resource | Thuộc tính | Giá trị |
|---|---|---|
| `aws_iam_role "ebs_csi"` | `name` | `"${var.name_prefix}-ebs-csi"` |
| | `assume_role_policy` | `data.aws_iam_policy_document.ebs_csi_trust.json` |
| `aws_iam_role_policy_attachment "ebs_csi"` | `role` | `aws_iam_role.ebs_csi.name` |
| | `policy_arn` | `"arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"` |

3. Sửa dòng addon trong `module "eks"`:

```hcl
# trước
aws-ebs-csi-driver     = {}

# sau
aws-ebs-csi-driver = {
  pod_identity_association = [{
    role_arn        = aws_iam_role.ebs_csi.arn
    service_account = "ebs-csi-controller-sa"
  }]
}
```

Không có `namespace` ở đây: addon do EKS cài nên luôn ở `kube-system` (kiểm tra trong `.terraform/modules/eks/variables.tf`, biến `addons`: object chỉ có `role_arn` + `service_account`). Không cần `aws_eks_pod_identity_association` riêng — module tự tạo từ khối này.

**3b. AWS Load Balancer Controller — `envs/dev-eks/addons-iam.tf` (MỚI)**

Không có managed policy của AWS cho controller này — project phát hành sẵn 1 file JSON. Tải đúng bản khớp version chart (`v3.6.2`) vào repo:

```bash
mkdir -p envs/dev-eks/policies
curl -sL -o envs/dev-eks/policies/aws-load-balancer-controller.json   https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v3.6.2/docs/install/iam_policy.json
```

| Resource | Thuộc tính | Giá trị |
|---|---|---|
| `aws_iam_policy "lb_controller"` | `name` | `"${local.name_prefix}-lb-controller"` |
| | `policy` | `file("${path.module}/policies/aws-load-balancer-controller.json")` |
| `aws_iam_role "lb_controller"` | `name` | `"${local.name_prefix}-lb-controller"` |
| | `assume_role_policy` | `data.aws_iam_policy_document.pod_trust.json` |
| `aws_iam_role_policy_attachment "lb_controller"` | `role` / `policy_arn` | `aws_iam_role.lb_controller.name` / `aws_iam_policy.lb_controller.arn` |
| `aws_eks_pod_identity_association "lb_controller"` | `cluster_name` | `module.eks.cluster_name` |
| | `namespace` | `"kube-system"` |
| | `service_account` | `"aws-load-balancer-controller"` |
| | `role_arn` | `aws_iam_role.lb_controller.arn` |

`service_account` phải **trùng tên** ServiceAccount mà chart tạo. Chart đặt tên = tên release → cài với release `aws-load-balancer-controller` (lệnh ở Bước 7).

`aws_iam_policy` + `attachment` (2 resource) chứ không phải `aws_iam_role_policy` (inline) như app, vì file JSON này dài ~250 dòng và có thể gắn vào role khác sau này — đúng chỗ dùng managed policy tự tạo.

**3c. External Secrets Operator — thêm vào `addons-iam.tf`**

ESO cần đọc **đúng** 9 secret (4 app + 4 admin + 1 Redis) và giải mã chúng bằng KMS key của `modules/security`.

Quyền phải liệt kê **ARN cụ thể**, không dùng wildcard theo tên: secret admin do **RDS tự tạo** (`manage_master_user_password = true`), tên kiểu `rds!db-3f9a…`, không có tiền tố `jaka-mod-dev-eks/`. Các ARN đã có sẵn trong output Bước 2d:

```hcl
locals {
  eso_secret_arns = concat(
    values({ for k, db in module.postgres : k => db.app_secret_arn }),
    values({ for k, db in module.postgres : k => db.admin_secret_arn }),
    [module.redis.auth_secret_arn],
  )
}
```

Bản viết tay của `values({ for ... })` đầu tiên:

```hcl
[
  module.postgres["order"].app_secret_arn,
  module.postgres["payment"].app_secret_arn,
  module.postgres["inventory"].app_secret_arn,
  module.postgres["notification"].app_secret_arn,
]
```

Policy (inline, như app):

```hcl
data "aws_iam_policy_document" "eso" {
  statement {
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = local.eso_secret_arns
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [module.security.kms_key_arn]   # cả 9 secret mã hóa bằng key này
  }
}
```

| Resource | Thuộc tính | Giá trị |
|---|---|---|
| `aws_iam_role "eso"` | `name` | `"${local.name_prefix}-external-secrets"` |
| | `assume_role_policy` | `data.aws_iam_policy_document.pod_trust.json` |
| `aws_iam_role_policy "eso"` | `role` / `policy` | `aws_iam_role.eso.name` / `data.aws_iam_policy_document.eso.json` |
| `aws_eks_pod_identity_association "eso"` | `cluster_name` | `module.eks.cluster_name` |
| | `namespace` | `"external-secrets"` |
| | `service_account` | `"external-secrets"` (chart ESO đặt tên = release `external-secrets`) |
| | `role_arn` | `aws_iam_role.eso.arn` |

**3d. Cài các add-on bằng Helm — ⚠️ KHÔNG chạy ở bước này**

> Chỉ chạy ở **Bước 7**, sau `terraform apply` thật và `aws eks update-kubeconfig`. Chạy bây giờ: `terraform output` báo `Backend initialization required` (chưa có state), và `helm` cài vào **cluster đang được chọn — tức kind** (`kubectl config current-context` → `kind-jaka`), nơi controller không có AWS để gọi.

Role ở trên chỉ có tác dụng khi chart cài **đúng tên release và namespace**. Lệnh dùng ở `scripts/eks-up.sh`:

```bash
helm repo add eks https://aws.github.io/eks-charts
helm repo add external-secrets https://charts.external-secrets.io
```

```bash
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller --version 3.6.2 \
  -n kube-system \
  --set clusterName="$(terraform -chdir=envs/dev-eks output -raw cluster_name)" \
  --set vpcId="$(terraform -chdir=envs/dev-eks output -raw vpc_id)" \
  --set region="$(terraform -chdir=envs/dev-eks output -raw region)" \
  --wait
```

```bash
helm upgrade --install external-secrets external-secrets/external-secrets --version 2.14.0 \
  -n external-secrets --create-namespace --wait --timeout 10m
```

| Phần | Vì sao |
|---|---|
| Tên release `aws-load-balancer-controller` / `external-secrets` | Chart đặt tên ServiceAccount = tên release → phải trùng `service_account` trong association (3b, 3c) |
| `-n kube-system` / `-n external-secrets` | Phải trùng `namespace` trong association |
| `clusterName` | Controller gắn tag cluster lên NLB/target group nó tạo, và chỉ quản lý những cái có tag đó |
| `vpcId`, `region` | Truyền thẳng để controller không phải dò metadata EC2 (IMDS) — Pod thường không với tới IMDS khi node chặn hop |
| `--wait` | Chờ Pod Ready. LB Controller có **webhook** cho Service: tạo Service `LoadBalancer` khi webhook chưa chạy → lỗi `failed calling webhook` |
| `--timeout 10m` (ESO) | `--wait` mặc định 5 phút; image ESO tải chậm là Helm báo `UPGRADE FAILED: Progress deadline exceeded` dù không có gì hỏng |

**3e. Kiểm tra**

```bash
cd modules/eks
terraform validate
terraform test
```

`terraform test` của `modules/eks` vẫn phải `3 passed` — `aws_iam_policy_document` đã được mock (`json` cố định) nên role mới không làm hỏng test.

```bash
cd ../../envs/dev-eks
terraform validate
```

Lỗi hay gặp:
- `Invalid function argument ... no file exists at ".../policies/aws-load-balancer-controller.json"` → chưa chạy lệnh `curl` ở 3b
- `Unsupported argument "namespace"` trong `pod_identity_association` của addon → addon không nhận namespace (xem 3a)
- `Reference to undeclared resource "data.aws_iam_policy_document" "pod_trust"` trong `modules/eks` → module không thấy data source của env, phải viết `ebs_csi_trust` riêng
- `Duplicate data "aws_iam_policy_document" configuration` → đặt tên trùng `pod_trust` trong `addons-iam.tf`; dùng lại cái có sẵn, đừng khai báo lại

#### Bước 4 — Secret: Secrets Manager → k8s Secret (chart không đổi)

**Mục tiêu:** trên kind, `cluster-up.sh` đọc `.env` rồi `kubectl create secret`. Trên EKS mật khẩu do **Terraform/RDS sinh** và nằm trong Secrets Manager — không ai biết để gõ vào `.env`. ESO đọc Secrets Manager rồi tạo **đúng 2 Secret mà kind đang dùng**, cùng tên, cùng key → chart, Job tạo role không phải sửa.

**k8s Secret cần có (giống kind) — và lấy từ đâu trên AWS:**

| k8s Secret (ns `dev`) | Key | Ai đọc | Lấy từ Secrets Manager | Định dạng secret trên AWS |
|---|---|---|---|---|
| `<svc>-service-secret` | `SPRING_DATASOURCE_PASSWORD` | app (`envFrom`), Job tạo role (`APP_PASS`) | `jaka-mod-dev-eks/<svc>-db/app-credentials` (tên do `modules/postgres` đặt) | JSON `{engine, username, password}` → lấy `password` |
| `<svc>-service-secret` | `SPRING_DATA_REDIS_PASSWORD` | app — chỉ order, payment dùng Redis | `jaka-mod-dev-eks/redis/auth-token` (tên do `modules/redis` đặt) | **chuỗi thuần** → không có `property` |
| `<svc>-db-admin` | `password` | Job tạo role (`PGPASSWORD`) | secret **do RDS tạo**, tên `rds!db-…` → chỉ biết **ARN** sau `apply` (output `db_admin_secret_arns`) | JSON `{username, password}` → lấy `password` |

→ Mỗi service 2 ExternalSecret (1 cho mỗi k8s Secret) = **8 ExternalSecret** + **1 ClusterSecretStore**.

```
ClusterSecretStore "aws-secrets-manager"  (cách nói chuyện với AWS: region + quyền của Pod ESO)
        ▲ secretStoreRef
ExternalSecret "order-service-secret" ──► k8s Secret order-service-secret {SPRING_DATASOURCE_PASSWORD, SPRING_DATA_REDIS_PASSWORD}
ExternalSecret "order-db-admin"       ──► k8s Secret order-db-admin       {password}
... ×4 service
```

**ESO có gì — 3 loại object (CRD do chart ESO cài ở Bước 3d):**

| Object | Phạm vi | Làm gì |
|---|---|---|
| `SecretStore` | **1 namespace** | "Cách nói chuyện với nhà cung cấp secret" (AWS region, quyền). Chỉ ExternalSecret **cùng namespace** dùng được |
| `ClusterSecretStore` | **toàn cluster** | Giống `SecretStore` nhưng ExternalSecret ở **mọi namespace** đều dùng được |
| `ExternalSecret` | 1 namespace | "Lấy key X từ store Y → ghi vào k8s Secret Z" (trỏ store qua `secretStoreRef`) |

Pod ESO (controller, ns `external-secrets`) theo dõi ExternalSecret ở **mọi** namespace → gọi AWS theo store được trỏ → tạo/cập nhật k8s Secret.

**4a. `k8s/eks/cluster-secret-store.yml` — 1 file, viết cố định**

```yaml
apiVersion: external-secrets.io/v1
kind: ClusterSecretStore
metadata:
  name: aws-secrets-manager
spec:
  provider:
    aws:
      service: SecretsManager
      region: ap-southeast-1
```

- **Cluster**SecretStore (không phải `SecretStore`): định nghĩa 1 lần, namespace nào sau này (`dev`, `staging`, `kafka`…) cũng dùng được. `SecretStore` đặt trong `dev` cũng chạy được cho 8 ExternalSecret hiện tại — nhưng mỗi namespace mới phải chép thêm 1 cái. Pod ESO nằm ở ns nào **không** ảnh hưởng lựa chọn này
  - Đánh đổi: namespace nào cũng đọc được mọi secret mà role `eso` được phép → ổn cho cluster 1 team. Cluster nhiều team → mỗi namespace 1 `SecretStore` + role IAM riêng
- **Không có khối `auth`**: ESO dùng credential mặc định của chính Pod ESO = role `eso` qua Pod Identity (Bước 3c). Đây là lý do Bước 3c phải ghép đúng SA `external-secrets`
- `external-secrets.io/v1` đúng với chart **2.14.0** (Bước 3d)

**4b. `k8s/eks/external-secrets.yml.tpl` — 1 template, render 4 lần**

ARN secret admin chỉ có sau `terraform apply` → không viết cứng được. Viết **template** với 2 biến, script điền bằng `envsubst`:

| Biến | Ví dụ | Lấy từ |
|---|---|---|
| `${SVC}` | `order` | vòng lặp trong script |
| `${ADMIN_SECRET_ARN}` | `arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-3f9a…` | `terraform output -json db_admin_secret_arns` |

```yaml
---                                              # BẮT BUỘC ở dòng 1: 4 bản render nối nhau, thiếu dòng này thì object cuối bản trước dính vào object đầu bản sau
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: ${SVC}-service-secret
  namespace: dev
spec:
  refreshInterval: 1h
  secretStoreRef: { kind: ClusterSecretStore, name: aws-secrets-manager }
  target:
    name: ${SVC}-service-secret                  # trùng secretName trong values/<svc>-*.yml
  data:
    - secretKey: SPRING_DATASOURCE_PASSWORD
      remoteRef:
        key: jaka-mod-dev-eks/${SVC}-db/app-credentials
        property: password
    - secretKey: SPRING_DATA_REDIS_PASSWORD
      remoteRef:
        key: jaka-mod-dev-eks/redis/auth-token  # chuỗi thuần → không có property
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: ${SVC}-db-admin
  namespace: dev
spec:
  refreshInterval: 1h
  secretStoreRef: { kind: ClusterSecretStore, name: aws-secrets-manager }
  target:
    name: ${SVC}-db-admin
  data:
    - secretKey: password
      remoteRef:
        key: ${ADMIN_SECRET_ARN}               # tên do RDS đặt → dùng ARN
        property: password
```

- `SPRING_DATA_REDIS_PASSWORD` có ở cả 4 service cho template đơn giản; inventory/notification không dùng Redis → biến môi trường thừa, vô hại
- `refreshInterval: 1h`: RDS **tự xoay vòng** mật khẩu admin (`admin_secret_rotation_days`) → ESO đọc lại mỗi giờ, k8s Secret luôn theo kịp
- `target` viết **dạng khối** (2 dòng), không viết `{ name: ${SVC}-… }`: trong `{ }` của YAML, dấu `{` của `${SVC}` bị hiểu là mở map lồng → VS Code báo hàng loạt lỗi `Unexpected flow-map-start`. Sau `envsubst` thì chạy được, nhưng template phải tự là YAML hợp lệ để editor kiểm tra được
- Tên `target` phải trùng chính xác `<svc>-service-secret` / `<svc>-db-admin` — sai 1 ký tự là app `CreateContainerConfigError: secret not found`

Render (dòng này đi vào `scripts/eks-up.sh` ở Bước 7):

```bash
admin_arns=$(terraform -chdir=envs/dev-eks output -json db_admin_secret_arns)
for SVC in order payment inventory notification; do
  ADMIN_SECRET_ARN=$(jq -r --arg s "$SVC" '.[$s]' <<<"$admin_arns") SVC=$SVC     envsubst '${SVC} ${ADMIN_SECRET_ARN}' < k8s/eks/external-secrets.yml.tpl
done | kubectl apply -f -
```

`envsubst '${SVC} ${ADMIN_SECRET_ARN}'` — liệt kê rõ 2 biến: chỉ thay đúng 2 biến này, mọi `$` khác trong file giữ nguyên.

**4c. Kiểm tra không cần AWS (trên kind)**

Không có AWS thì ESO không đọc được secret, nhưng vẫn kiểm tra được **cú pháp + schema** của 9 object. Cài ESO lên kind chỉ để có CRD — vô hại, không đụng gì tới hệ thống đang chạy (khác LB Controller ở 3d):

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm upgrade --install external-secrets external-secrets/external-secrets --version 2.14.0 \
  -n external-secrets --create-namespace --wait --timeout 10m
```

Nếu báo `Progress deadline exceeded`: Pod còn `ContainerCreating` vì node kind đang tải image `ghcr.io/external-secrets/external-secrets` (chậm). Chờ `kubectl get pods -n external-secrets -w` đủ `1/1 Running` rồi chạy lại lệnh trên (release từ `failed` → `deployed`).

Render với ARN giả rồi cho API server kiểm tra schema mà không tạo gì (`--dry-run=server`):

```bash
kubectl apply --dry-run=server -f k8s/eks/cluster-secret-store.yml
for SVC in order payment inventory notification; do
  ADMIN_SECRET_ARN='arn:aws:secretsmanager:ap-southeast-1:123456789012:secret:rds!db-test' SVC=$SVC     envsubst '${SVC} ${ADMIN_SECRET_ARN}' < k8s/eks/external-secrets.yml.tpl
done | kubectl apply --dry-run=server -f -
```

Kết quả đúng: 9 dòng `... created (server dry run)` (1 ClusterSecretStore + 8 ExternalSecret).

Lỗi hay gặp:
- `bash: !db: event not found` → ARN giả để trong **nháy kép**: bash gõ tay coi `!db` là "lệnh cũ bắt đầu bằng db" (history expansion). Dùng **nháy đơn** `'...'`. Trong script (`eks-up.sh`) không bị, vì history expansion chỉ bật ở shell gõ tay
- Chỉ ra **5** dòng (thiếu `order/payment/inventory-db-admin`) → template thiếu `---` ở dòng 1. Các bản render nối liền nhau, `<svc>-db-admin` bị `<svc kế>-service-secret` ghi đè key (trùng key YAML → giá trị sau thắng)
- `no matches for kind "ExternalSecret"` → chưa cài ESO (CRD chưa có)
- `unknown field "spec.data[0].remoteRef.propery"` → gõ sai tên field — `--dry-run=server` bắt được, `--dry-run=client` thì không
- Output còn chữ `${SVC}` → quên liệt kê biến trong `envsubst '...'` hoặc chưa `export`/gán biến trước lệnh
- `metadata.name: Invalid value: "${SVC}-db-admin"` → giống trên, template chưa được render

Xong kiểm tra, gỡ ESO khỏi kind nếu muốn (không bắt buộc):

```bash
helm uninstall external-secrets -n external-secrets
```

#### Bước 5 — Helm values `<svc>-eks.yml`, Job tạo role, StorageClass, NLB cho Gateway

**Giá trị nào gõ tay, giá trị nào không:**

| Loại | Ví dụ | Viết ở đâu |
|---|---|---|
| Biết trước, không đổi giữa các lần `apply` | port, contextPath, username, Kafka bootstrap | gõ thẳng vào `values/<svc>-eks.yml` / file YAML |
| Chỉ có **sau** `terraform apply` (đổi mỗi lần tạo lại) | URL ECR, endpoint RDS, endpoint Redis | **không** gõ — `eks-up.sh` đọc `terraform output` rồi truyền `--set` / `envsubst` |
| Bí mật | mật khẩu DB, Redis | không nằm trong file nào — ESO tạo Secret (Bước 4) |

**5a. `charts/spring-service/values/<svc>-eks.yml` ×4**

Lấy `<svc>-dev.yml` làm gốc, so từng dòng:

| Key | `order-dev.yml` (kind) | `order-eks.yml` | Vì sao |
|---|---|---|---|
| `image.repository` | `order-service` | **bỏ** | URL ECR chỉ có sau `apply` → `--set` trong script. Chart có `required` → quên `--set` là `helm` báo lỗi ngay |
| `port`, `contextPath` | `8081`, `/api/orders` | giữ nguyên | app không đổi |
| `env.SPRING_DATASOURCE_URL` | `jdbc:postgresql://order-db:5432/orderdb` | **bỏ** | host = endpoint RDS → `--set` |
| `env.SPRING_DATASOURCE_USERNAME` | `order_user` | giữ nguyên | Job 5b tạo đúng user này |
| `env.SPRING_DATA_REDIS_HOST` | `order-redis` | **bỏ** | endpoint ElastiCache → `--set` |
| `env.SPRING_DATA_REDIS_SSL_ENABLED` | (không có) | `"true"` | `modules/redis` bật `transit_encryption_enabled = true` → Redis chỉ nhận TLS. `application.yaml` mặc định `ssl.enabled: false` |
| `env.KAFKA_BOOTSTRAP` | `jaka-kafka-bootstrap.kafka:9092` | giữ nguyên | Kafka vẫn là Strimzi trong cluster, giống kind |
| `secretName` | `order-service-secret` | giữ nguyên | ESO tạo đúng tên này (`target.name` ở 4b) |
| `replicaCount` / `autoscaling` | mặc định 2 / tắt | `1` / bật, `min 1, max 3` | 2 node `t3.medium` chỉ chứa ~17 Pod/node (giới hạn IP của VPC CNI). Riêng `kube-system` + ESO + LB Controller + Envoy + Kafka đã ~22 Pod → 4 service × 2 replica là sát trần |

`charts/spring-service/values/order-eks.yml`:

```yaml
port: 8081
contextPath: /api/orders
env:
  SPRING_DATASOURCE_USERNAME: order_user
  SPRING_DATA_REDIS_SSL_ENABLED: "true"
  KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092
secretName: order-service-secret
replicaCount: 1
autoscaling: { enabled: true, minReplicas: 1, maxReplicas: 3, targetCPU: 50 }
```

`charts/spring-service/values/payment-eks.yml`:

```yaml
port: 8082
contextPath: /api/payments
env:
  SPRING_DATASOURCE_USERNAME: payment_user
  SPRING_DATA_REDIS_PORT: "6379"
  SPRING_DATA_REDIS_SSL_ENABLED: "true"
  KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092
secretName: payment-service-secret
replicaCount: 1
autoscaling: { enabled: true, minReplicas: 1, maxReplicas: 3, targetCPU: 50 }
```

`charts/spring-service/values/inventory-eks.yml` (không dùng Redis → không có dòng Redis):

```yaml
port: 8083
contextPath: /api/inventory
env:
  SPRING_DATASOURCE_USERNAME: inventory_user
  KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092
secretName: inventory-service-secret
replicaCount: 1
autoscaling: { enabled: true, minReplicas: 1, maxReplicas: 3, targetCPU: 50 }
```

`charts/spring-service/values/notification-eks.yml` (Mailpit vẫn chạy trong cluster như kind — `eks-up.sh` apply `k8s/notification-service/mailpit.yml`):

```yaml
port: 8084
contextPath: /api/notifications
env:
  SPRING_DATASOURCE_USERNAME: notification_user
  KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092
  MAIL_HOST: mailpit
  MAIL_PORT: "1025"
secretName: notification-service-secret
replicaCount: 1
autoscaling: { enabled: true, minReplicas: 1, maxReplicas: 3, targetCPU: 50 }
```

Phần `--set` (đi vào `scripts/eks-up.sh` ở Bước 7) — lấy từ output của `envs/dev-eks/outputs.tf`:

| `--set` | Giá trị | Output Terraform |
|---|---|---|
| `image.repository` | `123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/jaka-mod-dev-eks/order-service` | `ecr_repository_urls[<svc>]` |
| `image.tag` | tag vừa push | biến `tag` trong script |
| `env.SPRING_DATASOURCE_URL` | `jdbc:postgresql://<host>:5432/orderdb?sslmode=require` | host = `db_endpoints[<svc>]`; tên DB = `<svc>db` (`local.apps` trong `main.tf`) |
| `env.SPRING_DATA_REDIS_HOST` | `master.jaka-mod-dev-eks-redis.xxxx.apse1.cache.amazonaws.com` | `redis_primary_endpoint` — chỉ order, payment |

```bash
tf_out() { terraform -chdir=envs/dev-eks output -json "$1"; }
ecr_urls=$(tf_out ecr_repository_urls)
db_hosts=$(tf_out db_endpoints)
redis_host=$(terraform -chdir=envs/dev-eks output -raw redis_primary_endpoint)

for svc in order payment inventory notification; do
  repo=$(jq -r --arg s "$svc" '.[$s]' <<<"$ecr_urls")
  db_host=$(jq -r --arg s "$svc" '.[$s]' <<<"$db_hosts")
  extra=()
  [[ $svc == order || $svc == payment ]] && extra=(--set env.SPRING_DATA_REDIS_HOST="$redis_host")
  helm upgrade --install "$svc-service" charts/spring-service -n dev \
    -f "charts/spring-service/values/$svc-eks.yml" \
    --set image.repository="$repo" \
    --set image.tag="$tag" \
    --set env.SPRING_DATASOURCE_URL="jdbc:postgresql://$db_host:5432/${svc}db?sslmode=require" \
    "${extra[@]}"
done
```

- Tên release `"$svc-service"` → chart đặt tên ServiceAccount = tên release = `order-service`… → khớp `service_account = each.key` trong `pod-identity.tf` (`local.services` = `"${k}-service"`). Đổi tên release là Pod mất role IAM
- `extra=()` là mảng bash: inventory/notification truyền mảng rỗng → không có `--set` Redis

**5b. `k8s/eks/create-app-role.yml.tpl` — 1 template, render ×4**

Giống `k8s/order-service/create-app-role-job.yml` của kind, khác 4 chỗ:

| Chỗ | kind | EKS | Vì sao |
|---|---|---|---|
| Host | `psql -h order-db` | env `PGHOST: ${DB_HOST}` | RDS endpoint, chỉ có sau `apply` → `envsubst` |
| SSL | không | env `PGSSLMODE: require` | RDS Postgres 15+ mặc định `rds.force_ssl = 1` → không TLS là bị từ chối |
| Tên | viết cứng `order` | `${SVC}` | 1 file cho 4 service, giống 4b |
| Dòng 1 | — | `---` | 4 bản render nối nhau (lỗi "chỉ ra 5 dòng" ở 4c) |

Admin = `${SVC}_admin` (`envs/dev-eks/main.tf`: `admin_username = "${each.key}_admin"`). `modules/postgres` **không** tạo `<svc>_user` trong DB (chỉ sinh mật khẩu vào Secrets Manager) → Job này bắt buộc.

```yaml
---
apiVersion: batch/v1
kind: Job
metadata:
  name: ${SVC}-db-create-app-role
  namespace: dev
spec:
  backoffLimit: 5
  ttlSecondsAfterFinished: 300
  template:
    spec:
      restartPolicy: OnFailure
      containers:
        - name: psql
          image: postgres:16
          env:
            - name: PGHOST
              value: ${DB_HOST}
            - name: PGSSLMODE
              value: require
            - name: PGPASSWORD
              valueFrom:
                secretKeyRef:
                  name: ${SVC}-db-admin
                  key: password
            - name: APP_PASS
              valueFrom:
                secretKeyRef:
                  name: ${SVC}-service-secret
                  key: SPRING_DATASOURCE_PASSWORD
          command: ["sh", "-c"]
          args:
            - |
              psql -U ${SVC}_admin -d ${SVC}db -v ON_ERROR_STOP=1 -v app_pass="$APP_PASS" <<'SQL'
              SELECT EXISTS (SELECT FROM pg_roles WHERE rolname = '${SVC}_user') AS role_exists \gset
              \if :role_exists
              ALTER ROLE ${SVC}_user WITH PASSWORD :'app_pass';
              \else
              CREATE ROLE ${SVC}_user LOGIN PASSWORD :'app_pass';
              \endif
              GRANT CREATE, USAGE ON SCHEMA public TO ${SVC}_user;
              SQL
```

Render (đi vào `eks-up.sh`, **sau** khi apply ExternalSecret):

```bash
db_hosts=$(terraform -chdir=envs/dev-eks output -json db_endpoints)
for SVC in order payment inventory notification; do
  DB_HOST=$(jq -r --arg s "$SVC" '.[$s]' <<<"$db_hosts") SVC=$SVC envsubst '${SVC} ${DB_HOST}' < k8s/eks/create-app-role.yml.tpl
done | kubectl apply -f -
```

- `envsubst '${SVC} ${DB_HOST}'` chỉ thay 2 biến → `$APP_PASS` giữ nguyên để `sh` trong Pod tự đọc lúc chạy. Không liệt kê biến → `$APP_PASS` bị thay bằng chuỗi rỗng (máy bạn không có biến đó) → user được tạo với mật khẩu rỗng
- Secret chưa có (ESO chưa sync xong) → Pod báo `CreateContainerConfigError`, kubelet tự thử lại, **không** cần chờ trong script

**5c. `k8s/eks/storageclass-gp3.yml`**

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  encrypted: "true"
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
reclaimPolicy: Delete
```

| Dòng | Vì sao |
|---|---|
| `is-default-class: "true"` | `kafka-single-node.yml` không ghi `class:` → PVC dùng StorageClass **mặc định**. EKS mới (1.30+) có sẵn `gp2` nhưng **không** đánh dấu mặc định → thiếu dòng này PVC Kafka `Pending` mãi |
| `provisioner: ebs.csi.aws.com` | EBS CSI driver (add-on ở Bước 3a) là thứ gọi API tạo EBS |
| `WaitForFirstConsumer` | EBS chỉ gắn được vào node **cùng AZ**. Chờ Pod được xếp lên node rồi mới tạo volume ở AZ đó. `Immediate` → volume có thể nằm AZ khác → Pod `Pending` vì "volume node affinity conflict" |
| `encrypted: "true"` | Mã hóa bằng key EBS mặc định của AWS. Dùng CMK của `modules/security` thì role EBS CSI phải có thêm quyền KMS |

**5d. `k8s/eks/envoyproxy-nlb.yml` — bảo Envoy Gateway xin NLB internet-facing**

Envoy Gateway tạo Service `LoadBalancer` cho Gateway `web`. Không có annotation → LB Controller **bỏ qua** Service đó, và LB tạo ra (nếu có) là loại **internal** (mặc định của controller) → không vào được từ internet.

```yaml
apiVersion: gateway.envoyproxy.io/v1alpha1
kind: EnvoyProxy
metadata:
  name: nlb
  namespace: envoy-gateway-system
spec:
  provider:
    type: Kubernetes
    kubernetes:
      envoyService:
        annotations:
          service.beta.kubernetes.io/aws-load-balancer-type: external
          service.beta.kubernetes.io/aws-load-balancer-scheme: internet-facing
          service.beta.kubernetes.io/aws-load-balancer-nlb-target-type: ip
```

| Annotation | Nghĩa |
|---|---|
| `aws-load-balancer-type: external` | "LB Controller xử lý Service này" (không có → controller cũ của EKS tạo Classic LB) |
| `scheme: internet-facing` | NLB đặt ở public subnet (tag `kubernetes.io/role/elb = 1` trong `envs/dev-eks/main.tf`), có DNS public |
| `nlb-target-type: ip` | NLB gửi thẳng tới IP Pod Envoy, không đi vòng qua NodePort |

`k8s/gateway.yml` dùng chung với kind → **không sửa file**. Trên EKS, script chèn `parametersRef` (trỏ GatewayClass `eg` vào EnvoyProxy trên) bằng `sed` ngay lúc apply:

```bash
kubectl apply -f k8s/eks/envoyproxy-nlb.yml
sed '/controllerName: gateway.envoyproxy.io\/gatewayclass-controller/a\
  parametersRef: { group: gateway.envoyproxy.io, kind: EnvoyProxy, name: nlb, namespace: envoy-gateway-system }' \
    k8s/gateway.yml | kubectl apply -f -
```

- Vì sao không `apply gateway.yml` rồi `kubectl patch gatewayclass`: giữa 2 lệnh, Gateway `web` đã có → Envoy tạo Service **chưa có annotation** → LB Controller tạo NLB **internal** trước, rồi mới bị đổi. Chèn trong cùng 1 lần apply → Service sinh ra đã đúng annotation
- `sed '/X/a\ Y'` = "sau dòng khớp X, thêm dòng Y" — giống cách `cluster-up.sh` dùng `sed` sửa namespace của Strimzi

Lỗi hay gặp (Bước 5):
- `image.repository is required` → quên `--set image.repository` (values EKS cố ý không có)
- App `Connection refused` / timeout tới Redis → thiếu `SPRING_DATA_REDIS_SSL_ENABLED: "true"`
- Job `no pg_hba.conf entry ... no encryption` → thiếu `PGSSLMODE: require`
- Job tạo user nhưng app báo `password authentication failed` → `envsubst` không liệt kê biến, `$APP_PASS` bị thay rỗng
- PVC Kafka `Pending`, event `no persistent volumes available ... no storage class` → StorageClass `gp3` thiếu annotation default
- `kubectl get svc -n envoy-gateway-system` → `EXTERNAL-IP <pending>` mãi → thiếu annotation `external`, hoặc LB Controller chưa có quyền (Pod Identity Bước 3b), xem `kubectl logs -n kube-system deploy/aws-load-balancer-controller`

#### Bước 6 — Kiểm tra toàn bộ, không cần AWS

Mọi lệnh chạy từ gốc `jaka_infrastructure/`. Mỗi lệnh kiểm 1 tầng:

| Kiểm gì | Lệnh | Đúng khi |
|---|---|---|
| Terraform EKS: cú pháp + dây nối module | `terraform -chdir=envs/dev-eks init -backend=false` rồi `terraform -chdir=envs/dev-eks validate` | `Success! The configuration is valid.` |
| Stack cũ không bị phá (sau khi tách ECR, nới version) | như trên với `envs/dev` | `Success!` |
| Logic module (validation, for_each) | `terraform -chdir=modules/eks test`, `terraform -chdir=modules/network test` | `3 passed` / `6 passed` |
| Format | `terraform fmt -recursive -check` | không in gì |
| ClusterSecretStore + 8 ExternalSecret (schema) | 4c (cần ESO trên kind) | 9 dòng `created (server dry run)` |
| Helm values EKS render được | khối dưới | 4 dòng `ok`, có `HorizontalPodAutoscaler` |
| Script | `bash -n scripts/eks-up.sh` | không lỗi |

```bash
for svc in order payment inventory notification; do
  helm template "$svc-service" charts/spring-service -f "charts/spring-service/values/$svc-eks.yml" \
    --set image.repository=test --set image.tag=test > /dev/null && echo "ok $svc"
done
helm template order-service charts/spring-service -f charts/spring-service/values/order-eks.yml \
  --set image.repository=test --set image.tag=test | grep -A3 'kind: HorizontalPodAutoscaler'
```

Còn thiếu (làm thêm cho đủ):
- `modules/ecr/tests/ecr.tftest.hcl` (mock): 1 run `plan` với `services = ["order", "payment"]`, assert có 2 repo và tên `jaka-mod-test-order-service`
- CI: thêm `modules/ecr` vào matrix `test`, `dev-eks` vào `validate-envs`

#### Bước 7a — `scripts/eks-up.sh`: `cluster-up.sh` phiên bản EKS

Viết file mới `scripts/eks-up.sh`, đi **đúng thứ tự** `cluster-up.sh` — mỗi bước hoặc giữ nguyên, hoặc đổi sang thứ AWS cung cấp:

| # | `cluster-up.sh` (kind) | `eks-up.sh` (EKS) | Vì sao đổi |
|---|---|---|---|
| đầu | đọc `.env` (mật khẩu) | đọc `terraform output` (region, cluster, ECR, endpoint DB/Redis, ARN admin) | mật khẩu do Terraform/RDS sinh, nằm trong Secrets Manager; endpoint chỉ có sau `apply` |
| 1 | `kind create cluster` | `aws eks update-kubeconfig` | cluster do Terraform tạo, script chỉ trỏ `kubectl` vào nó |
| 1b | preload image bên thứ 3 | **bỏ** | node EKS có internet qua NAT, tự pull |
| 2 | `docker build` + `kind load` | `docker build` + **`docker push` lên ECR** (login trước) | node EKS không thấy image trên máy bạn |
| 3 | namespace + `kubectl create secret` | chỉ namespace | Secret do ESO tạo ở bước 6 |
| 4 | metrics-server (+ patch insecure TLS), Envoy, Strimzi | metrics-server (**không** patch), **LB Controller**, **ESO**, Envoy, Strimzi | kubelet EKS có cert thật; LB Controller tạo NLB, ESO đọc Secrets Manager. LB Controller cài **trước** Envoy: webhook của nó phải chạy trước khi có Service `LoadBalancer` |
| 5 | — | apply `storageclass-gp3.yml`, `cluster-secret-store.yml` | 5c, 4a |
| 6 | — | render `external-secrets.yml.tpl` ×4 → apply → `kubectl wait externalsecret --for=condition=Ready` | 4b. Chờ Secret có thật rồi mới chạy Job/app |
| 7 | Job nằm trong `k8s/<svc>-service/` | `delete job` → render `create-app-role.yml.tpl` ×4 → apply → `wait --for=condition=Complete` | 5b. Xóa trước vì Job **không sửa được** template sau khi tạo; SQL chạy lại vô hại |
| 8 | apply cả `k8s/<svc>-service/` (DB, Redis, Mailpit) | Kafka + topic + **chỉ** `mailpit.yml` | DB/Redis đã là RDS/ElastiCache |
| 9 | `helm` với `-f <svc>-dev.yml` | `helm` với `-f <svc>-eks.yml` + `--set` (image, DB URL, Redis host) | 5a |
| 10 | apply `gateway.yml`, in lệnh `port-forward` | apply `envoyproxy-nlb.yml` + `gateway.yml` có chèn `parametersRef` (sed), chờ `Programmed`, in DNS NLB | 5d. `Programmed` = NLB đã có DNS |
| 9 (kind) | dọn image cũ trên node | **bỏ** | ECR có lifecycle policy (`modules/ecr`) |

Mẫu đọc output dùng xuyên script (đặt ở đầu file):

```bash
tf_out() { terraform -chdir=envs/dev-eks output -json "$1"; }   # map  -> JSON
tf_raw() { terraform -chdir=envs/dev-eks output -raw "$1"; }    # chuỗi -> không có dấu "
pick()   { jq -r --arg s "$2" '.[$s]' <<<"$1"; }                 # pick "$ecr_urls" order
ecr_urls=$(tf_out ecr_repository_urls)
registry=$(pick "$ecr_urls" order); registry=${registry%%/*}     # cắt phần sau dấu / đầu tiên = host ECR
```

ECR login + push (bước 2):

```bash
aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry"
docker build --platform linux/amd64 -t "$repo:$tag" "$app_root/$svc-service/$svc-service"
docker push "$repo:$tag"
```

- `--platform linux/amd64`: node `t3.medium` là x86. Build trên máy ARM (Mac M) mà thiếu cờ này → Pod `exec format error`
- `$tag` khai báo **1 lần** ở đầu (`tag=${TAG:-eks-$(date +%s)}`), bước 2 push và bước 9 `--set image.tag` dùng chung — lệch là `ImagePullBackOff`

Kiểm tra không cần AWS:

```bash
bash -n scripts/eks-up.sh && echo syntax-ok
```

Lỗi hay gặp:
- `no basic auth credentials` khi push → chưa `docker login` ECR, hoặc token hết hạn (12 giờ)
- `failed calling webhook "mservice.elbv2.k8s.aws"` → tạo Service `LoadBalancer` khi LB Controller chưa sẵn sàng (thiếu `--wait` ở bước 4)
- `kubectl wait externalsecret` timeout → `kubectl describe externalsecret -n dev <tên>`: `AccessDenied` = role ESO thiếu ARN/KMS (3c); `ResourceNotFound` = sai tên secret
- `kubectl wait job` timeout → `kubectl logs -n dev job/<svc>-db-create-app-role`

#### Bước 7b (nếu có AWS) — Runbook

> Chi phí ước lượng khi chạy: control plane 0.10 USD/giờ + 2 × t3.medium + NAT + 4 RDS `db.t4g.micro` + 1 ElastiCache + NLB ≈ **0.4–0.5 USD/giờ**. Làm liền 1 buổi, `destroy` ngay.

**Cần trước:** `aws` CLI đăng nhập được (`aws sts get-caller-identity`), bucket state đã tạo bằng `bootstrap/`, `envs/dev-eks/backend.hcl` đã điền `<ACCOUNT_ID>`.

**1. Hạ tầng (Terraform):**

```bash
cd envs/dev-eks
terraform init -backend-config backend.hcl
terraform apply -var-file terraform.tfvars      # ~15–20 phút: EKS ~10, RDS ~5–10
cd ../..
```

**2. Nền tảng + ứng dụng (1 script):**

```bash
./scripts/eks-up.sh      # kubeconfig, push 4 image, add-on, secret, Job, Kafka, 4 release, Gateway → in DNS NLB
```

**3. Kiểm tra từng tầng:**

```bash
kubectl get nodes                                   # 2 node Ready
kubectl get externalsecret -n dev                   # 8 dòng SecretSynced / Ready True
kubectl get jobs -n dev                             # 4 Job Complete 1/1
kubectl get pvc -n kafka                            # Bound, StorageClass gp3
kubectl get pods,hpa -n dev                         # 4 app Running, HPA có TARGETS (cpu x%/50%)
kubectl get gateway web -n dev                      # ADDRESS = DNS NLB, PROGRAMMED True
```

```bash
BASE_URL=http://<dns-nlb> ./scripts/saga-demo.sh --timeout
```

**4. Dọn — đúng thứ tự** (NLB và ổ EBS do **controller trong cluster** tạo, Terraform không biết → phải xóa khi controller còn chạy, nếu không chúng còn đó, tốn tiền và **chặn xóa VPC**):

```bash
kubectl delete gateway web -n dev                   # Envoy xóa Service LoadBalancer → LB Controller xóa NLB
kubectl wait --for=delete svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web --timeout=5m
kubectl delete ns kafka                             # PVC bị xóa → EBS CSI xóa ổ EBS (reclaimPolicy: Delete)
```

```bash
terraform -chdir=envs/dev-eks destroy -var-file terraform.tfvars
```

Lỗi hay gặp khi `destroy`:
- `RepositoryNotEmptyException` (ECR còn image) → xóa repo kèm image rồi chạy lại `destroy`:

```bash
for svc in order payment inventory notification; do
  aws ecr delete-repository --repository-name "jaka-mod-dev-eks-$svc-service" --force --region ap-southeast-1
done
```

  Sửa lâu dài: thêm biến `force_delete` (default `false`) vào `modules/ecr`, gán `force_delete = var.force_delete` cho `aws_ecr_repository`, `envs/dev-eks` truyền `true`
- `DependencyViolation` khi xóa subnet/VPC → còn NLB hoặc ENI của nó: quên bước 4 → `aws elbv2 describe-load-balancers` tìm NLB tên `k8s-…`, xóa tay rồi `destroy` lại

**Output cụ thể:**
- `modules/ecr` + test; `modules/service`/`stack` dùng ECR mới, `envs/dev` validate pass, có `moved {}`
- `envs/dev-eks` gọi đủ `network`, `eks`, `security`, `storage`, `ecr`, `redis`, `postgres` ×4; `validate` pass
- EBS CSI, LB Controller, ESO có IAM role qua Pod Identity
- `k8s/eks/` + 4 file `values/<svc>-eks.yml`; `helm template` pass, HPA render ra
- `scripts/eks-up.sh`
- (Có AWS) `saga-demo.sh --timeout` pass qua DNS NLB, screenshot `kubectl get pods,hpa -A` trên EKS, rồi `destroy`

---

## Chủ Đề 5: Tổng Kết Giai Đoạn Tháng 2 + 3

### Kiến Trúc Cuối Giai Đoạn

**Trên kind (chạy được hằng ngày, `./scripts/cluster-up.sh`):**

```
Laptop :8090 (port-forward)
   │
   ▼
Envoy Gateway "web" (HTTPRoute: /api/orders, /api/payments, /api/inventory, /api/notifications)
   │
   ├─► order-service :8081 ───────┐
   ├─► payment-service :8082 ─────┤        ┌──────────── Kafka "jaka" (Strimzi, KRaft, 1 node dual-role) ────────────┐
   ├─► inventory-service :8083 ───┼──────► │ order-events | inventory-events | payment-events  (3 partition, 7 ngày) │
   └─► notification-service :8084 ┘        │ + *-dlt (30 ngày)                                                       │
         │                                 └─────────────────────────────────────────────────────────────────────────┘
         ▼
   StatefulSets (namespace dev): order-db, payment-db, inventory-db, notification-db (PVC) + order-redis, payment-redis
   Mailpit (SMTP giả cho notification)

Helm: 1 chart charts/spring-service, 4 release; dev = 2 replica cố định, prod values bật HPA
```

**Trên EKS (`envs/dev-eks` + `./scripts/eks-up.sh`, cùng chart, cùng `gateway.yml`):**

```
Internet
   │
   ▼
NLB (public subnet) ◄── AWS Load Balancer Controller (role IAM qua Pod Identity)
   │
   ▼
Envoy Gateway "web" ── HTTPRoute (y hệt kind)
   │
   ├─► order / payment / inventory / notification  (Deployment + HPA min 1 max 3, ns dev, private subnet)
   │        │  Secret ◄── ESO ◄── Secrets Manager (KMS)        image ◄── ECR
   │        ├──► RDS ×4 (1 DB/service, TLS)  ◄── Job tạo <svc>_user
   │        ├──► ElastiCache Redis (TLS + AUTH) — order, payment
   │        └──► Kafka "jaka" (Strimzi) ── PVC gp3 ◄── EBS CSI
   └─ Mailpit

Node: 2–4 × t3.medium (managed node group) · Pod IP thật trong VPC (VPC CNI)
```

**Mã nguồn hạ tầng:**

```
Terraform (jaka_infrastructure):
  modules/ network, security, storage, alb, postgres, redis, service, observability, eks, ecr
  stack/ (bộ EC2 tháng 1) · envs/dev (EC2), envs/dev-eks (EKS) · bootstrap/ (bucket state)
K8s: k8s/ (kind: DB/Redis/Job/Kafka/Gateway) · k8s/eks/ (chỉ phần khác kind) · charts/spring-service
Script: cluster-up.sh / cluster-down.sh (kind) · eks-up.sh (EKS) · saga-demo.sh (cả hai)
CI: GitHub Actions — fmt, init + validate + test, validate-envs, tflint, checkov
```

### Tiến Độ Theo Tuần

| Tuần | Tập trung | Output chính | Nơi nằm |
|---|---|---|---|
| 5 | Terraform nâng cao | 8 module, `stack/`, `envs/dev`, `bootstrap/` (S3 + `use_lockfile`), CI | `jaka_infrastructure/` |
| 6 | K8s cơ bản | 2 service trên kind, ConfigMap/Secret | `jaka_infrastructure/k8s/` |
| 7 | K8s stateful + traffic | StatefulSet, Job tạo role, probes, Gateway, HPA | `jaka_infrastructure/k8s/` |
| 8 | Helm + Kafka + SD1 | `charts/spring-service`, Strimzi, event đầu tiên | `jaka_infrastructure/charts/`, `k8s/kafka/` |
| 9 | Event-driven + SD2 | 4 service, Outbox, idempotent, DLT, CQRS, correlation ID | các thư mục `*-service/`, `docs/` |
| 10 | Saga + EKS | Saga 3 nhánh + timeout + refund, `cluster-up.sh`, `modules/eks` + `modules/ecr`, `envs/dev-eks` (data layer, IAM add-on), `k8s/eks/`, `values/*-eks.yml`, `eks-up.sh` | `*-service/`, `jaka_infrastructure/` |

### So Với Roadmap Gốc

| Roadmap gốc | Giai đoạn gộp | Ghi chú |
|---|---|---|
| Tuần 5–6 Terraform (2 tuần) | Tuần 5 (1 tuần) | Terraform cơ bản đã xong ở tháng 1 |
| Tuần 7–8 K8s cơ bản | Tuần 6–7 | |
| Tuần 9–10 Helm + EKS | Tuần 8 (Helm) + tuần 10 (EKS: Chủ đề 2 cluster, Chủ đề 4 đủ 4 service) | Viết + test không cần AWS; `apply` thật theo runbook Bước 7 Chủ đề 4 |
| Tuần 11–12 System Design + Kafka + Saga | Tuần 8–10 (song song) | |
| **8 tuần** | **6 tuần** | **Dư 2 tuần** → dồn cho Tháng 5–6 (AI) hoặc buffer |

### Checkpoint Giai Đoạn

| Hạng mục | Kiểm tra bằng | Ghi chú từ repo hiện tại |
|---|---|---|
| Terraform: module + remote backend + `terraform test` + CI xanh | Tab Actions trên GitHub | Có test: `modules/network` (6 run), `modules/eks` (3 run). ⚠️ `modules/ecr` và các module còn lại **chưa có** `tests/*.tftest.hcl` → job `test` của CI pass mà không test gì cho chúng |
| K8s: giải thích Pod → ReplicaSet → Deployment → Service | Tự nói lại, `kubectl get deploy,rs,pods -n dev` | |
| 4 microservice trên kind: Gateway, HPA, StatefulSet | `kubectl get pods,sts,hpa -n dev` | HPA chỉ bật ở `*-prod.yml` — muốn thấy HPA trên kind thì `--set autoscaling.enabled=true` |
| Kafka event-driven: Outbox + idempotent consumer + DLT | Chứng minh Chủ đề 3 tuần 9 | |
| Saga xử lý đúng 3 nhánh + timeout (+ refund khi payment đến muộn) | `scripts/saga-demo.sh` | |
| Module EKS viết xong, hiểu khác biệt kind vs EKS | `terraform test` trong `modules/eks` | |
| 4 service chạy được trên EKS: RDS, ElastiCache, ECR, ESO, NLB, HPA | `envs/dev-eks` validate + `helm template` 4 file `*-eks.yml` (Chủ đề 4) | Có AWS: `saga-demo.sh` qua DNS NLB rồi `destroy` |
| Trả lời được System Design: REST/gRPC, LB (L4/L7), scalability, caching, CAP, event-driven, Saga, CQRS | Câu hỏi bên dưới | |
| README đầy đủ + screenshot | `jaka_infrastructure/README.md` | Đã có: rolling update vs instance refresh, EKS (Chủ đề 2), chạy kind 1 lệnh + saga. **Còn thiếu:** Chủ đề 4 — data layer AWS, ESO, NLB, `eks-up.sh` |
| `docs/event-catalog.md` khớp code | So với `dto/` của 4 service | Đã có đủ event saga (`OrderConfirmed`, `OrderCancelled`, `RefundRequested`, `PaymentRefunded`) |

### Câu Hỏi Phỏng Vấn Tự Luyện (Senior Backend)

1. Thiết kế luồng đặt hàng qua 4 service, đảm bảo không trừ kho sai khi thanh toán lỗi.
2. Vì sao không dùng distributed transaction (2PC)?
3. Consumer nhận trùng message thì xử lý thế nào?
4. Làm sao đảm bảo ghi DB và gửi event nhất quán?
5. Choreography vs Orchestration — khi nào chọn cái nào?
6. Đơn đã timeout bị huỷ rồi mới nhận `PaymentCompleted` — xử lý thế nào?
7. Pod bị CrashLoopBackOff — bạn debug theo thứ tự nào?
8. readiness vs liveness probe — cấu hình sai gây hậu quả gì?
9. Terraform state bị khóa/bị hỏng — xử lý thế nào? (`use_lockfile` trong `backend.hcl` hoạt động ra sao)
10. Trên EKS, làm sao để order-service không đọc được secret của payment-service?
11. Vì sao trên EKS dùng NLB chứ không phải ALB như tháng 1?
12. Service `LoadBalancer` báo `EXTERNAL-IP <pending>` mãi — kiểm tra những gì?
13. PVC `Pending` trên EKS — nguyên nhân có thể là gì?
14. Mật khẩu DB đi từ đâu tới Pod mà không ai gõ tay, không nằm trong Git?

---

## Portfolio Cuối Giai Đoạn

```
GitHub
├── springboot-docker                  (tháng 1, tuần 1)
├── springboot-aws-networking          (tháng 1, tuần 2)
├── springboot_aws_compute_databases   (tháng 1 tuần 3–4 + tuần 9–10)
│   ├── order-service/ payment-service/ inventory-service/ notification-service/
│   └── docs/ (event-catalog.md, system-design-2.md)
└── jaka_infrastructure                (tuần 5–8, 10) ← Terraform module + CI + EKS, K8s manifest, Helm chart, scripts
```

**`jaka_infrastructure/README.md` phải có:**
1. Sơ đồ kiến trúc cuối giai đoạn
2. Cách chạy: `./scripts/cluster-up.sh` → port-forward 8090 → `./scripts/saga-demo.sh`
3. Bảng kind vs EKS + Pod Identity
4. Bản đồ thư mục: `modules/`, `stack/`, `envs/`, `bootstrap/`, `k8s/`, `k8s/eks/`, `charts/`, `scripts/`
5. Mục "Nếu có AWS": `bootstrap` → `envs/dev-eks` apply → `./scripts/eks-up.sh` (NLB trước Envoy Gateway, ESO, RDS/ElastiCache) → `saga-demo.sh` qua DNS NLB → dọn NLB/EBS → `destroy`

**README repo service phải có:**
1. Event catalog (link `docs/event-catalog.md`) + sơ đồ sequence Saga 3 nhánh + timeout
2. Link sang `jaka_infrastructure`

---

## 📋 Quick Reference — Tuần 10

| Khái niệm | Một câu |
|---|---|
| Saga | Chuỗi local transaction + bù trừ thay cho transaction phân tán |
| Compensating transaction | Hành động "hoàn tác" nghiệp vụ, phải idempotent |
| Semantic lock | Trạng thái trung gian (`PENDING`, `RESERVED`) báo "đang xử lý, đừng đụng" |
| Choreography | Service tự nghe event và phản ứng |
| Orchestration | 1 bộ điều phối gửi command từng bước |
| EKS managed node group | ASG EC2 do EKS quản lý |
| Fargate profile | Luật chọn Pod chạy trên Fargate (mỗi Pod 1 microVM, không EBS) |
| VPC CNI | Pod lấy IP thật trong subnet VPC |
| IRSA / Pod Identity | IAM role cho từng Pod |
| AWS Load Balancer Controller | Ingress/Gateway → ALB, Service LoadBalancer → NLB |
| EBS CSI driver | PVC → EBS gp3 volume |
| External Secrets Operator | Đồng bộ Secrets Manager → k8s Secret, chart không cần biết AWS |
| `moved {}` | Đổi địa chỉ resource trong state thay vì xóa + tạo lại |
| Pod Identity association | Ghép (cluster, namespace, ServiceAccount) ↔ IAM role, ở phía AWS |
| Trust policy vs permission policy | Trust = **ai** được nhận role · permission = role **được làm gì** |
| Inline vs managed policy | Inline (`aws_iam_role_policy`) nằm trong 1 role · managed (`aws_iam_policy` + attachment) là object riêng, gắn được nhiều role |
| `ClusterSecretStore` / `ExternalSecret` | Cách ESO nói chuyện với AWS / "lấy key X → ghi vào Secret Z" |
| StorageClass mặc định | Class mà PVC không ghi `storageClassName` sẽ dùng |
| `WaitForFirstConsumer` | Chờ Pod được xếp node rồi mới tạo ổ — ổ EBS phải cùng AZ với node |
| NLB vs ALB | NLB = tầng 4 (TCP), ALB = tầng 7 (đọc URL). Có Envoy đọc URL rồi → NLB đủ |
| `EnvoyProxy` | Cấu hình Pod/Service của Envoy (ở đây: annotation để LB Controller tạo NLB) |
| `envsubst` template | YAML có `${VAR}`, script điền giá trị chỉ có sau `terraform apply` |

## 🎯 Key Learnings

- [ ] Saga 3 nhánh + timeout chạy đúng trên kind
- [ ] Module EKS pass validate/test trong CI, `envs/dev` không bị ảnh hưởng
- [ ] Toàn bộ hệ thống dựng bằng 1 script, chạy lại lần 2 không lỗi
- [ ] `envs/dev-eks` đủ data layer + ECR; 4 service có values EKS, render được
- [ ] IAM cho EBS CSI, LB Controller, ESO qua Pod Identity; giải thích được trust / permission / association
- [ ] ESO: 9 object qua `kubectl apply --dry-run=server`
- [ ] `scripts/eks-up.sh` hoàn chỉnh, `bash -n` pass; đọc Bản Đồ Hạ Tầng và giải thích được từng dòng
- [ ] Hoàn thành checkpoint giai đoạn

**Giai đoạn tiếp theo → Tháng 4: CI/CD (GitHub Actions → image → kind/EKS), ArgoCD, Prometheus, Grafana, Loki, Tracing** 🚀
