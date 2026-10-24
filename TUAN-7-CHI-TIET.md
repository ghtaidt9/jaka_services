# Tuần 7 chi tiết — Kubernetes Stateful & Traffic: StatefulSet, Gateway/Ingress, Probes, HPA

> Giai đoạn gộp Tháng 2 + 3 (Tuần 5–10). **Không cần AWS** — chạy trên kind.

## Bối Cảnh & Mục Tiêu

**Đã hoàn thành:**
- ✅ Tuần 5: Terraform module + backend + test + CI
- ✅ Tuần 6: kind cluster, Deployment, Service, ConfigMap, Secret cho 2 service

**Vấn đề còn lại từ tuần 6:**
- ❌ Postgres/Redis chạy bằng Deployment → xóa Pod là **mất dữ liệu**
- ❌ Chỉ truy cập được bằng `port-forward` → chưa có "ALB" cho cluster
- ❌ Pod nhận traffic ngay cả khi app chưa khởi động xong
- ❌ Không giới hạn CPU/memory, không tự scale

**Tuần 7 mục tiêu:**
- Postgres + Redis chạy bằng StatefulSet + PersistentVolumeClaim
- Job tạo DB role `order_user`/`payment_user` (thay cho đoạn psql trong user-data tháng 1)
- Routing theo path `/api/orders`, `/api/payments` qua Gateway API (và hiểu Ingress)
- Readiness / liveness / startup probe dùng Spring Boot Actuator
- Requests/limits + HorizontalPodAutoscaler + load test

---

## Tại Sao Cần Tuần 7?

```
Tháng 1 (AWS)                         Tuần 7 (K8s)
─────────────────────────────────     ─────────────────────────────────
RDS (EBS, Multi-AZ)               →   StatefulSet + PVC
User-data psql tạo role           →   Job (chạy 1 lần, idempotent)
ALB + path rule                   →   Gateway + HTTPRoute (hoặc Ingress)
Target group health check         →   readinessProbe
ASG target tracking CPU 50%       →   HPA averageUtilization 50%
Instance type t3.micro            →   resources.requests / limits
```

---

## Chủ Đề 1: StatefulSet & Persistent Volume

### Nội Dung Phải Học

- **PersistentVolume (PV)**: một mảnh storage thật (EBS trên AWS, thư mục local trên kind)
- **PersistentVolumeClaim (PVC)**: Pod "xin" storage, không quan tâm storage cụ thể
- **StorageClass**: cách cấp PV tự động (dynamic provisioning). kind có sẵn `standard` (local-path)
- **Access mode**: `ReadWriteOnce` (1 node) — đủ cho Postgres
- **StatefulSet khác Deployment**:
  - Tên Pod cố định, có thứ tự: `order-db-0`, `order-db-1`
  - Mỗi Pod có PVC riêng qua `volumeClaimTemplates` — Pod chết, PVC **vẫn giữ**
  - Cần **headless Service** để có DNS riêng từng Pod: `order-db-0.order-db.dev.svc.cluster.local`
- **Thực tế production**: DB thường dùng managed service (RDS) hoặc operator (CloudNativePG). Tự chạy DB trên K8s là để **học** StatefulSet

### Chuẩn Bị — Gỡ order-db kiểu tuần 6

Service `order-db` tuần 6 là ClusterIP thường. `clusterIP` là field **immutable** → `kubectl apply` bản headless (`clusterIP: None`) sẽ báo lỗi `field is immutable`. Phải xóa Deployment + Service cũ trước (dữ liệu tuần 6 mất — đó chính là vấn đề tuần này giải quyết):

```powershell
kubectl delete deploy/order-db svc/order-db -n dev

# Secret cho tài khoản admin của Postgres (tuần 6 dùng luôn order_user làm admin — giờ tách ra như RDS tháng 1)
kubectl create secret generic order-db-admin -n dev --from-literal=password=order_admin_pass
```

### Cấu Trúc Tiêu Chuẩn

```
StatefulSet order-db (replicas: 1)
  └─ Pod order-db-0 ──mount──► PVC data-order-db-0 ──bind──► PV (local-path, 1Gi)
Headless Service order-db (clusterIP: None)
  └─ DNS: order-db-0.order-db.dev.svc.cluster.local
```

