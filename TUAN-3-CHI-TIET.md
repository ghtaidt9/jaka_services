# Tuần 3 chi tiết — AWS Compute & Database: EC2 + RDS

## Bối Cảnh & Mục Tiêu

**Tuần 1-2 đã hoàn thành:**
- ✅ Docker local: 5 containers chạy với docker-compose
- ✅ AWS Networking: VPC, subnets, ALB, security groups ready

**Tuần 3 mục tiêu:**
- Deploy Spring Boot microservices lên EC2 (compute)
- Replace local Postgres với AWS RDS (managed database)
- Replace local Redis với ElastiCache (managed cache)
- Kết nối EC2 → ALB → Internet
- Database-per-service vẫn giữ nguyên (order-db riêng, payment-db riêng)

---

## Tại Sao Cần Tuần 3?

```
Local Development (Tuần 1-2):
  Docker Container → Data lưu trong container
  Mất container = mất data ❌

Production (Tuần 3):
  EC2 (stateless) → RDS (managed database, automatic backup)
  EC2 down không vấn đề, RDS data vẫn safe ✅
  
EC2 = tính toán, request processing
RDS = storage durable, backup tự động, multi-AZ failover
```

---

## Chủ Đề 1: EC2 Instances & Auto Scaling Groups

### Nội Dung Phải Học

- **EC2 là gì**: Elastic Compute Cloud — máy ảo (instance) mà bạn control hoàn toàn
- **Instance Types**: 
  - **t3.medium** (burstable, cost-effective) ← dùng cho dev/staging
  - **t3.large** (burstable, 2 CPU) ← dùng cho payment-service (CPU intensive)
  - **m5.large** (general purpose) ← dùng cho order-service
- **AMI (Amazon Machine Image)**: Template để launch instance
  - **Ubuntu 22.04 LTS**: minimal, dùng để install Docker
  - Hoặc **Custom AMI**: có Docker sẵn (tuần 4)
- **User Data**: Script chạy lúc instance launch (install Docker, pull images, etc.)
- **EBS Volumes**: Storage cho instance (gpt3 SSD recommended)
- **Security Groups**: Firewall cho instance (inbound/outbound rules)
- **Key Pairs**: SSH key để access instance
- **Auto Scaling Groups (ASG)**: Tự động scale instances dựa trên CPU/memory
  - Desired count: 2 instances (1 per AZ)
  - Min count: 1 (không bao giờ < 1)
  - Max count: 4 (scale up tối đa 4 nếu heavy load)

### Cấu Trúc Tiêu Chuẩn

```
AWS Region (us-east-1)
  ├─ VPC (tuần 2)
  │   ├─ AZ 1a
  │   │   ├─ Private Subnet
  │   │   │   └─ EC2-1 (order-service)
  │   │   │   └─ EC2-3 (payment-service)
  │   │   ├─ Public Subnet
  │   │   │   └─ ALB Node 1
  │   │   └─ NAT Gateway
  │   │
  │   └─ AZ 1b
  │       ├─ Private Subnet
  │       │   └─ EC2-2 (order-service)
  │       │   └─ EC2-4 (payment-service)
  │       ├─ Public Subnet
  │       │   └─ ALB Node 2
  │       └─ NAT Gateway
  │
  ├─ Auto Scaling Group: order-service
  │   ├─ Launch Template: t3.medium, ubuntu 22.04
  │   ├─ Desired Count: 2
  │   └─ Targets ALB target group: order-tg
  │
  └─ Auto Scaling Group: payment-service
      ├─ Launch Template: t3.large, ubuntu 22.04
      ├─ Desired Count: 2
      └─ Targets ALB target group: payment-tg
```

