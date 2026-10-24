# Week 6 in Detail — Kubernetes Basics: Pod, Deployment, Service, ConfigMap, Secret

> Combined phase Month 2 + 3 (Weeks 5–10). **No AWS needed** — runs on kind (Kubernetes in Docker).

## Context & Goals

**Already done:**
- ✅ Month 1: Docker images for order-service + payment-service, running on EC2/ASG behind an ALB
- ✅ Week 5: Terraform modules, remote backend, tests, CI

**Week 6 goals:**
- Understand Kubernetes architecture (control plane, node, kubelet, container runtime)
- Stand up a local cluster with kind (1 control-plane + 2 workers)
- Run order-service + payment-service with Deployment + Service
- Configure via ConfigMap + Secret (replacing SSM Parameter Store)
- Understand the lifecycle: Pod → ReplicaSet → Deployment → Service
- This week Postgres/Redis run temporarily as simple Deployments (week 7 moves them to StatefulSets)

---

## Why Week 6?

```
Month 1 (EC2 + ASG):
  ❌ ~90-line user-data script: install docker, cwagent, pull image, docker run
  ❌ Changing the image = new launch template + instance refresh (minutes per instance)
  ❌ 1 EC2 = 1 container → wasted resources
  ❌ Config scattered: SSM params + env vars in user-data

Kubernetes:
  ✅ Declare the desired state (YAML) → K8s drives the system to that state
  ✅ Changing the image = 1 command, rolling update in seconds
  ✅ Many containers per node, the scheduler places them
  ✅ ConfigMap/Secret attached to Pods in one consistent way
```

---

## Topic 1: Kubernetes Architecture & kind

### What to Learn

- **Control plane**:
  - `kube-apiserver`: the single entry point — everything (kubectl, controllers) calls this API
  - `etcd`: key-value database storing the entire cluster state
  - `kube-scheduler`: picks a node for each new Pod
  - `kube-controller-manager`: the "reconcile" loops — compare desired vs actual, then fix
- **Worker node**:
  - `kubelet`: receives Pod specs from the API server, tells the container runtime to run them
  - **Container runtime** (containerd): actually runs the containers — K8s no longer uses Docker Engine directly
  - `kube-proxy`: programs iptables/ipvs rules so Services work
- **Declarative vs imperative**: `kubectl apply -f` (declare) vs `kubectl run/create` (command)
- **Namespace**: logical partition (dev, kafka, monitoring...)
- **kind**: each "node" is a Docker container — lightweight, good for learning

### Reference Structure

```
┌─────────────── kind cluster "jaka" ───────────────┐
│  jaka-control-plane (container)                    │
│    ├─ kube-apiserver, etcd, scheduler, controller  │
│  jaka-worker (container)     jaka-worker2          │
│    ├─ kubelet + containerd     ├─ kubelet + ...    │
│    └─ Pods: order, payment     └─ Pods: postgres.. │
└────────────────────────────────────────────────────┘
        ▲ kubectl (laptop) ──► kube-apiserver
```

```yaml
# kind-config.yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: jaka
nodes:
  - role: control-plane
    extraPortMappings:        # used in week 7 for Gateway/Ingress
      - containerPort: 30080
        hostPort: 8080
  - role: worker
  - role: worker
```

> `hostPort: 8080` is just a port Docker opens up front — nothing is listening behind it yet. If your kubeconfig has no current context, kubectl falls back to `localhost:8080` → you get `EOF` (easy to misread). If you hit that: `kind export kubeconfig --name jaka`, or delete and recreate the cluster.

```powershell
kind create cluster --config kind-config.yaml
kubectl get nodes -o wide
kubectl get pods -n kube-system      # shows apiserver, etcd, scheduler, coredns, kube-proxy
```

**Deliverables:**
- A running 3-node kind cluster
- Proof:
  - `kubectl get nodes` → 3 nodes `Ready`
  - `docker ps` → the 3 kind node containers
  - Redraw the control plane / worker diagram in your own words in the README

