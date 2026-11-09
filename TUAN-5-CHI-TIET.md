# Tuần 5 chi tiết — Terraform Nâng Cao: Module, Remote Backend, Test, CI

> Giai đoạn gộp Tháng 2 + 3 (Tuần 5–10). **Không cần AWS** — mọi thứ chạy trên LocalStack + `terraform test` với mock provider.
>
> **Đã đổi kế hoạch (30/09):** không refactor tại chỗ `jaka_aws_networking/` bằng `moved {}` nữa. Thay vào đó dựng **project mới `springboot-aws-compute-database/jaka_modularized/`** từ đầu theo module. Lý do ở mục "Tại Sao Đổi Kế Hoạch" bên dưới.

## Bối Cảnh & Mục Tiêu

**Tháng 1 đã hoàn thành:**
- ✅ Toàn bộ hạ tầng viết bằng Terraform (VPC, ALB, ASG, RDS, ElastiCache, S3, ECR, Secrets Manager + KMS, CloudWatch, SNS)
- ✅ Resource, Variable, Output, Locals, `templatefile`, `for_each`
- ✅ Workspace (`local`, `moto`) để tách state khi chạy emulator

**Còn thiếu so với roadmap Terraform:**
- ❌ Module — code đang phẳng trong 1 thư mục (~2800 dòng), order/payment là các cặp resource copy-paste
- ❌ Remote Backend — state nằm trên máy (`terraform.tfstate`)
- ❌ Nhiều môi trường (dev/prod) dùng chung code
- ❌ Test tự động + CI

**Tuần 5 mục tiêu:**
- Dựng `jaka_modularized/` từ đầu: 8 module + 1 lớp `stack` + `envs/dev`, `envs/prod`
- **Giữ nguyên** `jaka_aws_networking/` (vẫn chạy được, scripts deploy vẫn dùng được)
- Remote backend S3 + state locking (`use_lockfile`)
- `terraform test` cho mọi module, chạy không cần AWS
- GitHub Actions: fmt / test / validate / tflint / checkov mỗi lần push

---

## Tại Sao Đổi Kế Hoạch?

Kế hoạch cũ: refactor **tại chỗ** `jaka_aws_networking/` thành module, dùng `moved {}` để đổi địa chỉ resource trong state mà không destroy.

**Vì sao lúc đầu chọn cách đó:**
- Hạ tầng đã `apply` (có state) → muốn giữ nguyên resource, không destroy/tạo lại
- `moved {}` là kỹ năng thật khi refactor hạ tầng đang chạy ở công ty

**Vì sao giờ đổi sang project mới:**

| | Refactor tại chỗ (`moved {}`) | Project mới `jaka_modularized/` |
|---|---|---|
| Project cũ | Bị sửa liên tục, dễ vỡ giữa chừng | Không đụng tới, vẫn chạy |
| `scripts/` (deploy, rollback, smoke) | Vỡ vì output đổi tên | Không ảnh hưởng |
| Rủi ro state | Sai 1 `moved` = plan muốn destroy RDS | State mới, không có gì để mất |
| Thiết kế module | Bị ép theo tên/cấu trúc cũ | Thiết kế sạch (`for_each` theo service) |
| So sánh trước/sau | Mất bản cũ | Để 2 thư mục cạnh nhau |
| Số `moved {}` phải viết | ~100 (mỗi resource 1 khối) | 0 |

Hệ quả: `moved {}` không còn là việc chính. Vẫn học nó qua một bài tập nhỏ **bên trong** project mới (Chủ Đề 1, phần "Bài tập `moved {}`").

```
Trước:
  jaka_aws_networking/
    ├─ vpc.tf, alb.tf, asg.tf, rds.tf, ... (26 file phẳng)
    └─ terraform.tfstate          ← nằm trên laptop
  ❌ Muốn dựng prod → copy nguyên thư mục, sửa tay
  ❌ Thêm inventory-service (tuần 9) → copy ~15 resource order_* thành inventory_*
  ❌ Mất laptop = mất state
  ❌ Không có test → sửa 1 SG rule, không biết có vỡ gì

Sau tuần 5 (project mới, project cũ giữ nguyên):
  jaka_modularized/
    modules/ (network, security, storage, alb, postgres, redis, service, observability)
    stack/   → for_each over var.services
    envs/dev, envs/prod
  ✅ Thêm service = 1 dòng trong map
  ✅ State trên S3, có lock
  ✅ terraform test chạy trong CI, không cần AWS
```

