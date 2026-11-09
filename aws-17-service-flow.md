# Sơ đồ luồng và mối quan hệ 17 service AWS trong tuần 2

## 1) Danh sách 17 service/resource

1. VPC
2. CIDR
3. Subnet
4. Route Table
5. Internet Gateway (IGW)
6. NAT Gateway
7. Security Group (SG)
8. NACL
9. IAM User
10. IAM Role
11. IAM Policy
12. EC2
13. Auto Scaling
14. ALB
15. RDS
16. S3
17. CloudWatch

---

## 2) Mô hình quan hệ tổng quát

```text
Internet
   |
   v
IGW
   |
   v
VPC (10.0.0.0/16)
   |
   +--> Public Subnet (10.0.1.0/24)
   |       |
   |       +--> ALB
   |       |
   |       +--> EC2 (app/public)
   |
   +--> Private Subnet (10.0.10.0/24)
           |
           +--> EC2 (app/private)
           +--> RDS (private subnet)

Route Table quyết định traffic đi qua IGW hoặc NAT
NACL kiểm soát traffic vào/ra subnet
SG kiểm soát traffic vào/ra resource cụ thể
IAM Role + IAM Policy cấp quyền cho EC2 / Lambda / service
Auto Scaling tăng giảm số EC2 instance theo traffic
CloudWatch giám sát metrics / log / alarm
S3 lưu artifact, backup, static assets
```

---

## 3) Luồng request đi từ Internet vào ứng dụng

```text
Browser / User
   |
   v
Internet
   |
   v
IGW
   |
   v
VPC
   |
   v
Route Table
   |
   +--> Public Subnet
   |
   v
ALB
   |
   +--> SG (allow 80/443 from Internet)
   |
   v
Target Group
   |
   v
EC2 instance (app)
   |
   +--> SG (allow 8080 only from ALB)
   |
   v
RDS (private subnet)
   |
   +--> SG (allow 5432 only from app SG)
```

### Ý nghĩa từng lớp trong luồng:

- IGW: mở cửa nối VPC với Internet
- VPC: mạng lớn chứa mọi resource
- Route Table: chỉ đường cho traffic
- Public Subnet: nơi đặt ALB / public app
- ALB: điểm vào ứng dụng, phân phối request
- SG: firewall của ALB / EC2 / RDS
- EC2: chạy Spring Boot app
- RDS: database chạy riêng trong private subnet

---

## 4) Luồng outbound từ private subnet ra Internet

```text
EC2 trong Private Subnet
   |
   +--> Route Table: 0.0.0.0/0 -> NAT Gateway
   |
   v
NAT Gateway (đặt trong Public Subnet)
   |
   v
IGW
   |
   v
Internet
```

### Ý nghĩa:

- EC2 private subnet không public IP
- nó không thể bị Internet truy cập trực tiếp
- nhưng vẫn có thể đi ra ngoài Internet thông qua NAT Gateway
- NAT Gateway phục vụ outbound connectivity, không phục vụ inbound từ Internet

---

## 5) Mối quan hệ của 17 service/resource theo nhóm

### Nhóm A — Network foundation

```text
CIDR --> định nghĩa không gian IP cho VPC / subnet
VPC --> “mạng lớn” chứa mọi resource
Subnet --> “phòng con” nằm trong VPC
Route Table --> quy định packet đi đâu
IGW --> kết nối VPC với Internet
NAT Gateway --> cho private subnet đi ra Internet
NACL --> firewall ở subnet
SG --> firewall ở resource
```

### Nhóm B — Identity / authorization

```text
IAM User --> người hoặc client login vào AWS
IAM Group --> gom nhiều User
IAM Role --> quyền tạm thời cho resource/service
IAM Policy --> mô tả quyền cụ thể

IAM Policy attach vào IAM Role / User / Group
IAM Role attach vào EC2 / Lambda / EKS / service
```

### Nhóm C — Compute / app delivery

```text
EC2 --> chạy app / backend service
Auto Scaling --> tăng giảm số EC2 theo traffic
ALB --> đầu vào, distribute traffic, health check
```

### Nhóm D — Data / storage / monitoring

```text
RDS --> database managed, chạy trong private subnet
S3 --> lưu artifact, file, backup, static content
CloudWatch --> giám sát metrics/log/alarm
```

---

## 6) Mối quan hệ “hệ sinh thái” giữa 17 service

```text
User / Browser
   |
   v
IGW
   |
   v
VPC
   |
   +--> Route Table
   |         |
   |         +--> Public Subnet
   |         |      |
   |         |      +--> ALB
   |         |      |      |
   |         |      |      +--> SG
   |         |      |
   |         |      +--> EC2 (public app)
   |         |
   |         +--> Private Subnet
   |                |
   |                +--> EC2 (private app)
   |                +--> RDS
   |                +--> NAT Gateway
   |
   +--> IAM Role + IAM Policy
   |         |
   |         +--> gắn vào EC2 / service
   |
   +--> CloudWatch
          |
          +--> metrics/log/alarm

S3
  |
  +--> lưu artifact / backup / static asset
  +--> EC2 hoặc CI/CD pipeline có thể đọc/ghi qua IAM Role
```

---

## 7) Cách nhớ nhanh

- VPC = mạng lớn
- CIDR = cách nói kích thước / phạm vi IP
- Subnet = mảnh trong VPC
- Route Table = quy tắc đường đi
- IGW = cửa ra / vào Internet cho VPC
- NAT = cửa ra cho private subnet
- NACL = firewall ở subnet
- SG = firewall ở resource
- IAM User / Role / Policy = quyền truy cập
- EC2 = máy chủ chạy app
- Auto Scaling = tự thay đổi số lượng EC2
- ALB = đầu vào app, phân phối traffic
- RDS = DB managed
- S3 = storage object
- CloudWatch = monitoring

---

## 8) Gợi ý bản vẽ ngắn để nhớ trong phỏng vấn

```text
Internet
   |
   v
ALB (public subnet)
   |
   v
EC2 (private/public subnet)
   |
   v
RDS (private subnet)
   |
   v
S3 / CloudWatch / IAM Role
```

Với thêm các lớp kiểm soát:

```text
Route Table -> xác định đường đi
NACL -> kiểm soát subnet
SG -> kiểm soát resource
IAM Policy -> cấp quyền
```