---

## Topic 2: Pod → ReplicaSet → Deployment

### What to Learn

- **Pod**: the smallest unit, 1+ containers sharing network (same IP) and volumes. Pods are **ephemeral** — when one dies it's gone and the IP changes
- **ReplicaSet**: ensures there are always N identical Pods. Almost never created directly
- **Deployment**: manages ReplicaSets, adds **rolling update** and **rollback**
  - Change the image → a new ReplicaSet is created and scaled up gradually while the old one scales down
  - `maxSurge`, `maxUnavailable`: control the rollout speed
- **Labels & selectors**: how a Deployment/Service "finds" its Pods
- **Loading local images into kind**: `kind load docker-image` (no registry needed). kind nodes are separate containers and **cannot see images in your Windows Docker** — forget `kind load` and you get `ImagePullBackOff`
- **imagePullPolicy**: `IfNotPresent` for loaded images; `Always` for the `latest` tag (with kind, avoid `latest` because `Always` will try to pull from Docker Hub)

> ⚠️ **Required order before applying the app Deployment**: namespace → Secret → ConfigMap → Postgres + Redis (Step 2.0 below). The Deployment uses `envFrom` to reference the ConfigMap/Secret — missing them gives `CreateContainerConfigError`; missing the DB gives `CrashLoopBackOff` (JPA needs a DB connection at startup). The ConfigMap/Secret theory is covered in Topic 4, but you must **create** them now.

### Step 2.0 — Prep: namespace, Secret, ConfigMap, Postgres, Redis

> Commands below are written for **PowerShell** (Windows). Run them from `jaka_infrastructure/`.

```powershell
kubectl create namespace dev
kubectl create secret generic order-service-secret -n dev --from-literal=SPRING_DATASOURCE_PASSWORD=order_pass
```

```yaml
# k8s/order-service/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
  namespace: dev
data:
  SPRING_DATASOURCE_URL: "jdbc:postgresql://order-db:5432/orderdb"
  SPRING_DATASOURCE_USERNAME: "order_user"
  SPRING_DATA_REDIS_HOST: "order-redis"
  SPRING_DATA_REDIS_PORT: "6379"
```

```yaml
# k8s/order-db/order-db.yaml — temporary Deployment, week 7 switches to StatefulSet (deleting the Pod = losing data)
apiVersion: apps/v1
kind: Deployment
metadata: { name: order-db, namespace: dev }
spec:
  replicas: 1
  selector: { matchLabels: { app: order-db } }
  template:
    metadata: { labels: { app: order-db } }
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          ports: [{ containerPort: 5432 }]
          env:
            - { name: POSTGRES_DB,   value: orderdb }
            - { name: POSTGRES_USER, value: order_user }
            - name: POSTGRES_PASSWORD
              valueFrom: { secretKeyRef: { name: order-service-secret, key: SPRING_DATASOURCE_PASSWORD } }
---
apiVersion: v1
kind: Service
metadata: { name: order-db, namespace: dev }
spec:
  selector: { app: order-db }
  ports: [{ port: 5432 }]
```

```yaml
# k8s/order-redis/order-redis.yaml — one Redis per service, same as docker-compose
apiVersion: apps/v1
kind: Deployment
metadata: { name: order-redis, namespace: dev }
spec:
  replicas: 1
  selector: { matchLabels: { app: order-redis } }
  template:
    metadata: { labels: { app: order-redis } }
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports: [{ containerPort: 6379 }]
---
apiVersion: v1
kind: Service
metadata: { name: order-redis, namespace: dev }
spec:
  selector: { app: order-redis }
  ports: [{ port: 6379 }]
```

```powershell
kubectl apply -f k8s/order-db/
kubectl apply -f k8s/order-redis/
kubectl rollout status deploy/order-db -n dev
kubectl rollout status deploy/order-redis -n dev
```