**User Data Script** (runs on instance launch):
```bash
#!/bin/bash
set -e

# Update system
apt-get update
apt-get install -y docker.io

# Start Docker
systemctl start docker
systemctl enable docker

# Add ubuntu user to docker group
usermod -aG docker ubuntu

# Pull pre-built images from ECR (tuần 4)
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789.dkr.ecr.us-east-1.amazonaws.com

docker pull 123456789.dkr.ecr.us-east-1.amazonaws.com/order-service:v1
docker pull 123456789.dkr.ecr.us-east-1.amazonaws.com/payment-service:v1

# Run containers with environment from Systems Manager Parameter Store
ORDER_DB_URL=$(aws ssm get-parameter --name /prod/order-db-url --query 'Parameter.Value' --output text)
ORDER_DB_PASS=$(aws ssm get-parameter --name /prod/order-db-password --with-decryption --query 'Parameter.Value' --output text)

docker run -d \
  --name order-service \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="$ORDER_DB_URL" \
  -e SPRING_DATASOURCE_PASSWORD="$ORDER_DB_PASS" \
  --restart always \
  123456789.dkr.ecr.us-east-1.amazonaws.com/order-service:v1

# Similar for payment-service...
```

**Output cụ thể:**
- Tạo 2 Launch Templates (order-service, payment-service)
- Tạo 2 Auto Scaling Groups, target 2 instances mỗi cái
- Chứng minh:
  - EC2 instances launched (2 per service)
  - Instances healthy (có heartbeat tới ALB)
  - CloudWatch logs show Docker containers running
- Ghi vào README: ASG configuration, instance types, user data script

---

## Chủ Đề 2: RDS (Relational Database Service)

### Nội Dung Phải Học

- **RDS là gì**: AWS managed PostgreSQL/MySQL/etc — bạn không cần manage DB server
- **Multi-AZ Deployments**: RDS có primary + standby replica trong khác AZ
  - Primary down → automatic failover tới standby (không downtime)
- **Automated Backups**: Daily snapshots, point-in-time restore capability
- **Security**:
  - **Encryption at rest**: Data mã hóa trong disk (KMS key)
  - **Encryption in transit**: SSL/TLS cho connection (RDS endpoint)
- **Parameter Groups**: Configure DB settings (max_connections, timeout, etc.)
- **Database Users**: Create users per service (order_user, payment_user)
  - order_user chỉ có access tới order_db (isolation)
  - payment_user chỉ có access tới payment_db
- **Endpoints**:
  - **Writer endpoint**: order-db.xxxxx.us-east-1.rds.amazonaws.com (read+write)
  - **Reader endpoint** (read-only, tuần 4 khi có replicas)

### Cấu Trúc Tiêu Chuẩn

```
AWS RDS (Managed Database)
  ├─ DB Instance 1: order-db
  │   ├─ Engine: PostgreSQL 15.x
  │   ├─ Instance Class: db.t3.small (burstable, cost-effective)
  │   ├─ Storage: 20GB gpt3 SSD, auto-scaling to 100GB
  │   ├─ Multi-AZ: Enabled (standby in 1b if primary in 1a)
  │   ├─ Username: postgres (root)
  │   ├─ Encryption: Enabled (KMS key)
  │   ├─ Endpoint: order-db.xxxxx.us-east-1.rds.amazonaws.com:5432
  │   ├─ Security Group: db-sg (inbound 5432 from app-sg)
  │   └─ Database: order_db
  │       └─ User: order_user (SELECT, INSERT, UPDATE, DELETE on order_db)
  │
  └─ DB Instance 2: payment-db
      ├─ Engine: PostgreSQL 15.x
      ├─ Instance Class: db.t3.small
      ├─ Storage: 20GB gpt3 SSD
      ├─ Multi-AZ: Enabled
      ├─ Endpoint: payment-db.xxxxx.us-east-1.rds.amazonaws.com:5432
      └─ Database: payment_db
          └─ User: payment_user
```

**Migration từ Docker tới RDS:**

```
Before (Tuần 1-2):
  EC2 localhost → Docker Postgres (inside container)
  Data in: /var/lib/postgresql/data (inside container, dễ mất)

After (Tuần 3):
  EC2 → RDS order-db (managed, multi-AZ, auto backup)
  EC2 → RDS payment-db (managed, multi-AZ, auto backup)
  Connection string: jdbc:postgresql://order-db.xxxxx.rds.amazonaws.com:5432/order_db
```

**Output cụ thể:**
- Tạo 2 RDS instances (order-db, payment-db)
- Cấu hình Multi-AZ, encryption, backups
- Tạo database users (order_user, payment_user)
- Chứng minh:
  - EC2 instance có thể connect tới RDS (via security group)
  - Data persisted (delete instance, data still in RDS)
  - Automatic failover works (manual failover test)
  - Backups automated (check backup list in RDS console)