```yaml
# k8s/order-db/statefulset.yaml
apiVersion: v1
kind: Service
metadata: { name: order-db, namespace: dev }
spec:
  clusterIP: None
  selector: { app: order-db }
  ports: [{ port: 5432 }]
---
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: order-db, namespace: dev }
spec:
  serviceName: order-db
  replicas: 1
  selector:
    matchLabels: { app: order-db }
  template:
    metadata:
      labels: { app: order-db }
    spec:
      containers:
        - name: postgres
          image: postgres:16
          env:
            - { name: POSTGRES_DB, value: orderdb }
            - { name: POSTGRES_USER, value: order_admin }
            - name: POSTGRES_PASSWORD
              valueFrom: { secretKeyRef: { name: order-db-admin, key: password } }
            - { name: PGDATA, value: /var/lib/postgresql/data/pgdata }
          ports: [{ containerPort: 5432 }]
          volumeMounts:
            - { name: data, mountPath: /var/lib/postgresql/data }
          readinessProbe:
            exec: { command: ["pg_isready", "-U", "order_admin", "-d", "orderdb"] }
            periodSeconds: 5
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: standard
        resources: { requests: { storage: 1Gi } }
```

> Từ đây `order_user` **chưa tồn tại** trong DB mới → order-service sẽ `CrashLoopBackOff` cho tới khi chạy Job ở Chủ đề 2. Đó là bình thường.

**Output cụ thể:**
- StatefulSet cho order-db, payment-db (Postgres), order-redis, payment-redis (mỗi service 1 Redis riêng, như tuần 6; Redis không cần Job tạo role)
- Chứng minh:
  - Ghi 1 dòng dữ liệu → `kubectl delete pod order-db-0` → Pod mới lên → dữ liệu **vẫn còn**. Ở Chủ đề 1 app chưa chạy được (chưa có `order_user`, Chủ đề 2 mới tạo) và order-service cũng chưa có API `POST` (tuần 8) → test thẳng bằng `psql` với tài khoản admin, không cần app:

```powershell
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "CREATE TABLE persist_test (note text); INSERT INTO persist_test VALUES ('before-delete');"
kubectl delete pod order-db-0 -n dev
kubectl wait --for=condition=Ready pod/order-db-0 -n dev --timeout=2m
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "SELECT * FROM persist_test;"
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "DROP TABLE persist_test;"
```

  - Không kiểm tra bằng `GET /api/orders`: endpoint này `@Cacheable` → có thể trả từ Redis, không chứng minh được Postgres giữ dữ liệu.
  - `kubectl get pvc,pv -n dev` → PVC `Bound`
  - `kubectl delete statefulset order-db` → PVC vẫn còn (giải thích vì sao đây là thiết kế cố ý)

---

## Chủ Đề 2: Job — Tạo DB Role Least-Privilege

### Nội Dung Phải Học

- **Job**: chạy Pod đến khi thành công rồi dừng (khác Deployment chạy mãi)
- `backoffLimit`, `restartPolicy: OnFailure`, `ttlSecondsAfterFinished`
- **CronJob**: Job theo lịch (backup DB, dọn dữ liệu)
- **initContainer**: chạy trước container chính trong cùng Pod — ví dụ đợi DB sẵn sàng
- Tái sử dụng đúng logic SQL idempotent từ `service-bootstrap.sh.tpl` tháng 1 (`\gset` + `\if`)

### Cấu Trúc Tiêu Chuẩn

```yaml
# k8s/order-db/create-app-role-job.yaml
apiVersion: batch/v1
kind: Job
metadata: { name: order-db-create-app-role, namespace: dev }
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
            - name: PGPASSWORD
              valueFrom: { secretKeyRef: { name: order-db-admin, key: password } }
            - name: APP_PASS
              valueFrom: { secretKeyRef: { name: order-service-secret, key: SPRING_DATASOURCE_PASSWORD } }
          command: ["sh", "-c"]
          args:
            - |
              psql -h order-db -U order_admin -d orderdb -v ON_ERROR_STOP=1 -v app_pass="$APP_PASS" <<'SQL'
              SELECT EXISTS (SELECT FROM pg_roles WHERE rolname = 'order_user') AS role_exists \gset
              \if :role_exists
              ALTER ROLE order_user WITH PASSWORD :'app_pass';
              \else
              CREATE ROLE order_user LOGIN PASSWORD :'app_pass';
              \endif
              GRANT CREATE, USAGE ON SCHEMA public TO order_user;
              SQL
```

**Output cụ thể:**
- Job cho 2 DB; app đăng nhập bằng `*_user`, không dùng admin
- Chứng minh:
  - `kubectl get jobs -n dev` → `COMPLETIONS 1/1`
  - Chạy lại Job lần 2 → vẫn thành công (idempotent). Job **không chạy lại bằng `apply`** (spec Pod của Job là immutable, và `ttlSecondsAfterFinished: 300` tự xóa Job sau 5 phút) → xóa rồi tạo lại:

