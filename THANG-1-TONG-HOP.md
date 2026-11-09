# Tháng 1 Tổng Hợp — AWS Foundation (4 Weeks)

## 🎯 Mục Tiêu Tháng 1

**Mục đích chính:** Deploy Spring Boot microservices từ local Docker lên AWS cloud, có sẵn sàng cho production.

**Trước tháng 1:**
- Có 2 Spring Boot services (order-service, payment-service)
- Có docker-compose.yml chạy local

**Sau tháng 1:**
- Cả 2 services chạy trên AWS (EC2 instances)
- Database managed bởi AWS RDS (multi-AZ, auto backup)
- File storage trên AWS S3
- Monitoring via CloudWatch
- 4 GitHub repos (portfolio)

---

## 📅 Chi Tiết 4 Tuần

### **Tuần 1: Docker Fundamentals (Dockerize)**
**File:** `TUAN-1-CHI-TIET.md`

**Chủ đề:**
1. Docker Multi-stage Build (80% size reduction)
2. Docker Network + Volume (DNS, persistence)
3. Docker Compose multi-service (5 containers)
4. Proof: Data persists, containers communicate via DNS

**Output:**
- Docker images cho order-service, payment-service
- `docker-compose.yml` (order-db, payment-db, redis, 2 services)
- Init SQL scripts
- All tests passed locally

**Portfolio Repo:** `springboot-docker`

**Timeline:** 5-7 days

---

### **Tuần 2: AWS Networking (VPC, ALB, Security Groups)**
**File:** `TUAN-2-CHI-TIET.md`

**Chủ đề:**
1. VPC Architecture (CIDR, subnets, multi-AZ)
2. Internet Gateway + NAT Gateway (routing)
3. Security Groups (firewall, principle of least privilege)
4. Application Load Balancer (path-based routing)
5. IAM Roles (EC2 permissions)

**Output:**
- 1 VPC (10.0.0.0/16)
- 4 subnets (2 public, 2 private, 2 AZs)
- 1 ALB (public subnets) → route /api/orders, /api/payments
- 3 security groups (ALB, EC2 app, databases)
- IAM role for EC2 instances
- Route tables (public → IGW, private → NAT)

**Infrastructure Proof:**
- EC2 instance trong private subnet ping ra internet (via NAT) ✅
- ALB accessible từ internet ✅
- EC2 NOT directly accessible từ internet (via security group) ✅

**Portfolio Repo:** `springboot-aws-networking` (Terraform)

**Timeline:** 5-7 days

---

### **Tuần 3: AWS Compute & Database (EC2, RDS, ElastiCache)**
**File:** `TUAN-3-CHI-TIET.md`

**Chủ đề:**
1. EC2 Instances & Auto Scaling Groups (stateless deployment)
2. Launch Templates (config for instances)
3. RDS PostgreSQL (managed database, multi-AZ)
4. Database users & security (order_user, payment_user)
5. ElastiCache Redis (managed cache)
6. Connection pooling & SSL/TLS

**Output:**
- 2 Auto Scaling Groups (order-service 2x instances, payment-service 2x instances)
- Instance types: t3.medium (order), t3.large (payment)
- 2 RDS instances (order-db, payment-db)
  - Multi-AZ enabled
  - Encryption enabled
  - Auto backup enabled
  - Users: order_user, payment_user (least privilege)
- 1 ElastiCache cluster (cache.t3.micro, 2 nodes, multi-AZ)
- User data script (install Docker, pull from ECR, run containers)

**Application Proof:**
- EC2 instances healthy (showing in ALB target groups) ✅
- RDS multi-AZ failover tested ✅
- Data persisted in RDS (not in EC2) ✅
- Spring Boot apps connected tới RDS + ElastiCache ✅

**Portfolio Repo:** `springboot-aws-compute-database` (Terraform)

**Timeline:** 5-7 days

---

### **Tuần 4: S3, ECR & Production Deployment**
**File:** `TUAN-4-CHI-TIET.md`

**Chủ đề:**
1. ECR (Elastic Container Registry) - private Docker registry
2. S3 (Simple Storage Service) - object storage
3. CloudWatch (monitoring, logging, alarms)
4. Secrets Manager (manage sensitive data)
5. End-to-end production deployment
6. Smoke tests + rollback procedures

**Output:**
- 2 ECR repositories (order-service, payment-service)
- Docker images pushed to ECR (tag: v1.0.0)
- 1 S3 bucket (microservices-prod)
  - Private access (EC2 via IAM role)
  - Versioning enabled
  - Encryption at rest enabled
- CloudWatch setup:
  - Log groups (/aws/ec2/order-service, /aws/ec2/payment-service)
  - Metrics (CPU, memory, requests, latency)
  - Alarms (high CPU, unhealthy targets, errors)
  - Dashboard (visualize all metrics)
- Secrets Manager (DB passwords, API keys)
- Deployment scripts (build-push, deploy, smoke-test, rollback)
- Runbook (incident response)