---

## Chủ Đề 1: Module

### Nội Dung Phải Học

- **Module là gì**: một thư mục `.tf` có input (`variable`), output (`output`), giống một function
- **Root module vs child module**: root là thư mục bạn chạy `terraform apply` (`envs/dev`), child là thư mục được gọi bằng `module "x" { source = ... }`
- **Building-block module vs composition**: `modules/*` không biết "order" hay "payment"; `stack/` mới là nơi ghép chúng lại
- **`for_each` trên module**: `module "service" { for_each = var.services }` → 1 lần gọi = N service
- **Source**: local path (`../modules/network`), Git, Terraform Registry
- **Interface tốt**: input tối thiểu, output đủ dùng, mọi variable có `description`, validate input
- **Dependency qua output**: truyền output (vd. ARN của secret version) thay vì `depends_on` xuyên module
- **`moved {}`**: đổi địa chỉ resource trong state mà không destroy (học qua bài tập nhỏ)
- **Khi nào KHÔNG nên tách module**: resource dùng 1 lần, không có ranh giới rõ ràng

### Cấu Trúc Tiêu Chuẩn

```
springboot-aws-compute-database/
├── jaka_aws_networking/        ← GIỮ NGUYÊN (project tháng 1)
└── jaka_modularized/           ← MỚI
    ├── bootstrap/              # bucket S3 chứa state
    ├── modules/
    │   ├── network/            # VPC, subnet, IGW, NAT, route table, 4 SG
    │   ├── security/           # KMS key cho secret, IAM user SSM
    │   ├── storage/            # S3 dùng chung (prefix theo service)
    │   ├── alb/                # ALB + listener :80 (mặc định 404)
    │   ├── postgres/           # 1 DB của 1 service
    │   ├── redis/              # ElastiCache dùng chung
    │   ├── service/            # 1 microservice: ECR, IAM, TG, route, LT, ASG
    │   └── observability/      # SNS, alarm, dashboard
    │   (mỗi module: variables.tf, outputs.tf, versions.tf, tests/)
    ├── stack/                  # ghép module; for_each theo var.services
    ├── envs/
    │   ├── dev/                # main.tf gọi stack + backend.hcl
    │   └── prod/
    ├── localstack/docker-compose.yml
    └── .github/workflows/terraform.yml
```

**Vì sao chia ranh giới như vậy:**

| Module | Quy tắc tách | Số instance |
|---|---|---|
| `network` | Ít thay đổi, mọi thứ khác nằm trong nó | 1 |
| `postgres` | Database-per-service | 1 / service |
| `service` | Mọi thứ 1 microservice sở hữu | 1 / service |
| `observability` | Phụ thuộc tất cả, không ai phụ thuộc nó | 1 |

**Luồng dữ liệu giữa các module:**

```
network ──(vpc_id, private_subnet_ids, sg ids)──► alb, postgres, redis, service
security ─(kms key)─────────────────────────────► postgres, redis, service
alb ──────(http_listener_arn)───────────────────► service (tự gắn path rule)
postgres ─(host, secret arn, url param)─────────► service (user-data + IAM)
redis ────(param names, auth secret)────────────► service
service ──(asg name, tg suffix, log group)──────► observability
```

**Ví dụ `stack/main.tf`:**

```hcl
module "postgres" {
  source   = "../modules/postgres"
  for_each = var.services

  name_prefix    = local.name_prefix
  service        = each.key
  db_name        = each.value.db_name
  admin_username = "${each.key}_admin"
  app_username   = "${each.key}_user"
  multi_az       = var.postgres.multi_az   # dev = false, prod = true
  # ...
}

module "service" {
  source   = "../modules/service"
  for_each = var.services

  database = {
    host           = module.postgres[each.key].address
    app_secret_arn = module.postgres[each.key].app_secret_arn
    # ...
  }
}
```

**Bài tập `moved {}` (trong project mới):**

Đổi tên 1 resource trong `modules/alb` rồi chứng minh plan không destroy:

```hcl
# modules/alb/main.tf — đổi aws_lb_listener.http thành aws_lb_listener.public
moved {
  from = aws_lb_listener.http
  to   = aws_lb_listener.public
}
```