```powershell
kubectl delete job order-db-create-app-role -n dev --ignore-not-found
kubectl apply -f k8s/order-db/create-app-role-job.yaml
kubectl wait --for=condition=Complete job/order-db-create-app-role -n dev --timeout=2m
kubectl rollout restart deploy/order-service -n dev     # app đang CrashLoop vì chưa có role → khởi động lại
```
  - `\du` trong psql thấy `order_user` không có quyền superuser

---

## Chủ Đề 3: Probes — Health Check Đúng Cách

### Nội Dung Phải Học

- **readinessProbe**: fail → Pod bị **gỡ khỏi Service** (không nhận traffic), không bị restart. ≈ target group health check
- **livenessProbe**: fail → **kubelet restart container**. Dùng cho trạng thái treo (deadlock)
- **startupProbe**: tắt liveness trong lúc app khởi động chậm (Spring Boot + Hibernate `ddl-auto: update` có thể mất 20–40s)
- **Spring Boot Actuator health groups**: bật `management.endpoint.health.probes.enabled=true` → có `/actuator/health/readiness` và `/actuator/health/liveness`
- **Sai lầm hay gặp**: liveness check cả DB → DB chậm → mọi Pod bị restart cùng lúc (cascading failure). Liveness chỉ nên check bản thân app

### Cấu Trúc Tiêu Chuẩn

```yaml
# thêm vào container order-service (gộp chung 1 khối env với JAVA_TOOL_OPTIONS ở Chủ đề 5 — YAML không cho 2 key env)
env:
  - { name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED, value: "true" }
  # Mặc định group readiness CHỈ có readinessState, không check DB.
  # Thêm db để Pod tự rời Service khi DB chết (readiness được phép check phụ thuộc; liveness thì KHÔNG)
  - { name: MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE, value: "readinessState,db" }
startupProbe:
  httpGet: { path: /api/orders/actuator/health/liveness, port: 8081 }
  periodSeconds: 5
  failureThreshold: 24          # tối đa 120s để khởi động
readinessProbe:
  httpGet: { path: /api/orders/actuator/health/readiness, port: 8081 }
  periodSeconds: 5
livenessProbe:
  httpGet: { path: /api/orders/actuator/health/liveness, port: 8081 }
  periodSeconds: 10
  failureThreshold: 3
```

**Output cụ thể:**
- 3 probe cho cả 2 service
- Chứng minh:
  - Scale order-db về 0 (`kubectl scale statefulset order-db -n dev --replicas=0`) → Pod order-service chuyển `0/1 Ready` nhưng **không restart** (nhờ env `..._READINESS_INCLUDE` có `db` ở trên)
  - Rolling update không có request lỗi (chạy vòng `curl.exe` liên tục trong lúc update). Nếu vẫn lỗi vài request lúc Pod cũ tắt: thêm `lifecycle: { preStop: { sleep: { seconds: 5 } } }` vào container để kube-proxy kịp gỡ Pod khỏi Service trước khi app dừng
- Ghi vào README: bảng readiness vs liveness vs startup

---

## Chủ Đề 4: Gateway API & Ingress — "ALB" Cho Cluster

### Nội Dung Phải Học

- **Ingress**: resource cũ, định nghĩa rule host/path → Service. Cần 1 **Ingress Controller** chạy thật
  - Lưu ý: dự án **ingress-nginx đã ngừng bảo trì (retired) từ 3/2026** — vẫn học khái niệm Ingress vì gặp nhiều trong hệ thống cũ, nhưng dự án mới nên dùng Gateway API
- **Gateway API** (chuẩn mới, roadmap ghi "Ingress + Gateway"):
  - `GatewayClass`: loại controller (Envoy Gateway, NGINX Gateway Fabric, Istio...)
  - `Gateway`: listener (port 80/443) — do team platform quản lý
  - `HTTPRoute`: rule path → Service — do team app quản lý
- **Trên AWS thật**: AWS Load Balancer Controller biến Ingress/Gateway thành ALB thật (tuần 10 nói thêm)
- **TLS termination**: cert gắn ở Gateway (biết khái niệm, cert-manager học sau)

### Cấu Trúc Tiêu Chuẩn

```
Laptop :8090 ──(kubectl port-forward)──► Envoy (Gateway "web") :80
                                           ├─ /api/orders*   ──► Service order-service:8081
                                           └─ /api/payments* ──► Service payment-service:8082
```