- Ghi vào README: RDS endpoints, connection strings, backup policy

---

## Chủ Đề 3: ElastiCache (Managed Redis)

### Nội Dung Phải Học

- **ElastiCache là gì**: AWS managed Redis/Memcached — dùng để cache
- **Cluster Mode**: 
  - **Disabled**: Single node (simple, nhưng nếu node down → no cache)
  - **Enabled**: Multiple nodes, sharded (high availability, scale reads)
- **Auto Failover**: Node down → failover tới replica (0 downtime)
- **Encryption**:
  - **At rest**: Redis data mã hóa trong disk
  - **In transit**: SSL/TLS cho connection (encryption in transit)
- **Parameter Groups**: Configure Redis settings (maxmemory policy, timeout, etc.)
- **Endpoint**: Primary node address (order-cache.xxxxx.cache.amazonaws.com:6379)

### Cấu Trúc Tiêu Chuẩn

```
AWS ElastiCache (Managed Redis)
  └─ Cache Cluster: order-payment-cache
      ├─ Engine: Redis 7.x
      ├─ Node Type: cache.t3.micro (small, cheap)
      ├─ Number of Nodes: 2 (multi-AZ automatic failover)
      ├─ Cluster Mode: Disabled (simpler for this use case)
      ├─ Encryption at rest: Enabled
      ├─ Encryption in transit: Enabled (TLS)
      ├─ Endpoint: order-payment-cache.xxxxx.cache.amazonaws.com:6379
      ├─ Security Group: cache-sg (inbound 6379 from app-sg)
      └─ Maintenance window: Sun 03:00-04:00 UTC
```

**Connection từ microservices:**

```yaml
# Spring Boot application.yml
spring:
  data:
    redis:
      host: order-payment-cache.xxxxx.cache.amazonaws.com
      port: 6379
      ssl: true  # ElastiCache enable SSL
      timeout: 2000ms
```

**Output cụ thể:**
- Tạo 1 ElastiCache cluster (multi-AZ, encryption enabled)
- Cấu hình security group
- Chứng minh:
  - EC2 instance có thể connect tới ElastiCache
  - Redis commands work (SET, GET, etc.)
  - Cache replication works (data sync giữa nodes)
- Ghi vào README: ElastiCache endpoint, connection string, encryption config

---

## Chủ Đề 4: Database Migration & Connection Testing

### Nội Dung Phải Học

- **Migration Strategy**: 
  - Dữ liệu từ Docker Postgres → AWS RDS (database dump + restore)
  - Hoặc: đơn giản viết init scripts cho RDS
- **Connection Pooling**:
  - HikariCP (Spring Boot default) cấu hình connection pool (minimum, maximum)
  - Tại sao: avoid creating new connection for every query (expensive)
- **SSL/TLS Connection**: RDS require SSL để kết nối từ EC2
- **Performance Testing**: Kiểm tra latency, throughput khi kết nối RDS

### Connection String Examples

```
# Local Docker (Tuần 1-2)
jdbc:postgresql://postgres-order:5432/order_db

# AWS RDS (Tuần 3)
jdbc:postgresql://order-db.c123abc456def.us-east-1.rds.amazonaws.com:5432/order_db?sslmode=require

# AWS RDS với user (best practice)
jdbc:postgresql://order-db.c123abc456def.us-east-1.rds.amazonaws.com:5432/order_db?user=order_user&sslmode=require
```

**Output cụ thể:**
- Migration scripts (init SQL cho order_db, payment_db)
- Test connections từ EC2 (psql client)
- Test từ Spring Boot application (actuator health check)
- Performance benchmark (query latency comparison: local vs RDS)
- Ghi vào README: migration steps, connection pooling config

---

## Portfolio Cuối Tuần — Repo `springboot-aws-compute-database`