```bash
cd envs/dev
terraform plan -var-file terraform.tfvars -var-file localstack.tfvars
# Kỳ vọng: "has moved to ...", "0 to add, 0 to change, 0 to destroy"
```

Thử lại nhưng **bỏ** khối `moved` → plan báo 1 destroy + 1 create. Đó chính là rủi ro mà kế hoạch cũ phải xử lý ~100 lần.

**Output cụ thể:**
- 8 module + `stack`, mọi variable có `description`
- `jaka_aws_networking/` không bị sửa dòng nào
- Chứng minh:
  - `terraform validate` pass ở `envs/dev`, `envs/prod`
  - Test `third_service_is_one_map_entry` pass (thêm inventory = 1 dòng map)
  - Bài tập `moved {}`: plan = 0 add / 0 destroy
- README: sơ đồ module + bảng khác biệt với `jaka_aws_networking`

---

## Chủ Đề 2: Remote Backend & State Locking

### Nội Dung Phải Học

- **State là gì**: bản đồ giữa code và resource thật (ID, attribute). Mất state = Terraform "quên" hạ tầng
- **Remote backend S3**: state lưu trong bucket, bật versioning để rollback state
- **Locking**:
  - Cách cũ: DynamoDB table (`dynamodb_table`)
  - Cách mới (Terraform ≥ 1.10): `use_lockfile = true`, lock bằng file `.tflock` ngay trên S3, không cần DynamoDB
- **Bootstrap problem**: bucket chứa state phải tạo trước → root module riêng (`bootstrap/`) với local state
- **Partial backend config**: `backend "s3" {}` trong code, phần còn lại ở `backend.hcl` / `backend.localstack.hcl`, cùng code chạy được cả AWS lẫn LocalStack
- **Tách state theo env**: key khác nhau (`dev/terraform.tfstate`, `prod/terraform.tfstate`) → blast radius nhỏ
- **Sensitive data trong state**: password (`random_password`) nằm plain text trong state → bucket phải mã hóa + chặn public
- **`terraform init -migrate-state`**: chuyển state local → S3 (project mới không cần, nhưng biết để dùng khi đưa `jaka_aws_networking` lên S3)

### Cấu Trúc Tiêu Chuẩn

```
bootstrap/                 (local state, chạy 1 lần)
  └─ main.tf  → bucket jaka-mod-tfstate-<account_id>
               versioning + SSE + block public + HTTPS-only + prevent_destroy

envs/dev/versions.tf       backend "s3" {}             ← partial
envs/dev/backend.hcl       AWS thật
envs/dev/backend.localstack.hcl  LocalStack (account 000000000000)
  └─ s3://jaka-mod-tfstate-000000000000/dev/terraform.tfstate (+ .tflock)
```

**`envs/dev/backend.localstack.hcl`:**

```hcl
bucket       = "jaka-mod-tfstate-000000000000"
key          = "dev/terraform.tfstate"
region       = "ap-southeast-1"
encrypt      = true
use_lockfile = true

endpoints = {
  s3 = "http://localhost:4566"
}
use_path_style              = true
access_key                  = "test"
secret_key                  = "test"
skip_credentials_validation = true
skip_requesting_account_id  = true
skip_metadata_api_check     = true
skip_s3_checksum            = true
```

**Chạy:**

```bash
cd jaka_modularized/bootstrap
terraform init
terraform apply -var use_localstack=true
```

```bash
cd ../envs/dev
terraform init -backend-config backend.localstack.hcl
terraform plan -var-file terraform.tfvars -var-file localstack.tfvars
```

**Output cụ thể:**
- `bootstrap/` tạo bucket state (versioning + SSE + block public)
- Backend S3 cho `envs/dev` và `envs/prod`, key riêng
- Chứng minh:
  - Mở 2 terminal, chạy `terraform plan` cùng lúc → terminal thứ 2 báo **"Error acquiring the state lock"**
  - `aws --endpoint-url http://localhost:4566 s3 ls s3://jaka-mod-tfstate-000000000000 --recursive` thấy file state
  - Xóa `.terraform/` local, `init` lại → state vẫn còn
- README: cách bootstrap backend

---

## Chủ Đề 3: Nhiều Môi Trường (dev / prod)

### Nội Dung Phải Học