payment-service is identical: `payment-service-secret` (`payment_pass`), `payment-db` (`paymentdb`/`payment_user`), `payment-redis`.

### Reference Structure

```
Deployment order-service (replicas: 2, image v1)
  └─ ReplicaSet order-service-7d9f... (v1)
       ├─ Pod order-service-7d9f-abcde
       └─ Pod order-service-7d9f-fghij

kubectl set image ... v2
  ├─ ReplicaSet 7d9f (v1): 2 → 1 → 0
  └─ ReplicaSet 5c8b (v2): 0 → 1 → 2     ← rolling update
```

> ⚠️ There is **no readinessProbe yet** this week → as soon as the container starts, the Pod counts as Ready and receives traffic, while Spring Boot needs ~10–20s to come up. The rolling update still works but **requests may fail** during that window. True zero-downtime only comes in week 7 when probes are added.

### Step 2.1 — Build image v1 and the Deployment

```powershell
# from order-service/order-service/
docker build -t order-service:v1 .
kind load docker-image order-service:v1 --name jaka
```

```yaml
# k8s/order-service/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  namespace: dev
  labels: { app: order-service }
spec:
  replicas: 2
  selector:
    matchLabels: { app: order-service }
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
  template:
    metadata:
      labels: { app: order-service }
    spec:
      containers:
        - name: order-service
          image: order-service:v1
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8081
          envFrom:
            - configMapRef: { name: order-service-config }
            - secretRef:    { name: order-service-secret }
```

```powershell
# from jaka_infrastructure/ (namespace, Secret, ConfigMap, DB already created in Step 2.0)
kubectl apply -f k8s/order-service/
kubectl rollout status deploy/order-service -n dev
kubectl get deploy,rs,pods -n dev -l app=order-service
kubectl delete pod -n dev <any-one-pod>        # the ReplicaSet creates a new Pod immediately
```

### Step 2.2 — Rolling update to v2 and rollback

```powershell
# 1. Change one log line in the code, then from order-service/order-service/ build + load v2 FIRST
docker build -t order-service:v2 .
kind load docker-image order-service:v2 --name jaka

# 2. From jaka_infrastructure/
kubectl set image deploy/order-service order-service=order-service:v2 -n dev
kubectl rollout status deploy/order-service -n dev
kubectl rollout history deploy/order-service -n dev
kubectl rollout undo deploy/order-service -n dev
```

> ⚠️ With `maxUnavailable: 0`, if the new Pod never becomes Ready (image not loaded, missing ConfigMap...) the rollout **hangs forever**. The old Pods stay as they are; after `progressDeadlineSeconds` (600s) it only reports `ProgressDeadlineExceeded` and does **not roll back automatically**. Unstick it with `kubectl rollout undo`.
>
> `set image` only changes the cluster — `deployment.yaml` still says `v1`. The next `kubectl apply -f` will pull it back to `v1`. To keep v2, update the file too.

**Deliverables:**
- Deployments for order-service and payment-service (2 replicas each)
- Proof:
  - Delete a Pod → a new Pod appears automatically (self-healing)
  - Build image v2 (change one log line) → rolling update → `rollout history` shows 2 revisions
  - `rollout undo` back to v1
- Compare in the README: K8s rolling update vs Month 1 ASG instance refresh

---

## Topic 3: Service — Networking Between Pods

### What to Learn

- **Why Services**: Pod IPs change constantly → you need a stable address + load balancing
- **Service types**:
  - `ClusterIP` (default): reachable only inside the cluster
  - `NodePort`: opens a port 30000–32767 on every node
  - `LoadBalancer`: the cloud provisions a real LB (on AWS = NLB/ALB); on kind you need `cloud-provider-kind`
  - Headless (`clusterIP: None`): returns each Pod's IP — used for StatefulSets (week 7)