**Production Proof:**
- Docker image push to ECR ✅
- EC2 pull image từ ECR ✅
- S3 upload/download from EC2 ✅
- CloudWatch logs collected ✅
- End-to-end test: User → ALB → EC2 → RDS + S3 ✅
- Deployment with zero downtime ✅
- Rollback successful ✅

**Portfolio Repo:** `springboot-aws-production`

**Timeline:** 5-7 days

---

## 🏗️ Final Architecture (End of Month 1)

```
┌─────────────────────────────────────────────────────────────┐
│                    INTERNET (Users)                         │
└────────────────────────────┬────────────────────────────────┘
                             │ HTTP (port 80, 443)
                             ↓
        ┌────────────────────────────────────────┐
        │   AWS Region (us-east-1)               │
        │                                        │
        │  ┌──────────────────────────────────┐  │
        │  │ VPC (10.0.0.0/16)                │  │
        │  │                                  │  │
        │  │ ┌─ AZ 1a ──────────────────────┐│  │
        │  │ │ Public Subnet                 ││  │
        │  │ │ ┌─────────────────────────┐  ││  │
        │  │ │ │      ALB                │  ││  │
        │  │ │ │ Path-based routing      │  ││  │
        │  │ │ │ /api/orders → order    │  ││  │
        │  │ │ │ /api/payments → pay    │  ││  │
        │  │ │ └─────────────────────────┘  ││  │
        │  │ │ IGW                          ││  │
        │  │ └───────────────────────────────┘│  │
        │  │                                  │  │
        │  │ │ Private Subnet                 │  │
        │  │ │ ┌─────────────────────────┐  │  │
        │  │ │ │ order-service (EC2 #1)  │  │  │
        │  │ │ │ port 8080               │  │  │
        │  │ │ └─────────────────────────┘  │  │
        │  │ │ ┌─────────────────────────┐  │  │
        │  │ │ │payment-service (EC2 #3) │  │  │
        │  │ │ │ port 8081               │  │  │
        │  │ │ └─────────────────────────┘  │  │
        │  │ └───────────────────────────────┘  │
        │  │                                  │  │
        │  │ ┌─ AZ 1b ──────────────────────┐│  │
        │  │ │ Public Subnet                 ││  │
        │  │ │ ALB Node 2                    ││  │
        │  │ │ NAT Gateway                   ││  │
        │  │ └───────────────────────────────┘│  │
        │  │                                  │  │
        │  │ │ Private Subnet                 │  │
        │  │ │ ┌─────────────────────────┐  │  │
        │  │ │ │order-service (EC2 #2)   │  │  │
        │  │ │ │port 8080                │  │  │
        │  │ │ └─────────────────────────┘  │  │
        │  │ │ ┌─────────────────────────┐  │  │
        │  │ │ │payment-service (EC2 #4) │  │  │
        │  │ │ │port 8081                │  │  │
        │  │ │ └─────────────────────────┘  │  │
        │  │ └───────────────────────────────┘  │
        │  └──────────────────────────────────┘  │
        │                                        │
        │  ┌──────────────────────────────────┐  │
        │  │ RDS (Managed Database)           │  │
        │  │ ├─ order-db (Multi-AZ)          │  │
        │  │ │  ├─ Primary (1a)              │  │
        │  │ │  ├─ Standby (1b)              │  │
        │  │ │  └─ User: order_user          │  │
        │  │ ├─ payment-db (Multi-AZ)        │  │
        │  │ │  ├─ Primary (1a)              │  │
        │  │ │  ├─ Standby (1b)              │  │
        │  │ │  └─ User: payment_user        │  │
        │  │ └─ Automatic backups            │  │
        │  └──────────────────────────────────┘  │
        │                                        │
        │  ┌──────────────────────────────────┐  │
        │  │ ElastiCache (Redis)              │  │
        │  │ ├─ Node 1 (1a)                   │  │
        │  │ ├─ Node 2 (1b)                   │  │
        │  │ └─ Multi-AZ failover enabled     │  │
        │  └──────────────────────────────────┘  │
        │                                        │
        └────────────────────────────────────────┘

AWS Services (Outside VPC):
  ├─ S3 (microservices-prod bucket)
  ├─ ECR (order-service, payment-service repos)
  ├─ CloudWatch (Logs, Metrics, Alarms, Dashboard)
  ├─ Secrets Manager (DB passwords, API keys)
  └─ IAM (Roles & Policies)
```

---

## 📊 Week-by-Week Progression

```
Week 1: Local Development
  └─ docker-compose up -d
  └─ 5 containers running locally
  └─ curl localhost:8080/api/orders ✅

Week 2: Networking Infrastructure
  └─ terraform apply (VPC, subnets, ALB, security groups)
  └─ Infrastructure ready (no services yet)
  └─ Test: EC2 can ping internet ✅

Week 3: Compute & Database
  └─ terraform apply (EC2, RDS, ElastiCache)
  └─ Services deployed
  └─ Test: curl ALB-DNS/api/orders → response from EC2 ✅

Week 4: Storage & Monitoring
  └─ Push images to ECR
  └─ Setup S3, CloudWatch, deployment scripts
  └─ Full end-to-end testing
  └─ Production ready! ✅
```