Cấu trúc thư mục:
```
springboot-aws-compute-database/
├── terraform/
│   ├── ec2.tf                    # Launch templates, ASG
│   ├── rds.tf                    # RDS instances
│   ├── elasticache.tf            # ElastiCache cluster
│   ├── iam-ec2-role.tf           # EC2 IAM role
│   ├── variables.tf
│   ├── outputs.tf                # Show RDS endpoints
│   └── terraform.tfvars
│
├── scripts/
│   ├── user-data.sh              # EC2 user data script
│   ├── init-order-db.sql         # Init order_db schema + user
│   ├── init-payment-db.sql       # Init payment_db schema + user
│   ├── test-connection.sh        # Test EC2 → RDS connection
│   └── migrate-data.sh           # Migrate từ Docker → RDS
│
├── config/
│   ├── application-prod.yml      # Spring Boot prod config (RDS endpoint)
│   └── application-cache.yml     # Spring Boot cache config (ElastiCache endpoint)
│
├── diagrams/
│   ├── ec2-infrastructure.png    # ASG + EC2 instances
│   ├── rds-multi-az.png          # RDS multi-AZ setup
│   └── overall-architecture.png  # All components together
│
└── README.md
```

**README.md phải trả lời được:**

1. **Repo này chứng minh năng lực gì?**
   - Deploy Spring Boot microservices lên EC2
   - Setup RDS database tại AWS (multi-AZ, encrypted)
   - Configure ElastiCache cho caching
   - Database-per-service architecture

2. **Cách chạy:**
   ```bash
   cd terraform
   terraform apply
   
   # Outputs: EC2 instance IPs, RDS endpoints, ElastiCache endpoint
   ```

3. **Kiến trúc:**
   - 2 Auto Scaling Groups (order-service, payment-service)
   - 2 RDS instances (order-db, payment-db)
   - 1 ElastiCache cluster (shared)
   - Connection pooling & SSL setup

4. **Chứng minh:**
   - Screenshot: EC2 instances healthy in ASG
   - Screenshot: RDS instances in multi-AZ
   - Screenshot: ElastiCache cluster running
   - Log: Spring Boot apps connected tới RDS + ElastiCache
   - Test: Create order → data saved in RDS order_db

**Tiêu chí hoàn thành tuần 3:**
- [ ] Hiểu EC2, Launch Templates, Auto Scaling Groups
- [ ] Biết instance types (t3.medium vs m5.large) khi nào dùng
- [ ] Hiểu RDS multi-AZ và automatic failover
- [ ] Biết cách tạo database users (principle of least privilege)
- [ ] Setup ElastiCache cho caching
- [ ] Chạy được Terraform để deploy EC2 + RDS + ElastiCache
- [ ] Test được connection từ EC2 tới RDS
- [ ] Verify được data persist trong RDS
- [ ] Có screenshot chứng minh ở README

**Sau tuần 3 → Tuần 4: S3 + Full Deployment!** 🚀

---

## 📋 Quick Reference - AWS Services Tuần 3

| Service | Mục Đích | Output |
|---------|----------|--------|
| **EC2** | Compute instances | Instance IDs, Private IPs |
| **Launch Template** | Template để launch EC2 | Template ID |
| **Auto Scaling Group** | Scale instances automatically | ASG name, desired count |
| **RDS PostgreSQL** | Managed database | RDS endpoint, Database name |
| **RDS User** | Database user với limited permissions | username |
| **ElastiCache** | Managed Redis cache | Cache endpoint |
| **CloudWatch Logs** | Log từ EC2 | Log group name |
| **Systems Manager Parameter Store** | Store secrets (DB password) | Parameter names |

---

## 🎯 Key Learnings

1. **Stateless EC2**: Instances có thể down bất kỳ lúc nào (ASG auto-replace)
   → Data KHÔNG nên lưu trong EC2, phải lưu trong RDS/S3

2. **Multi-AZ Redundancy**: Primary RDS down → automatic failover
   → High availability, zero downtime

3. **Security Isolation**: 
   - order_user chỉ access order_db
   - payment_user chỉ access payment_db
   → Prevent data leaks, follow principle of least privilege

4. **Managed Services**: RDS + ElastiCache handle backup, failover, patching
   → Bạn chỉ lo logic, AWS lo infrastructure

5. **Connection Strings Change**:
   - Before: `localhost:5432/order_db`
   - After: `order-db.xxxxx.rds.amazonaws.com:5432/order_db`
   → Update Spring Boot config, no code changes needed
