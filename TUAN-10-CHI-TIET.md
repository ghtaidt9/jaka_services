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
  - **EBS CSI driver**: StorageClass `gp3` cho PVC (thay `standard` của kind trong `k8s/*/…-db.yml`)
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

`outputs.tf`: `cluster_name`, `cluster_endpoint`, `node_security_group_id`. Không output `oidc_provider_arn`: dự án dùng Pod Identity (Bước 6), nên đặt `enable_irsa = false` trong `module "eks"` — không cần OIDC provider.

#### Bước 3 — Test: `modules/eks/tests/eks.tftest.hcl`

```hcl
mock_provider "aws" {}
# Module EKS kéo thêm provider khác (tls, time, cloudinit...). Sau "terraform init",
# chạy "terraform providers" và thêm 1 mock_provider cho MỖI provider trong danh sách.

variables {
  name_prefix        = "jaka-mod-dev-eks"
  kubernetes_version = "1.35"   # chọn bản đang "standard support" lúc bạn làm (trang EKS Kubernetes versions)
  vpc_id             = "vpc-123"
  private_subnet_ids = ["subnet-a", "subnet-b"]
  node_instance_type = "t3.medium"
  node_min           = 2
  node_max           = 4
}

run "chan_node_min_nho_hon_2" {
  command   = plan
  variables { node_min = 1 }
  expect_failures = [var.node_min]
}

run "chan_1_subnet" {
  command   = plan
  variables { private_subnet_ids = ["subnet-a"] }
  expect_failures = [var.private_subnet_ids]
}

run "plan_hop_le" {
  command = plan
}
```

> Đừng viết `assert { condition = var.node_min >= 2 }` — nó chỉ kiểm tra giá trị bạn tự đặt, luôn pass. Chứng minh đúng là **validation chặn được input sai** (`expect_failures`).
>
> `mock_provider` trả chuỗi ngẫu nhiên cho mọi data source (ARN, policy JSON...). Nếu `run "plan_hop_le"` lỗi bên trong module EKS, dùng `override_data` cho data source đó, hoặc `override_module { target = module.eks }` để bỏ qua hẳn module con — bạn chỉ cần test lớp bọc của mình.

```bash
cd modules/eks
terraform init
terraform validate
terraform test
```

#### Bước 4 — `envs/dev-eks` (cùng khuôn `envs/dev`)

`versions.tf` — giống `envs/dev/versions.tf` (`required_version >= 1.10.0`, `backend "s3" {}` partial) nhưng `aws ~> 6.0`, bỏ `random`.

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

#### Bước 6 (đọc hiểu) — Pod Identity cho từng service

```hcl
resource "aws_eks_pod_identity_association" "order" {
  cluster_name    = module.eks.cluster_name
  namespace       = "dev"
  service_account = "order-service"
  role_arn        = aws_iam_role.order_pod.arn   # chỉ được đọc SSM /jaka-mod/dev-eks/order/*
}
```

Chart `charts/spring-service` **chưa có** ServiceAccount — khi lên EKS thật: thêm `templates/serviceaccount.yml` + `serviceAccountName` trong `deployment.yml`, tên SA = tên release (`order-service`...). Trên kind không cần.

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
| 4. Add-on | `k8s/metrics-server-patch.json`; Envoy Gateway `v1.9.2`; Strimzi `latest` |
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
set -euo pipefail
cd "$(dirname "$0")/.."

services=(order payment inventory notification)
app_root=..                                  # nơi có order-service/order-service/...
tag=${TAG:-dev-$(date +%s)}                  # tag mới mỗi lần: chart dùng IfNotPresent

# Mật khẩu lấy từ biến môi trường, không hardcode — thiếu biến nào là dừng ngay
for svc in "${services[@]}"; do
  for suffix in DB_PASS DB_ADMIN_PASS; do
    name="${svc^^}_${suffix}"                # vd ORDER_DB_PASS
    [[ -n "${!name:-}" ]] || { echo "missing env $name" >&2; exit 1; }
  done
done

# 1. Cluster
kind get clusters | grep -qx jaka || kind create cluster --config kind-config.yaml

# 2. Image
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

# 4. Add-on
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
if ! kubectl get deploy metrics-server -n kube-system \
     -o jsonpath='{.spec.template.spec.containers[0].args}' | grep -q kubelet-insecure-tls; then
  kubectl patch deploy metrics-server -n kube-system --type=json --patch-file k8s/metrics-server-patch.json
fi
helm upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version v1.9.2 \
  -n envoy-gateway-system --create-namespace --wait