- **Directory per env** (`envs/dev`, `envs/prod`) vs **workspace**:
  - Directory: backend riêng, cấu hình nhìn thấy rõ trong file → **khuyên dùng cho prod**
  - Workspace: cùng code, khác state → hợp cho môi trường tạm (như `local`/`moto` bạn dùng ở tháng 1)
- **Env root mỏng**: `envs/*/main.tf` chỉ gọi `stack` với giá trị khác nhau → `diff` 2 file thấy toàn bộ khác biệt
- **Khác biệt dev vs prod điển hình**: instance size, Multi-AZ, số NAT, số node Redis, retention log, deletion protection
- **Variable validation**: chặn giá trị sai ngay lúc plan

### Cấu Trúc Tiêu Chuẩn

| Setting | dev | prod |
|---|---|---|
| NAT gateway | 1 dùng chung | 1 / AZ |
| `instance_type` | `t3.micro` | `t3.small` |
| ASG min / max / desired | 1 / 2 / 1 | 2 / 6 / 2 |
| RDS Multi-AZ | `false` | `true` |
| RDS deletion protection / final snapshot | tắt / bỏ qua | bật / có |
| Redis node | 1 (tự tắt failover) | 2 (failover + Multi-AZ) |
| S3 `force_destroy` | `true` | `false` |
| Log retention | 7 ngày | 30 ngày |

```hcl
variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be dev or prod."
  }
}
```

**Output cụ thể:**
- `envs/dev/main.tf` và `envs/prod/main.tf` khác nhau đúng bảng trên
- Chứng minh:
  - `diff envs/dev/main.tf envs/prod/main.tf`
  - `terraform plan` ở prod hiển thị `multi_az = true`, ở dev là `false` (LocalStack)
  - Test `staging_is_rejected` pass

---

## Chủ Đề 4: `terraform test` Không Cần AWS

### Nội Dung Phải Học

- **`terraform test`** (Terraform ≥ 1.6): file `*.tftest.hcl`, mỗi `run` block = 1 lần plan/apply + `assert`
- **`command = plan`**: chỉ plan, không tạo resource → nhanh, an toàn
- **`mock_provider`** (≥ 1.7): giả lập provider, không cần credentials → chạy được trong CI không có AWS
- **`mock_data`**: ép giá trị cho data source (`aws_caller_identity`, `aws_region`, `aws_iam_policy_document`)
- **`expect_failures`**: test rằng validation **có** chặn input sai
- **Bẫy kiểu dữ liệu**: `at_rest_encryption_enabled` của ElastiCache là **string** trong provider v5 → so sánh bằng `tobool(...)`
- Test cái gì: số subnet theo AZ, SG không mở CIDR cho DB, IMDSv2, IAM chỉ đọc param của mình, tên resource, validation

### Cấu Trúc Tiêu Chuẩn

```hcl
# modules/network/tests/network.tftest.hcl
mock_provider "aws" {}

variables {
  name_prefix          = "jaka-test"
  vpc_cidr             = "10.0.0.0/16"
  azs                  = ["ap-southeast-1a", "ap-southeast-1b"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24"]
}

run "one_public_and_one_private_subnet_per_az" {
  command = plan

  assert {
    condition     = length(aws_subnet.public) == 2 && length(aws_subnet.private) == 2
    error_message = "Expected exactly one public and one private subnet per AZ."
  }
}

run "invalid_cidr_is_rejected" {
  command = plan
  variables {
    vpc_cidr = "not-a-cidr"
  }
  expect_failures = [var.vpc_cidr]
}
```

```bash
cd jaka_modularized
for d in modules/* stack; do (cd $d && terraform init -input=false >/dev/null && terraform test); done
```

**Output cụ thể:**
- Mỗi module có ≥ 2 `run` block (happy path + validation); tổng hiện tại 39 test
- Chứng minh: `Success! N passed, 0 failed.` ở mọi thư mục, không set biến môi trường AWS nào

---

## Chủ Đề 5: CI cho Terraform (GitHub Actions)

### Nội Dung Phải Học

- **Pipeline chuẩn**: `fmt -check` → `init` → `validate` → `test` → `tflint` → `checkov`
- **`init -backend=false`**: CI validate `envs/*` không cần truy cập state
- **Matrix**: mỗi module 1 job test song song
- **tflint + ruleset aws**: bắt instance type sai, thuộc tính deprecated
- **checkov**: bắt misconfig bảo mật (bucket không mã hóa, SG mở toàn bộ, RDS không backup...)
- **Skip có lý do**: `#checkov:skip=CKV_AWS_xxx: lý do`, không skip bừa
- **Chuẩn bị sau này**: thêm job `plan` với OIDC role khi có AWS

