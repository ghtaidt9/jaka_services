# Tuần 2 chi tiết — AWS Networking: VPC, Security Groups, ALB, IAM

## Bối Cảnh & Mục Tiêu

**Tuần 1 (Dockerize) đã hoàn thành:**
- ✅ Có 5 container chạy local (order-service, payment-service, 2x Postgres, Redis)
- ✅ Có `docker-compose.yml` orchestrating everything
- ✅ Docker images built & tested

**Tuần 2 mục tiêu:**
- Bước lên AWS để chạy trên cloud
- Hiểu rõ networking trong AWS: VPC, subnet, security groups
- Setup ALB (Application Load Balancer) để route traffic
- Chuẩn bị infrastructure để tuần 3 deploy EC2 + RDS

---

## Tại Sao Cần Tuần 2?

```
Local Docker → Docker Desktop (standalone)
AWS EC2 → Chạy trong VPC → Cần networking!

VPC (Virtual Private Cloud) = Network ảo của bạn trên AWS
  ├─ Subnets (public + private)
  ├─ Security Groups (firewall)
  ├─ ALB (LoadBalancer để route traffic tới EC2)
  └─ NAT/IGW (để EC2 kết nối ra internet)

Nếu bỏ qua tuần này:
  ❌ Không biết cấu hình network (lỡ cấu hình sai = service unreachable)
  ❌ Không hiểu security (mở port sai = bị hack)
  ❌ Không biết setup ALB (không thể load balance)
```

---

## Chủ Đề 1: VPC Fundamentals & Architecture

### Nội Dung Phải Học

- **VPC là gì**: Virtual Private Cloud — network ảo độc lập, nơi EC2/RDS chạy
- **CIDR Notation**: Cách định nghĩa IP ranges (10.0.0.0/16 = 10.0.0.0 tới 10.0.255.255)
- **Subnets**: Chia VPC thành public (có internet) và private (không có internet trực tiếp)
- **Internet Gateway (IGW)**: Cổng để public subnet kết nối ra internet
- **NAT Gateway**: Cho phép private subnet kết nối ra internet (một chiều)
- **Route Tables**: Định nghĩa luật định tuyến (traffic từ subnet A → tới đâu)
- **Availability Zones (AZ)**: Các data center khác nhau trong region (multi-AZ = high availability)

### Cấu Trúc Tiêu Chuẩn

```
AWS Region (e.g., us-east-1)
  ├─ VPC (10.0.0.0/16)
  │   ├─ Availability Zone 1a
  │   │   ├─ Public Subnet (10.0.1.0/24) → Có IGW → ALB & NAT ở đây
  │   │   └─ Private Subnet (10.0.2.0/24) → EC2 ở đây
  │   │
  │   └─ Availability Zone 1b
  │       ├─ Public Subnet (10.0.3.0/24) → Backup ALB ở đây
  │       └─ Private Subnet (10.0.4.0/24) → Backup EC2 ở đây
  │
  ├─ Internet Gateway → public subnets dùng để ra internet
  └─ NAT Gateway → private subnets kết nối ra internet (một chiều)
```

**Output cụ thể:**
- Tạo 1 VPC với 4 subnets (2 public, 2 private) trong 2 AZs
- Tạo 1 IGW và attach vào VPC
- Tạo 1 NAT Gateway trong public subnet 1a
- Tạo 2 route tables: 1 cho public subnets (route to IGW), 1 cho private subnets (route to NAT)
- Chứng minh: EC2 test instance trong private subnet có thể ping ra internet (thông qua NAT)
- Ghi vào README: sơ đồ VPC + giải thích từng component

---

## Chủ Đề 2: Security Groups & NACLs (Network Access Control Lists)

### Nội Dung Phải Học

- **Security Groups**: Stateful firewall (luật inbound/outbound), mở port cho traffic cần thiết
  - Luật: protocol (TCP/UDP), port range, source/destination CIDR
  - Stateful = nếu cho inbound → outbound tự động allow
- **NACLs**: Stateless firewall (subnet level), backup cho security groups
- **Principle of Least Privilege**: Chỉ mở port thực sự cần, block tất cả else
- **Security Groups cho Application**: 
  - **ALB SG**: Inbound HTTP (80), HTTPS (443) từ 0.0.0.0/0 (anywhere)
  - **EC2 SG**: Inbound từ ALB SG (không phải 0.0.0.0/0), port 8080-8081
  - **RDS SG**: Inbound từ EC2 SG, port 5432 (Postgres)
  - **Redis SG**: Inbound từ EC2 SG, port 6379