if [[ -z "$(kubectl get deploy strimzi-cluster-operator -n kafka --ignore-not-found -o name)" ]]; then
  kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
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
```

```bash
#!/usr/bin/env bash
# scripts/cluster-down.sh
set -euo pipefail
kind delete cluster --name jaka
```

Ghi chú:
- **Job tạo role** (`create-app-role-job.yml`) đi cùng bước 6. Chạy lại khi Job còn → `unchanged`; Job đã bị TTL (300 giây) xóa → `apply` tạo lại và chạy lại — vô hại vì SQL idempotent (`\if :role_exists ... ALTER ROLE`). Không cần `kubectl wait job`: app chưa login được thì readiness fail và tự retry
- **Strimzi** dùng `kubectl create` (CRD quá lớn cho `apply`) → phải kiểm tra trước, đừng nuốt lỗi
- **Tag theo timestamp**: mỗi lần chạy lại script là rollout lại cả 4 service. Muốn tránh → `TAG=v5 ./scripts/cluster-up.sh`, nhưng nhớ: tag cũ + `IfNotPresent` = vẫn chạy image cũ
- **Image cũ tích lũy trong node kind** sau nhiều lần load → thỉnh thoảng dọn:

```bash
for n in jaka-control-plane jaka-worker jaka-worker2; do docker exec "$n" crictl rmi --prune; done
```

- Không port-forward vào `8080` — `kind-config.yaml` đã map host 8080 → node control-plane

**Output cụ thể:**
- `scripts/cluster-up.sh` + `scripts/cluster-down.sh` (+ `redeploy.sh` nếu làm)
- Chứng minh:
  - Từ máy trống → toàn bộ hệ thống chạy, không thao tác tay:

```bash
./scripts/cluster-down.sh
export ORDER_DB_PASS=... ORDER_DB_ADMIN_PASS=...   # 8 biến
./scripts/cluster-up.sh
kubectl get pods -A
```

  - Chạy `./scripts/cluster-up.sh` **lần 2** ngay sau đó → không lỗi
  - Saga demo (Chủ đề 1) pass cả 3 kịch bản + timeout **trên kind**, qua Gateway port-forward `8090`. Chủ đề 1 viết `saga-demo.ps1` — trên Linux viết `scripts/saga-demo.sh` (curl + `jq`)
  - Screenshot `kubectl get pods -A`

---

## Chủ Đề 4: Tổng Kết Giai Đoạn Tháng 2 + 3

### Kiến Trúc Cuối Giai Đoạn

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
Terraform (jaka_infrastructure, không apply):
  modules/ network, security, storage, alb, postgres, redis, service, observability, eks
  stack/ (bộ EC2 tháng 1) · envs/dev, envs/dev-eks · bootstrap/ (bucket state)
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
| 10 | Saga + EKS | Saga 3 nhánh + timeout + refund, `modules/eks`, `envs/dev-eks`, `cluster-up.sh` | `order-service/`, `jaka_infrastructure/` |

### So Với Roadmap Gốc

| Roadmap gốc | Giai đoạn gộp | Ghi chú |
|---|---|---|
| Tuần 5–6 Terraform (2 tuần) | Tuần 5 (1 tuần) | Terraform cơ bản đã xong ở tháng 1 |
| Tuần 7–8 K8s cơ bản | Tuần 6–7 | |
| Tuần 9–10 Helm + EKS | Tuần 8 (Helm) + tuần 10 (EKS Terraform) | EKS không apply được vì không có AWS |
| Tuần 11–12 System Design + Kafka + Saga | Tuần 8–10 (song song) | |
| **8 tuần** | **6 tuần** | **Dư 2 tuần** → dồn cho Tháng 5–6 (AI) hoặc buffer |

### Checkpoint Giai Đoạn

| Hạng mục | Kiểm tra bằng | Ghi chú từ repo hiện tại |
|---|---|---|
| Terraform: module + remote backend + `terraform test` + CI xanh | Tab Actions trên GitHub | ⚠️ Ngoài `modules/eks`, **chưa module nào có `tests/*.tftest.hcl`** → job `test` của CI đang pass mà không test gì. Thêm ít nhất 1 test validation cho `network` (`azs` < 2 → `expect_failures`) |
| K8s: giải thích Pod → ReplicaSet → Deployment → Service | Tự nói lại, `kubectl get deploy,rs,pods -n dev` | |
| 4 microservice trên kind: Gateway, HPA, StatefulSet | `kubectl get pods,sts,hpa -n dev` | HPA chỉ bật ở `*-prod.yml` — muốn thấy HPA trên kind thì `--set autoscaling.enabled=true` |
| Kafka event-driven: Outbox + idempotent consumer + DLT | Chứng minh Chủ đề 3 tuần 9 | |
| Saga xử lý đúng 3 nhánh + timeout (+ refund khi payment đến muộn) | `scripts/saga-demo.sh` | |
| Module EKS viết xong, hiểu khác biệt kind vs EKS | `terraform test` trong `modules/eks` | |
| Trả lời được System Design: REST/gRPC, LB (L4/L7), scalability, caching, CAP, event-driven, Saga, CQRS | Câu hỏi bên dưới | |
| README đầy đủ + screenshot | `jaka_infrastructure/README.md` hiện mới có mục rolling update vs instance refresh | Thêm: cách chạy `cluster-up.sh`, bảng kind vs EKS, mục "Nếu có AWS" |
| `docs/event-catalog.md` khớp code | So với `dto/` của 4 service | Cập nhật mục 4–6 cho `OrderConfirmed`, `OrderCancelled`, `RefundRequested` |

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
4. Bản đồ thư mục: `modules/`, `stack/`, `envs/`, `bootstrap/`, `k8s/`, `charts/`, `scripts/`
5. Mục "Nếu có AWS": `bootstrap` → `envs/dev-eks` apply → cài AWS LB Controller → chuyển Gateway sang ALB → `destroy`

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

## 🎯 Key Learnings

- [ ] Saga 3 nhánh + timeout chạy đúng trên kind
- [ ] Module EKS pass validate/test trong CI, `envs/dev` không bị ảnh hưởng
- [ ] Toàn bộ hệ thống dựng bằng 1 script, chạy lại lần 2 không lỗi
- [ ] Hoàn thành checkpoint giai đoạn

**Giai đoạn tiếp theo → Tháng 4: CI/CD (GitHub Actions → image → kind/EKS), ArgoCD, Prometheus, Grafana, Loki, Tracing** 🚀