> ⚠️ **Không port-forward vào `8080`**: cổng 8080 trên laptop đã bị Docker giữ cho `extraPortMappings` (8080 → 30080) trong `kind-config.yaml` → `port-forward ... 8080:80` báo `address already in use`. Dùng cổng khác (ví dụ `8090`). Muốn dùng đúng mapping 8080 → 30080 thì phải cấu hình Service của Envoy thành `NodePort` với `nodePort: 30080` qua CRD `EnvoyProxy` — nâng cao, làm sau khi port-forward đã chạy.

```powershell
# Cài Gateway API CRDs + Envoy Gateway (thay <version> bằng bản mới nhất trên gateway.envoyproxy.io)
helm install eg oci://docker.io/envoyproxy/gateway-helm --version <version> -n envoy-gateway-system --create-namespace
kubectl wait --for=condition=Available deploy/envoy-gateway -n envoy-gateway-system --timeout=5m
```

```yaml
# k8s/gateway/gateway.yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata: { name: eg }
spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: web, namespace: dev }
spec:
  gatewayClassName: eg
  listeners:
    - { name: http, protocol: HTTP, port: 80 }
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: api, namespace: dev }
spec:
  parentRefs: [{ name: web }]
  rules:
    - matches: [{ path: { type: PathPrefix, value: /api/orders } }]
      backendRefs: [{ name: order-service, port: 8081 }]
    - matches: [{ path: { type: PathPrefix, value: /api/payments } }]
      backendRefs: [{ name: payment-service, port: 8082 }]
```

```powershell
kubectl apply -f k8s/gateway/gateway.yaml
kubectl wait --for=condition=Accepted gateway/web -n dev --timeout=2m
# KHÔNG wait "Programmed" trên kind: Envoy tạo Service type LoadBalancer, kind không có cloud cấp IP
# → EXTERNAL-IP <pending> → Programmed=False (AddressNotAssigned) mãi. Đợi Pod Envoy Ready là đủ để port-forward:
kubectl wait --for=condition=Ready pod -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web --timeout=2m
kubectl get gateway,httproute -n dev
# Envoy tạo Service type LoadBalancer; trên kind dùng port-forward (hoặc cloud-provider-kind)
$svc = kubectl get svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web -o name
# --address 0.0.0.0 để container k6 (Chủ đề 5) gọi được qua host.docker.internal
kubectl port-forward -n envoy-gateway-system $svc 8090:80 --address 0.0.0.0

# Terminal khác. Dùng curl.exe — trong Windows PowerShell, "curl" là alias của Invoke-WebRequest
curl.exe http://localhost:8090/api/orders/actuator/health
curl.exe http://localhost:8090/api/payments/actuator/health
```

**Output cụ thể:**
- 1 Gateway + 1 HTTPRoute routing 2 path
- Viết thêm 1 file `ingress.yaml` tương đương (chỉ để so sánh cú pháp, không cần chạy)
- Chứng minh: `curl` 2 path qua cùng 1 cổng đều trả `UP`
- Ghi vào README: so sánh ALB listener rule ↔ HTTPRoute ↔ Ingress

---

## Chủ Đề 5: Resources & HorizontalPodAutoscaler

### Nội Dung Phải Học

- **requests**: lượng CPU/memory scheduler "giữ chỗ" → quyết định Pod đặt ở node nào
- **limits**: trần. Vượt memory limit → **OOMKilled**; vượt CPU limit → bị throttle (chậm, không chết)
- **QoS class**: Guaranteed / Burstable / BestEffort — ảnh hưởng thứ tự bị evict
- **JVM trong container**: JVM hiện đại tự đọc memory limit; dùng `-XX:MaxRAMPercentage=75` thay vì `-Xmx` cứng
- **metrics-server**: cung cấp CPU/memory cho `kubectl top` và HPA
- **HPA**: `desiredReplicas = ceil(currentReplicas × currentUtilization / target)`. Utilization tính theo **requests**, nên phải có requests
- **Scale-down stabilization**: mặc định đợi 5 phút trước khi giảm

### Cấu Trúc Tiêu Chuẩn

```yaml
# thêm vào container order-service
resources:
  requests: { cpu: 250m, memory: 512Mi }
  limits:   { memory: 768Mi }
env:     # gộp vào khối env đã có từ Chủ đề 3, không tạo key env thứ 2
  - { name: JAVA_TOOL_OPTIONS, value: "-XX:MaxRAMPercentage=75" }
---
# k8s/order-service/hpa.yaml
# Khi có HPA: XÓA dòng `replicas: 2` trong deployment.yaml — nếu không, mỗi lần `kubectl apply` sẽ ép về 2 replica, đánh nhau với HPA
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: order-service, namespace: dev }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: order-service }
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 50 }
```