- **Internal DNS (CoreDNS)**: `<service>.<namespace>.svc.cluster.local`
- **Endpoints / EndpointSlice**: the list of Pod IPs a Service points to (only **Ready** Pods)
- **`kubectl port-forward`**: temporary access from your laptop, for debugging

### Reference Structure

```
payment-service Pod ──► http://order-service.dev.svc.cluster.local:8081/api/orders
                          │  (CoreDNS → ClusterIP 10.96.x.x)
                          ▼  (kube-proxy iptables, round-robin)
                 order-service Pod A / Pod B
```

```yaml
# k8s/order-service/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: order-service
  namespace: dev
spec:
  type: ClusterIP
  selector: { app: order-service }
  ports:
    - port: 8081
      targetPort: 8081
```

```powershell
kubectl get svc,endpointslices -n dev
kubectl run curl -n dev --rm -it --restart=Never --image=curlimages/curl -- curl -s http://order-service:8081/api/orders/actuator/health
kubectl port-forward -n dev svc/order-service 8081:8081
```

**Deliverables:**
- ClusterIP Services for the 2 apps (Postgres/Redis already have Services from Step 2.0)
- Proof:
  - Call order-service from the `curl` Pod by DNS name
  - `kubectl get endpointslices` shows 2 Pod IPs; delete a Pod → the IP changes, the Service keeps working
  - `port-forward`, then call the API from your laptop

---

## Topic 4: ConfigMap & Secret

### What to Learn

- **ConfigMap**: non-sensitive config (DB URL, Redis host, profile, log level)
- **Secret**: sensitive data (passwords). **Only base64, NOT encryption** — anyone who can read the Secret can read the password
  - Real protection: RBAC, encryption at rest for etcd, or External Secrets Operator / Sealed Secrets (know the concepts)
- **Ways to inject into a Pod**: `envFrom`, `env.valueFrom`, or mount as files
- **Spring Boot relaxed binding**: `SPRING_DATASOURCE_URL` ↔ `spring.datasource.url` — exactly how the Month 1 user-data passed env vars
- **Changing a ConfigMap does not restart Pods** when consumed as env → you need `kubectl rollout restart`
- **Never commit real Secrets to Git**

### Reference Structure

The ConfigMap + Secret were already **created** in Step 2.0 (Topic 2). Here the ConfigMap gets the full set of keys:

```yaml
# k8s/order-service/configmap.yaml (full version)
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
  namespace: dev
data:
  SERVER_PORT: "8081"
  SPRING_DATASOURCE_URL: "jdbc:postgresql://order-db:5432/orderdb"
  SPRING_DATASOURCE_USERNAME: "order_user"
  SPRING_DATA_REDIS_HOST: "order-redis"
  SPRING_DATA_REDIS_PORT: "6379"
  SPRING_DATA_REDIS_DATABASE: "0"
  CLOUDWATCH_METRICS_ENABLED: "false"   # = cloudwatch.metrics.enabled, already off by default — stated explicitly for readability
```

```powershell
# Create the Secret with a command — don't commit a YAML file containing the password
kubectl create secret generic order-service-secret -n dev --from-literal=SPRING_DATASOURCE_PASSWORD=order_pass

# Read it back (PowerShell has no base64 -d)
$b64 = kubectl get secret order-service-secret -n dev -o jsonpath='{.data.SPRING_DATASOURCE_PASSWORD}'
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
```

**Month 1 (AWS) ↔ Kubernetes mapping:**

| Month 1 (AWS) | Kubernetes | Notes |
|---|---|---|
| EC2 instance | Node | |
| ASG + Launch Template | Deployment + ReplicaSet | |
| Instance refresh | Rolling update | much faster |
| Target group health check | readiness probe | week 7 |
| ALB path routing | Ingress / Gateway API | week 7 |
| SSM Parameter Store (String) | ConfigMap | |
| SSM SecureString | Secret | |
| User-data `docker run -e ...` | `envFrom` in the Pod spec | |
| User-data creating the DB role | Job / initContainer | week 7 |
| RDS / ElastiCache | StatefulSet + PVC | week 7 |
| Target tracking CPU | HorizontalPodAutoscaler | week 7 |
| CloudWatch Agent | Prometheus / Loki | month 4 |