### Cấu Trúc Tiêu Chuẩn

```
┌─────────────────────────────────────────────────────┐
│ ALB Security Group (alb-sg)                         │
│ ├─ Inbound: HTTP (80) từ 0.0.0.0/0 (công chúng)   │
│ ├─ Inbound: HTTPS (443) từ 0.0.0.0/0              │
│ └─ Outbound: ALL (mặc định)                        │
└─────────────────────────────────────────────────────┘
                        ↓ (Forward to)
┌─────────────────────────────────────────────────────┐
│ EC2 Security Group (app-sg)                        │
│ ├─ Inbound: TCP 8080-8081 từ alb-sg (ALB only)    │
│ ├─ Inbound: TCP 22 từ YOUR_IP (SSH access)        │
│ └─ Outbound: ALL                                   │
└─────────────────────────────────────────────────────┘
         ↓ (Connect to DB)    ↓ (Connect to Cache)
    ┌─────────────────┐  ┌──────────────────┐
    │ RDS SG          │  │ Redis SG         │
    │ Inbound:        │  │ Inbound:         │
    │ 5432 from app-sg│  │ 6379 from app-sg │
    └─────────────────┘  └──────────────────┘
```

**Output cụ thể:**
- Tạo 3 security groups: alb-sg, app-sg, db-sg
- Cấu hình rules như diagram trên
- Chứng minh: 
  - EC2 instance có thể reach ALB (port 8080 open)
  - ALB có thể reach internet (port 80 open)
  - EC2 KHÔNG thể reach từ internet trực tiếp (security)
- Ghi vào README: security group rules + giải thích principle

---

## Chủ Đề 3: Application Load Balancer (ALB)

### Nội Dung Phải Học

- **ALB là gì**: Reverse proxy, phân phối traffic từ internet tới backend EC2s
- **Target Groups**: Nhóm EC2s được ALB forward traffic tới (có healthcheck)
- **Listeners**: Định nghĩa port ALB lắng nghe (80, 443) và rule forward tới target group nào
- **Healthcheck**: ALB định kỳ check EC2 có sẵn sàng không (nếu down → remove khỏi pool)
- **Path-based routing**: Định tuyến dựa trên URL path (/api/orders → order-service, /api/payments → payment-service)
- **Multi-AZ**: ALB có node trong 2+ AZs → high availability

### Cấu Trúc Tiêu Chuẩn

```
Internet (users)
  ↓ http://yourdomain.com/api/orders
  ↓
ALB (yourdomain-alb.us-east-1.elb.amazonaws.com)
  ├─ Listener: Port 80 (HTTP)
  │   ├─ Rule 1: Path /api/orders → Target Group: order-tg
  │   └─ Rule 2: Path /api/payments → Target Group: payment-tg
  │
  ├─ Target Group: order-tg
  │   ├─ EC2-1 (order-service, port 8080) ✅ healthy
  │   └─ EC2-2 (order-service, port 8080) ✅ healthy
  │   └─ Healthcheck: GET /actuator/health every 30s
  │
  └─ Target Group: payment-tg
      ├─ EC2-3 (payment-service, port 8081) ✅ healthy
      └─ EC2-4 (payment-service, port 8081) ✅ healthy
      └─ Healthcheck: GET /actuator/health every 30s
```

**Output cụ thể:**
- Tạo 1 ALB trong public subnets (2 AZs)
- Tạo 2 target groups: order-tg, payment-tg
- Cấu hình healthcheck (path: /actuator/health, port: 8080/8081)
- Tạo 1 listener (port 80) với path-based routing rules
- Chứng minh: 
  - `curl http://ALB-DNS/api/orders` → nhận response từ order-service
  - `curl http://ALB-DNS/api/payments` → nhận response từ payment-service
  - Kiểm tra target group health (tất cả healthy)
- Ghi vào README: ALB DNS URL + routing rules

---

## Chủ Đề 4: IAM Roles & Policies (Identity & Access Management)

### Nội Dung Phải Học

- **IAM Role**: Identity mà EC2/Lambda/RDS có thể assume để access AWS services
- **IAM Policy**: JSON document định nghĩa permissions (allow/deny)
- **Principle of Least Privilege**: EC2 chỉ có permission tối thiểu cần dùng
- **EC2 Instance Profile**: Role được attach vào EC2 instance
- **Permissions cần thiết cho microservices:**
  - EC2 → CloudWatch (ghi logs)
  - EC2 → S3 (read/write files, tuần 4)
  - EC2 → Secret Manager (đọc API keys, tuần 4)
  - RDS → CloudWatch (monitoring)