File `k8s/metrics-server-patch.json` (JSON không cho comment):

```json
[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]
```

```powershell
# metrics-server (kind cần --kubelet-insecure-tls)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
# Dùng --patch-file: Windows PowerShell 5.1 làm mất dấu " khi truyền JSON inline qua -p cho lệnh ngoài
kubectl patch deploy metrics-server -n kube-system --type=json --patch-file k8s/metrics-server-patch.json
kubectl rollout status deploy/metrics-server -n kube-system
kubectl top pods -n dev
```

```javascript
// scripts/load-test.js  (k6) — đi qua Gateway, cần port-forward 8090 (--address 0.0.0.0) ở Chủ đề 4 đang chạy
import http from 'k6/http';
export const options = { vus: 50, duration: '3m' };
export default function () {
  http.get('http://host.docker.internal:8090/api/orders/');   // có "/" cuối — thiếu thì Tomcat trả 302, k6 tính cả redirect
}
```

```powershell
# PowerShell không có toán tử "<" → pipe nội dung file vào stdin
Get-Content scripts/load-test.js -Raw | docker run --rm -i grafana/k6 run -
kubectl get hpa -n dev -w
```

> `kubectl port-forward` là 1 kết nối đơn đến 1 Pod Envoy — đủ để thấy HPA scale, nhưng không phải cách đo hiệu năng thật.

**Output cụ thể:**
- requests/limits cho mọi container; HPA cho 2 service
- Chứng minh:
  - Load test → `kubectl get hpa -w` thấy replicas 2 → 4+ → load dừng → sau ~5 phút về 2
  - Đặt memory limit 256Mi → thấy `OOMKilled` trong `describe pod` → sửa lại. Lưu ý: với `MaxRAMPercentage=75` JVM tự thu heap về ~192Mi, nên có thể thay vào đó thấy `java.lang.OutOfMemoryError` trong `kubectl logs --previous` (JVM chết trước khi kernel kill). Muốn chắc chắn thấy `OOMKilled`: tạm bỏ `JAVA_TOOL_OPTIONS` và đặt `-Xmx512m` với limit 256Mi
- Ghi vào README: so sánh HPA ↔ ASG target tracking tháng 1 (tốc độ scale, đơn vị scale)

---

## Portfolio Cuối Tuần — Repo `springboot-k8s` (tiếp tục)

```
springboot-k8s/
├── k8s/
│   ├── order-db/      (statefulset, headless svc, create-app-role-job)
│   ├── payment-db/
│   ├── order-redis/   (statefulset)
│   ├── payment-redis/ (statefulset)
│   ├── order-service/ (deployment + probes + resources, service, configmap, hpa)
│   ├── payment-service/
│   └── gateway/       (gatewayclass, gateway, httproute, ingress.yaml để so sánh)
├── scripts/load-test.js
└── README.md
```

**README.md phải trả lời được:**
1. Vì sao DB dùng StatefulSet chứ không phải Deployment?
2. Xóa Pod DB có mất dữ liệu không? Xóa PVC thì sao?
3. readiness vs liveness — nếu cấu hình sai thì hậu quả gì?
4. Request đi từ laptop tới order-service qua những bước nào?
5. HPA tính số replica thế nào? Kèm screenshot scale lên/xuống

---

## 📋 Quick Reference — Tuần 7

| Khái niệm | Tương đương AWS tháng 1 |
|---|---|
| StatefulSet + PVC | RDS + EBS |
| Headless Service | DNS endpoint từng instance |
| Job | Đoạn psql trong user-data |
| readinessProbe | Target group health check |
| Gateway + HTTPRoute | ALB + listener rule |
| requests/limits | Instance type |
| HPA | ASG target tracking policy |

## 🎯 Key Learnings

- [ ] Dữ liệu Postgres sống sót sau khi xóa Pod
- [ ] Job tạo role idempotent
- [ ] 3 loại probe cấu hình đúng, rolling update không lỗi request
- [ ] Routing 2 path qua Gateway API; hiểu Ingress và vì sao chuyển sang Gateway
- [ ] HPA scale lên/xuống khi load test

**Tuần sau → Tuần 8: Helm + Kafka + System Design (phần 1)** 🚀