**Deliverables:**
- ConfigMap + Secret for both services; the apps read the right config
- Proof:
  - `kubectl exec <pod> -n dev -- env` shows the `SPRING_*` variables (filter with `| Select-String SPRING` in PowerShell)
  - Change `SPRING_DATA_REDIS_DATABASE` in the ConfigMap → `rollout restart` → the new Pods pick up the new value
  - Explain in the README: why a Secret ≠ encryption

---

## Topic 5: Basic Debugging

### What to Learn

- Pod not running → investigate in this order: `get` → `describe` (check Events) → `logs` → `exec`
- Common error states:
  - `ImagePullBackOff`: wrong image name or forgot `kind load`
  - `CrashLoopBackOff`: the app dies at startup (wrong DB URL, missing env)
  - `Pending`: not enough CPU/memory, or the PVC isn't bound
  - `CreateContainerConfigError`: a referenced ConfigMap/Secret is missing

```powershell
kubectl get pods -n dev
kubectl describe pod <pod> -n dev          # check the Events section at the bottom
kubectl logs <pod> -n dev --previous       # logs from the previous (crashed) run
kubectl exec -it <pod> -n dev -- sh
kubectl get events -n dev --sort-by=.lastTimestamp
```

**Deliverables:**
- Deliberately cause 3 failures (wrong image name, wrong DB password, Secret not created) → record symptoms + fixes in `docs/TROUBLESHOOTING.md`

---

## End-of-Week Portfolio — Repo `springboot-k8s`

```
springboot-k8s/
├── kind-config.yaml
├── k8s/
│   ├── namespace.yaml
│   ├── order-service/   (deployment, service, configmap)
│   ├── payment-service/ (deployment, service, configmap)
│   ├── order-db/        (temporary: deployment + service, week 7 switches to StatefulSet)
│   ├── payment-db/
│   ├── order-redis/     (one Redis per service)
│   └── payment-redis/
├── scripts/
│   ├── cluster-up.ps1    # kind create + load images + create Secrets + apply (PowerShell on Windows)
│   └── cluster-down.ps1
├── docs/TROUBLESHOOTING.md
└── README.md
```

**The README must answer:**
1. When you `kubectl apply` a Deployment, which components take part, and in what order?
2. How do Pod, ReplicaSet, and Deployment differ?
3. How does payment-service find order-service?
4. Is a Secret secure? Why or why not?
5. The AWS ↔ K8s mapping table

---

## 📋 Quick Reference — Week 6

| Command | Used for |
|---|---|
| `kind create cluster --config ...` | Create the cluster |
| `kind load docker-image img:tag --name jaka` | Load a local image into the cluster |
| `kubectl apply -f dir/` | Apply manifests |
| `kubectl get pods -o wide` | Show Pods + node + IP |
| `kubectl describe pod x` | Check Events when something fails |
| `kubectl logs x --previous` | Logs from the last crash |
| `kubectl rollout status/history/undo` | Manage rolling updates |
| `kubectl port-forward svc/x 8081:8081` | Access from your laptop |

## 🎯 Key Learnings

- [ ] Can explain the roles of apiserver, etcd, scheduler, controller, kubelet, containerd
- [ ] Run 2 services on kind with Deployment + Service
- [ ] Rolling update + rollback succeeded
- [ ] Configured via ConfigMap/Secret, nothing hardcoded
- [ ] Can debug the 3 common Pod failures

**Next week → Week 7: StatefulSet, Gateway/Ingress, Probes, HPA** 🚀