### Cấu Trúc Tiêu Chuẩn

File đầy đủ: `jaka_modularized/.github/workflows/terraform.yml`. Rút gọn:

```yaml
jobs:
  fmt:
    steps:
      - run: terraform fmt -check -recursive
  test:
    strategy:
      matrix:
        dir: [modules/network, modules/security, modules/storage, modules/alb,
              modules/postgres, modules/redis, modules/service, modules/observability, stack]
    steps:
      - working-directory: ${{ matrix.dir }}
        run: |
          terraform init -input=false
          terraform validate
          terraform test
  validate-envs:
    strategy:
      matrix:
        env: [dev, prod]
    steps:
      - working-directory: envs/${{ matrix.env }}
        run: |
          terraform init -input=false -backend=false
          terraform validate
  tflint:  ...
  checkov: ...
```

> Workflow chỉ chạy khi `jaka_modularized/` là **gốc của một Git repo riêng** (giống `jaka_aws_networking/` có `.git` riêng).

**Chạy trước ở local (bật Docker Desktop trước):**

```bash
docker run --rm -v "$PWD:/data" -w /data --entrypoint sh ghcr.io/terraform-linters/tflint -c "tflint --init && tflint --recursive --config /data/.tflint.hcl"
docker run --rm -v "$PWD:/tf" bridgecrew/checkov -d /tf --quiet
```

**Output cụ thể:**
- Workflow chạy xanh trên GitHub
- Chứng minh:
  - Screenshot tab Actions xanh
  - Cố tình thêm ingress `0.0.0.0/0` vào RDS SG → CI đỏ → revert → xanh
- Badge CI trong README

---

## Portfolio Cuối Tuần — `jaka_modularized/`

```
jaka_modularized/
├── bootstrap/
├── modules/
│   ├── network/  security/  storage/  alb/
│   ├── postgres/ redis/     service/  observability/
│   │   (mỗi module có tests/)
├── stack/        (+ tests/)
├── envs/
│   ├── dev/
│   └── prod/
├── localstack/
├── .github/workflows/terraform.yml
├── .tflint.hcl
└── README.md
```

**README.md phải trả lời được:**
1. Vì sao chia module theo ranh giới này?
2. Vì sao dựng project mới thay vì refactor tại chỗ? `moved {}` dùng khi nào?
3. State ở đâu, lock bằng gì, nếu 2 người apply cùng lúc thì sao?
4. dev khác prod những gì?
5. Test cái gì, chạy thế nào mà không cần AWS?
6. Khác gì so với `jaka_aws_networking` (tên resource, ALB 404, SSH, IAM)?

---

## 📋 Quick Reference — Tuần 5

| Lệnh / Khái niệm | Dùng để |
|---|---|
| `module "x" { source = "..." }` | Gọi module |
| `module "x" { for_each = var.services }` | 1 module call cho N service |
| `moved { from = ... to = ... }` | Đổi địa chỉ resource không destroy |
| `backend "s3" {}` + `-backend-config backend.hcl` | Partial backend config |
| `terraform init -migrate-state` | Chuyển state local → remote |
| `use_lockfile = true` | Lock state bằng file trên S3 (≥ 1.10) |
| `terraform test` | Chạy `*.tftest.hcl` |
| `mock_provider "aws" {}` / `mock_data` | Test không cần credentials |
| `expect_failures` | Test validation có chặn input sai |
| `terraform init -backend=false` | Validate trong CI không cần state |

## 🎯 Key Learnings

- [ ] Giải thích được root module vs child module vs composition (`stack`)
- [ ] Giải thích được vì sao dựng project mới thay vì refactor tại chỗ
- [ ] Bài tập `moved {}`: đổi tên resource, plan = 0 thay đổi
- [ ] Remote backend S3 + lock hoạt động trên LocalStack
- [ ] dev/prod tách thư mục, khác nhau chỉ ở `envs/*/main.tf`
- [ ] Mỗi module có `terraform test` pass không cần AWS
- [ ] CI GitHub Actions xanh (fmt, test, validate, tflint, checkov)

**Tuần sau → Tuần 6: Kubernetes cơ bản trên kind** 🚀