---

## 💰 AWS Cost Estimation (Month 1)

```
Rough monthly costs (us-east-1):

EC2 (4x t3.medium + t3.large instances):
  └─ ~$60/month (On-Demand)

RDS (2x db.t3.small, Multi-AZ):
  └─ ~$100/month (Multi-AZ = 2x cost)

ElastiCache (2x cache.t3.micro):
  └─ ~$20/month

S3:
  └─ ~$1/month (depends on usage)

ALB:
  └─ ~$15/month + $0.006 per request

CloudWatch:
  └─ ~$10/month (logs + metrics)

NAT Gateway:
  └─ ~$32/month + ~$0.045 per GB

Internet Gateway:
  └─ Free (data transfer costs apply)

───────────────────────────────
TOTAL: ~$240-300/month (for development/staging)
```

**Cost Optimization Tips:**
- Use t3 instances (burstable, cheaper than m5)
- RDS Multi-AZ is expensive → use for prod only (dev: Single-AZ)
- S3 has free tier (5GB storage free)
- Use reserved instances for longer-term (33% discount)
- Use spot instances for non-critical workloads (70% discount)

---

## 🔄 Portfolio Growth

| Week | Portfolio Repo | What It Shows |
|------|---|---|
| **1** | `springboot-docker` | Docker fundamentals, docker-compose, local microservices |
| **2** | `springboot-aws-networking` | AWS VPC, networking, ALB, security (Terraform) |
| **3** | `springboot-aws-compute-database` | AWS EC2, RDS, ElastiCache, auto-scaling (Terraform) |
| **4** | `springboot-aws-production` | S3, ECR, monitoring, deployment scripts, runbooks |

**By end of Month 1:**
- 4 GitHub repositories
- ~50+ Terraform files
- ~20+ deployment/test scripts
- Complete documentation (README, runbooks, diagrams)
- Production-ready microservices architecture

**Resume bullet points:**
- ✅ Architected and deployed 2 Spring Boot microservices on AWS
- ✅ Implemented Infrastructure as Code using Terraform
- ✅ Designed multi-AZ, high-availability architecture
- ✅ Setup automated deployment pipelines with zero downtime
- ✅ Implemented monitoring and alerting (CloudWatch)
- ✅ Managed secrets and security using AWS best practices

---

## ✅ Self-Assessment Checklist

### **Week 1 Completion:**
- [ ] Docker images build successfully (multi-stage)
- [ ] `docker-compose up -d` runs 5 containers
- [ ] Can curl localhost:8080/api/orders, get response
- [ ] Data persists when restart container
- [ ] DNS resolution works (order-service ping order-db)
- [ ] README has architecture diagrams + proof screenshots

### **Week 2 Completion:**
- [ ] VPC created (2 AZs, 4 subnets)
- [ ] ALB created, health checks passing
- [ ] Security groups configured correctly
- [ ] IAM roles created with least privilege
- [ ] `terraform apply` runs without errors
- [ ] README has networking diagrams + ALB DNS

### **Week 3 Completion:**
- [ ] EC2 instances launch from Launch Template
- [ ] Auto Scaling Group has 2 desired instances
- [ ] RDS instances created (Multi-AZ)
- [ ] ElastiCache cluster running
- [ ] Spring Boot apps connect to RDS + ElastiCache
- [ ] CloudWatch logs show app output
- [ ] README has deployment diagrams

### **Week 4 Completion:**
- [ ] Docker images pushed to ECR
- [ ] S3 bucket created and accessible
- [ ] CloudWatch logs aggregated
- [ ] Alarms configured and tested
- [ ] Smoke test script passes
- [ ] Deployment script works (no downtime)
- [ ] Rollback tested and working
- [ ] README has runbook + procedures

---

## 🚀 Next: Tháng 2 (Terraform + Kubernetes)

After completing Month 1, you're ready for:

**Tháng 2: Infrastructure as Code + Container Orchestration**
- **Week 5:** Terraform advanced (modules, state management)
- **Week 6:** Kubernetes local (minikube)
- **Week 7:** Deploy microservices to Kubernetes
- **Week 8:** Helm charts (package management)

**Result:** 4 microservices running on Kubernetes with automated deployments

---

## 📚 Learning Resources

**AWS Services (Months 1-2):**
- [AWS Networking Guide](https://docs.aws.amazon.com/vpc/)
- [EC2 User Guide](https://docs.aws.amazon.com/ec2/)
- [RDS User Guide](https://docs.aws.amazon.com/rds/)
- [S3 User Guide](https://docs.aws.amazon.com/s3/)

**Docker & Kubernetes (Months 1-3):**
- [Docker Official Docs](https://docs.docker.com/)
- [Kubernetes Official Docs](https://kubernetes.io/docs/)

**Terraform (Months 2-4):**
- [Terraform AWS Provider](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)
- [Terraform Best Practices](https://www.terraform.io/docs/cloud/guides/recommended-practices.html)

---

**Congratulations on completing Month 1! You're now a Cloud Platform Engineer!** 🎉

Next stop: **Kubernetes & Infrastructure as Code** 🚀