### Cấu Trúc Policy Tiêu Chuẩn

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:us-east-1:ACCOUNT_ID:*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": "arn:aws:s3:::my-microservices-bucket/*"
    }
  ]
}
```

**Output cụ thể:**
- Tạo 1 IAM role: microservices-ec2-role
- Attach inline policies: CloudWatch logs, S3 access
- Tạo 1 instance profile: microservices-instance-profile
- Associate vào EC2 (tuần 3)
- Chứng minh: EC2 có thể write logs tới CloudWatch (không cần credentials trong code)
- Ghi vào README: IAM role ARN + policies attached

---

## Portfolio Cuối Tuần — Repo `springboot-aws-networking`

Cấu trúc thư mục gợi ý:
```
springboot-aws-networking/
├── terraform/
│   ├── vpc.tf                    # VPC + subnets
│   ├── igw.tf                    # Internet Gateway
│   ├── nat.tf                    # NAT Gateway
│   ├── route-tables.tf           # Route tables
│   ├── security-groups.tf        # Security groups
│   ├── alb.tf                    # Application Load Balancer
│   ├── iam.tf                    # IAM roles & policies
│   ├── variables.tf              # Variables
│   ├── outputs.tf                # Output VPC ID, ALB DNS, etc.
│   └── terraform.tfvars          # Values for variables (add to .gitignore)
│
├── diagrams/
│   ├── vpc-architecture.png      # VPC diagram
│   ├── security-groups.png       # SG rules diagram
│   └── alb-routing.png           # ALB path-based routing diagram
│
├── scripts/
│   ├── test-alb.sh               # Test ALB endpoints
│   └── setup-iam.sh              # Manual IAM setup (if not using Terraform)
│
├── .gitignore
├── .terraform/                   # Ignore Terraform state
├── terraform.tfstate             # IMPORTANT: Add to .gitignore!
└── README.md
```

**README.md phải trả lời được:**

1. **Repo này chứng minh năng lực gì?**
   - Hiểu VPC architecture (multi-AZ, public/private subnets)
   - Cấu hình security groups (principle of least privilege)
   - Setup ALB với path-based routing
   - Quản lý IAM roles & permissions

2. **Cách chạy:**
   ```bash
   cd terraform
   terraform init
   terraform plan
   terraform apply
   
   # Outputs sẽ show: VPC ID, ALB DNS, Security Group IDs
   ```

3. **Kiến trúc:**
   - Vẽ VPC diagram (2 AZs, 4 subnets, IGW, NAT)
   - Giải thích routing: public ↔ IGW, private → NAT
   - Giải thích security groups: ALB → EC2 → RDS/Redis

4. **Chứng minh:**
   - Screenshot: `terraform apply` successful
   - Screenshot: ALB DNS trong AWS Console
   - Screenshot: Security Group rules
   - Screenshot: IAM role attached to instance profile

**Tiêu chí hoàn thành tuần 2:**
- [ ] Hiểu CIDR notation và subnetting
- [ ] Có thể giải thích khác biệt public/private subnets
- [ ] Biết tại sao cần IGW, NAT, Route Tables
- [ ] Hiểu security groups (inbound/outbound rules)
- [ ] Biết cấu hình ALB với target groups
- [ ] Biết IAM roles cho principle of least privilege
- [ ] Chạy được Terraform để tạo toàn bộ infrastructure
- [ ] Có screenshot chứng minh ở README

**Sau tuần 2 → Tuần 3: Deploy EC2 + RDS!** 🚀

---

## 📋 Quick Reference - AWS Services Tuần 2

| Service | Mục Đích | Output |
|---------|----------|--------|
| **VPC** | Network ảo | VPC ID: vpc-xxxxx |
| **Subnets** | Chia VPC thành zones | 4 subnet IDs |
| **IGW** | Connect public → internet | igw-xxxxx |
| **NAT** | Connect private → internet (one-way) | nat-xxxxx |
| **Route Tables** | Định tuyến traffic | 2 route table IDs |
| **Security Groups** | Firewall | alb-sg, app-sg, db-sg IDs |
| **ALB** | Load balancer | ALB DNS name |
| **Target Groups** | Nhóm EC2s | order-tg, payment-tg |
| **IAM Role** | Permissions cho EC2 | microservices-ec2-role |
