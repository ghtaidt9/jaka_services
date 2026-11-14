# Lộ trình 6 tháng — Cloud-Native Platform Engineer (Java + AWS + K8s + AI)

> Nén từ 18–24 tháng xuống 6 tháng bằng cách: (1) cắt bớt thời gian ở phần bạn đã có nền (Docker, GitHub Actions, AWS cơ bản), (2) chạy song song các stage bổ trợ nhau (K8s + System Design, GH Actions + Monitoring), (3) học đúng lượng cần để làm được project, không học lan man.
>
> **Điều kiện để làm được trong 6 tháng:** ~30–35h/tuần (gần như full-time). Nếu vừa học vừa đi làm full-time, hãy nhân 1.5–1.7 lần (≈ 9–10 tháng) — timeline này không co giãn được nếu học part-time.

---

## Tháng 1 — AWS nền tảng (bỏ qua Linux/Docker vì đã biết)

**Tuần 1**
- Ôn nhanh Docker: multi-stage build, Docker Compose nhiều service
- Output: dockerize hoàn chỉnh order-service + payment-service của bạn chạy chung compose với Postgres, Redis

**Tuần 2–4**
- VPC, CIDR, Subnet, Route Table, NAT, IGW, IAM (User/Role/Policy), EC2, Auto Scaling, RDS, CloudWatch
- Output: deploy Spring Boot lên AWS theo mô hình Internet → ALB → EC2 → Spring Boot → RDS → S3

**Checkpoint cuối tháng:** tự deploy production trên AWS mà không cần xem lại tài liệu.

---

## Tháng 2 — Terraform + Kubernetes cơ bản

**Tuần 5–6**
- Terraform: Resource, Variable, Output, Module, State, Remote Backend
- Output: dựng lại toàn bộ hạ tầng tháng 1 (VPC, EC2, RDS, IAM, S3) chỉ bằng `terraform apply`

**Tuần 7–8**
- Kubernetes cơ bản: Container Runtime, Pod, ReplicaSet, Deployment, Service, Ingress, ConfigMap, Secret, Persistent Volume
- Output: chạy được order-service + payment-service + Postgres + Redis trên K8s local (minikube/kind)

**Checkpoint:** dựng hạ tầng AWS trong vài phút bằng Terraform; hiểu rõ vòng đời Pod → Deployment → Service.

---

## Tháng 3 — Kubernetes nâng cao // System Design (song song)

**Tuần 9–10**
- Helm, EKS
- Output: deploy toàn bộ 4 service (order/payment/notification/inventory) lên EKS, có Ingress + Gateway

**Tuần 11–12 (song song với tuần 9–10, không tách riêng)**
- System Design: REST vs gRPC, Kafka, Redis caching, Load Balancer, Scalability, CAP, Event-driven, Saga, CQRS
- Áp dụng trực tiếp: thêm Kafka giữa order-service và inventory-service, áp Saga cho luồng đặt hàng thất bại

**Checkpoint:** hệ thống microservice có autoscaling, ingress, và giao tiếp event-driven — đủ để trình bày trong phỏng vấn Senior Backend.

---

## Tháng 4 — DevOps / CI/CD / Monitoring

**Tuần 13–14**
- GitHub Actions → Docker → K8s (bạn đã biết GH Actions, tập trung phần build-test-deploy vào cluster)
- Output: mỗi lần `git push` tự động build, test, đóng gói, deploy lên EKS

**Tuần 15–16**
- ArgoCD (GitOps), Prometheus, Grafana, Loki, Tracing, Alerting
- Output: dashboard giám sát 4 service, alert khi lỗi/latency cao

**Checkpoint:** CI/CD + observability giống môi trường doanh nghiệp thật.

---

## Tháng 5 — AI Engineering

**Tuần 17–20**
- LLM, Embedding, Prompt, Vector DB, RAG, Spring AI, LangGraph, MCP, AI Agent, Tool Calling
- Output: chatbot RAG — Spring Boot → LLM → Vector DB → RAG → chạy trên AWS, tích hợp được với 1 trong 4 service hiện có (ví dụ: hỏi đáp trạng thái đơn hàng)

**Checkpoint:** có 1 project AI chạy production, kết nối được với hệ thống Java hiện tại.

---

## Tháng 6 — AI Infrastructure + hoàn thiện portfolio

**Tuần 21–23**
- GPU, Inference, vLLM, KServe, Ray, Autoscaling, Bedrock/Vertex AI, Security
- Output: deploy nhiều model qua Load Balancer → GPU → Monitoring

**Tuần 24–26 (buffer)**
- Hoàn thiện portfolio, viết README cho từng repo, chuẩn bị demo
- Tùy chọn: ôn AWS Associate nếu muốn có chứng chỉ
- Mock interview cho các vị trí Backend/Cloud/Platform/DevOps/AI Platform Engineer

---

## Bảng tổng hợp

| Tháng | Chủ đề chính | Output bắt buộc |
|---|---|---|
| 1 | AWS Foundation | Deploy Spring Boot lên AWS (ALB→EC2→RDS→S3) |
| 2 | Terraform + K8s cơ bản | Hạ tầng bằng `terraform apply`; service chạy trên K8s local |
| 3 | K8s nâng cao + System Design | 4 microservice trên EKS, có Kafka + Saga |
| 4 | DevOps/CI-CD/Monitoring | Push code → auto build/test/deploy; dashboard giám sát |
| 5 | AI Engineering | Chatbot RAG tích hợp vào hệ thống hiện tại |
| 6 | AI Infrastructure + Portfolio | Multi-model deployment; portfolio hoàn chỉnh |

**Những gì bị cắt so với bản gốc:** thời lượng học lại Linux/Docker/GitHub Actions cơ bản (đã có nền), và tách System Design + K8s thành 2 giai đoạn liên tiếp (giờ chạy song song vì chúng bổ trợ trực tiếp cho nhau).

**Rủi ro lớn nhất của lịch 6 tháng:** tháng 3 và tháng 5 là hai điểm dễ trễ nhất vì lượng khái niệm mới nhiều nhất. Nếu bắt đầu trễ tiến độ, ưu tiên cắt ở tuần buffer (24-26) trước, không cắt ở Kubernetes hay AI Engineering.
