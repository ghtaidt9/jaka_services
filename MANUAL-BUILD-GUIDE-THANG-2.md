# Manual build guide — THANG-2: the system on Kubernetes (kind)

A step-by-step spec for rebuilding month 2 by hand. You take the THANG-1 system (order-service and payment-service, each with its own Postgres and Redis), run it on a local Kubernetes cluster, package both services with one Helm chart, put a Gateway in front, add autoscaling, then add a Kafka cluster and send the first event between the two services.

Like the THANG-1 guide, this describes **what** to write and **how to check it**. It does not give you the files.

**Series:** THANG-1 → [MANUAL-BUILD-GUIDE-THANG-1.md](MANUAL-BUILD-GUIDE-THANG-1.md) · **THANG-2 (this file)** · THANG-3 → [MANUAL-BUILD-GUIDE-THANG-3.md](MANUAL-BUILD-GUIDE-THANG-3.md)

**Answer keys.** Open them only after you have tried a stage.

| Part | Where |
|---|---|
| Kubernetes files, chart, scripts | `jaka_infrastructure/`. Ignore anything about inventory or notification, `*-eks.yml`, `k8s/eks/`, `envs/dev-eks/`, `modules/eks`, `modules/ecr`, `saga-demo.sh`, `eks-up.sh`. Those belong to THANG-3 |
| Service code as it was at the end of month 2 | commit `fbfc964` of this repo, for example `git show fbfc964:payment-service/payment-service/src/main/resources/application.yaml` |

---

## Contents

- [TL;DR](#tldr)
- [The big picture (read this first)](#the-big-picture-read-this-first)
- [Why this order](#why-this-order)
- [Know before you start](#know-before-you-start)
- [Stage map](#stage-map)
- [The check loop](#the-check-loop)
- [Stage 0 — Tools, skeleton, service worktree](#stage-0--tools-skeleton-service-worktree)
- [Stage 1 — kind cluster](#stage-1--kind-cluster)
- [Stage 2 — Namespaces and Secrets](#stage-2--namespaces-and-secrets)
- [Stage 3 — Databases: StatefulSets](#stage-3--databases-statefulsets)
- [Stage 4 — Job: create the app DB role](#stage-4--job-create-the-app-db-role)
- [Stage 5 — Service images](#stage-5--service-images)
- [Stage 6 — One service by hand](#stage-6--one-service-by-hand)
- [Stage 7 — Helm chart `spring-service`](#stage-7--helm-chart-spring-service)
- [Stage 8 — metrics-server](#stage-8--metrics-server)
- [Stage 9 — Gateway API (Envoy Gateway)](#stage-9--gateway-api-envoy-gateway)
- [Stage 10 — HorizontalPodAutoscaler](#stage-10--horizontalpodautoscaler)
- [Stage 11 — Kafka on Kubernetes (Strimzi)](#stage-11--kafka-on-kubernetes-strimzi)
- [Stage 12 — The first event](#stage-12--the-first-event)
- [Stage 13 — One command: `cluster-up.sh`](#stage-13--one-command-cluster-upsh)
- [Appendix A — Definition of done](#appendix-a--definition-of-done)
- [Appendix B — Object inventory per file](#appendix-b--object-inventory-per-file)
- [Appendix C — Concepts by stage](#appendix-c--concepts-by-stage)
- [Appendix D — Names, ports and DNS](#appendix-d--names-ports-and-dns)
- [Appendix E — Debug loop](#appendix-e--debug-loop)
- [Appendix F — Improvements to make while rebuilding](#appendix-f--improvements-to-make-while-rebuilding)
- [Appendix G — Not covered](#appendix-g--not-covered)

---

## TL;DR

- Keep working in your THANG-1 rebuild folder (this guide calls it `jaka_manual/`). It gains four things: `kind-config.yaml`, `k8s/`, `charts/spring-service/`, `scripts/`.
- 14 stages: tools → cluster → secrets → databases → role Job → images → one service by hand → Helm → metrics-server → Gateway → HPA → Kafka → first event → one-command script.
- No AWS and no cost. Everything runs on a 3-node kind cluster inside Docker. Give Docker about 8 GB of RAM.
- Service code comes from a git worktree reset to the "end of month 1" state. You retype only the Kafka part (stage 12).

## The big picture (read this first)

### What runs where at the end of THANG-2

```
Laptop
  │  curl http://localhost:8090/api/orders/...
  ▼
kubectl port-forward 8090 → Service envoy-dev-web-… (ns envoy-gateway-system, type LoadBalancer, EXTERNAL-IP <pending> on kind)
  ▼
Envoy Pod  ── reads HTTPRoute "api" (ns dev)
  │   /api/orders*   → Service order-service:8081   → Deployment order-service   (2 Pods)
  │   /api/payments* → Service payment-service:8082 → Deployment payment-service (2 Pods)
  ▼
order Pods   → order-db   (StatefulSet + PVC)   order_user / orderdb
             → order-redis (StatefulSet + PVC)
payment Pods → payment-db  (StatefulSet + PVC)  payment_user / paymentdb
             → payment-redis (StatefulSet + PVC)

order Pods ──OrderCreated──► Kafka "jaka" (ns kafka, Strimzi, 1 node) topic order-events ──► payment Pods (group payment-service)
```

| Namespace | What lives there | Created by |
|---|---|---|
| `kube-system` | API server, etcd, scheduler, controller manager, CoreDNS, kube-proxy, metrics-server | kind, stage 8 |
| `local-path-storage` | the provisioner behind StorageClass `standard` | kind |
| `dev` | Secrets, DBs, Redis, role Jobs, the 2 apps, Gateway `web`, HTTPRoute `api` | stages 2–10 |
| `envoy-gateway-system` | Envoy Gateway controller and the Envoy proxy it creates | stage 9 |
| `kafka` | Strimzi operator, Kafka broker, entity operator, topics | stage 11 |

### What replaced what (THANG-1 → THANG-2)

You already know every job these objects do. Only the tool changed.

| Job | THANG-1 (AWS, EC2) | THANG-2 (kind) | File |
|---|---|---|---|
| Machines | VPC, subnets, EC2 instances | 1 control-plane + 2 worker nodes (Docker containers) | `kind-config.yaml` |
| Get the image onto the machine | ECR + `docker pull` in user data | `docker build` + `kind load` | stage 5, `cluster-up.sh` |
| How a service is started | launch template user data, `docker run -e ...` | Deployment Pod template + ConfigMap + Secret | chart `deployment.yml`, `configmap.yml` |
| How many copies | ASG min/max/desired | Deployment `replicas` | chart |
| Scale on CPU | ASG target-tracking policy | HorizontalPodAutoscaler | chart `hpa.yml` |
| Deploy a new version | ASG instance refresh | rolling update (`maxSurge`, `maxUnavailable`) | chart `deployment.yml` |
| "Is it healthy?" | ALB target-group health check | readiness probe | chart `deployment.yml` |
| One stable address for N copies | target group | Service (ClusterIP) | chart `service.yml` |
| Public entry + path routing | ALB + listener rules | Envoy Gateway + Gateway + HTTPRoute | `k8s/gateway.yml` |
| Database | RDS instance | Postgres StatefulSet + PVC + headless Service | `k8s/<svc>-service/<svc>-db.yml` |
| Cache | ElastiCache (shared) | one Redis StatefulSet per service | `k8s/<svc>-service/<svc>-redis.yml` |
| Plain config | SSM Parameter Store | ConfigMap | chart `configmap.yml` |
| Passwords | Secrets Manager | Secret | `kubectl create secret` |
| Create `order_user` with grants | bootstrap script on every EC2 boot | one-shot Job | `k8s/<svc>-service/create-app-role-job.yml` |
| CPU/RAM numbers | CloudWatch agent | metrics-server (only what HPA needs) | stage 8 |
| One definition, N services | `modules/service` + `for_each` | Helm chart + one values file per service | `charts/spring-service/` |
| Message broker | none | Kafka via the Strimzi operator | `k8s/kafka/` |

### What flows between objects

In Terraform, modules pass **outputs** to **inputs**. In Kubernetes, objects find each other by **name** and by **label**. Nothing checks those names at `apply` time. A typo shows up later as a Pod that will not start, a Service with no endpoints, or a 404.

| Producer | Field that names it | Consumer |
|---|---|---|
| Secret `<svc>-db-admin`, key `password` | `secretKeyRef` | StatefulSet `<svc>-db` (`POSTGRES_PASSWORD`), Job (`PGPASSWORD`) |
| Secret `<svc>-service-secret`, key `SPRING_DATASOURCE_PASSWORD` | `secretKeyRef` / `envFrom.secretRef` | Job (`APP_PASS`), app Pods |
| Service `<svc>-db` | DNS name | Job (`psql -h`), app (`SPRING_DATASOURCE_URL`) |
| Service `<svc>-redis` | DNS name | app (`SPRING_DATA_REDIS_HOST`) |
| ConfigMap `<svc>-service` | `envFrom.configMapRef` | app Pods |
| Pod label `app: <svc>-service` | `selector` | Deployment, Service |
| Service `<svc>-service` + port | `backendRefs` | HTTPRoute `api` |
| Gateway `web` | `parentRefs` | HTTPRoute `api` |
| GatewayClass `eg` | `gatewayClassName` | Gateway `web` |
| Deployment `<svc>-service` | `scaleTargetRef` | HPA |
| Kafka `jaka` | label `strimzi.io/cluster: jaka` | KafkaNodePool, KafkaTopic |
| Service `jaka-kafka-bootstrap` (ns `kafka`) | DNS `jaka-kafka-bootstrap.kafka:9092` | app (`KAFKA_BOOTSTRAP`) |

Reading the table top to bottom gives the order you apply things in.

## Why this order

- **Secrets before the Pods that read them.** A Pod that references a missing Secret stays in `CreateContainerConfigError`.
- **Database, then role Job, then app.** The app logs in as `order_user`, which only exists after the Job.
- **One service by hand before Helm.** A chart is just YAML with holes in it. You have to know the YAML first, and you need to feel the duplication that Helm removes.
- **CRDs before the objects that use them.** Envoy Gateway installs the Gateway API types before `gateway.yml`; Strimzi installs the Kafka types before `kafka-single-node.yml`.
- **Kafka near the end.** Only stage 12 needs it.
- **The script last.** `cluster-up.sh` is stages 1–12 in order, written so that running it twice is safe.

## Know before you start

| Tradeoff | Consequence |
|---|---|
| kind nodes are Docker containers, not cloud VMs | No cloud load balancer: a `LoadBalancer` Service stays `EXTERNAL-IP <pending>`. You reach the Gateway with `kubectl port-forward` |
| Images are not in a registry | Every new image needs `kind load`, and every build needs a new tag (`imagePullPolicy: IfNotPresent` never replaces a tag the node already has) |
| Data lives in PVCs on the node's disk | `kind delete cluster` deletes every database |
| Passwords come from a local `.env` | It must be git-ignored. THANG-3 replaces it with Secrets Manager on EKS |
| Kafka has one node, replication factor 1 | Fine for learning; losing the node loses the data |
| Memory | 3 nodes + 2 × (Postgres, Redis, 2 app Pods) + Kafka ≈ 6–8 GB |
| One cluster at a time | This guide reuses the name `jaka`. Delete the reference cluster before stage 1 |

## Stage map

| Stage | You write | Depends on | Needs Kafka |
|---|---|---|---|
| 0 | folders, `.gitignore`, `.env`, service worktree | — | no |
| 1 | `kind-config.yaml` | 0 | no |
| 2 | (commands only) | 1 | no |
| 3 | `k8s/<svc>-service/<svc>-db.yml`, `<svc>-redis.yml` | 2 | no |
| 4 | `k8s/<svc>-service/create-app-role-job.yml` | 3 | no |
| 5 | (service worktree reset, images) | 0 | no |
| 6 | `k8s/scratch/order-service/*.yml` (thrown away in stage 7) | 4, 5 | no |
| 7 | `charts/spring-service/**` | 6 | no |
| 8 | `k8s/metrics-server-patch.json` | 1 | no |
| 9 | `k8s/gateway.yml` | 7 | no |
| 10 | (values only) | 7, 8 | no |
| 11 | `k8s/kafka/kafka-single-node.yml`, `k8s/kafka/topics.yml` | 2 | yes |
| 12 | service code in the worktree | 11 | yes |
| 13 | `scripts/cluster-up.sh`, `scripts/cluster-down.sh` | all | yes |

## The check loop

For every YAML file:

```bash
kubectl apply --dry-run=server -f <file>    # the API server validates fields, and fails if a CRD is missing
kubectl apply -f <file>
kubectl get <kind> -n <namespace>
kubectl describe <kind>/<name> -n <namespace>   # read the Events section at the bottom
```

For the chart:

```bash
helm lint charts/spring-service -f charts/spring-service/values/order-dev.yml
helm template order-service charts/spring-service -f charts/spring-service/values/order-dev.yml
```

A stage is done when its **Check** passes. When something fails, use [Appendix E](#appendix-e--debug-loop).

---

## Stage 0 — Tools, skeleton, service worktree

**Goal:** the tools installed, the folders created, and a copy of the services you can change freely.

### Tools

| Tool | Used for | Check |
|---|---|---|
| Docker | runs the kind nodes, builds images | `docker info` |
| kind | creates the cluster | `kind version` |
| kubectl | talks to the API server | `kubectl version --client` |
| Helm 3 | installs the chart and Envoy Gateway | `helm version` |
| jq, curl | scripts and tests | `jq --version` |

### Folders

```bash
cd jaka_manual
mkdir -p k8s/order-service k8s/payment-service k8s/kafka k8s/scratch/order-service
mkdir -p charts/spring-service/templates charts/spring-service/values scripts
```

### `.gitignore`

Add `.env`. Leave the THANG-1 lines alone.

### `.env`

One line per variable, `NAME=value`, no quotes, no spaces around `=`.

| Variable | Used as | Read by |
|---|---|---|
| `ORDER_DB_PASS` | password of `order_user` | Secret `order-service-secret` |
| `ORDER_DB_ADMIN_PASS` | password of `order_admin` | Secret `order-db-admin` |
| `PAYMENT_DB_PASS` | password of `payment_user` | Secret `payment-service-secret` |
| `PAYMENT_DB_ADMIN_PASS` | password of `payment_admin` | Secret `payment-db-admin` |
| `APP_ROOT` | folder that holds `order-service/` and `payment-service/` | `cluster-up.sh` (stage 13) |

The app password and the admin password must differ. THANG-3 adds four more variables for inventory and notification.

### Service worktree

The services in this repo are already at their THANG-3 state. Commit `fbfc964` holds them as they were at the end of month 2. A git worktree gives you that state in a separate folder with its own branch, without touching your real services.

```bash
cd ..                                                    # repo root
git worktree add -b thang2-services ../jaka-services fbfc964
```

Set `APP_ROOT=../../jaka-services` in `.env` (the path from `jaka_manual/` to the worktree).

That commit already contains the month-2 Kafka code. Delete it now, so that stages 5–11 run the plain month-1 apps and you can retype the Kafka part in stage 12.

| Service | Delete |
|---|---|
| order (package `com.example.order_service`) | `publisher/OrderEventPublisher.java`, `dto/OrderCreatedEvent.java`, `dto/CreateOrderRequest.java` |
| order | in `service/OrderService.java`: the `publisher` field and the whole `create` method |
| order | in `controller/OrderController.java`: the `@PostMapping` method |
| payment (package `com.jaka.payment_service`) | `consumer/OrderEventConsumer.java`, `dto/OrderCreatedEvent.java` |
| both | in `application.yaml`: the whole `spring.kafka` block |
| both | in `build.gradle`: `spring-boot-starter-kafka`, `spring-boot-starter-kafka-test`, `testcontainers-kafka` |

### Check

```bash
cd ../jaka-services/order-service/order-service && ./gradlew build -x test
cd ../../payment-service/payment-service && ./gradlew build -x test
cd ../.. && git add -A && git commit -m "Reset services to month 1"
```

Both builds end with `BUILD SUCCESSFUL`. A `cannot find symbol` error means a reference to a deleted class is still there.

### Delete the reference cluster

```bash
kind get clusters
kind delete cluster --name jaka
```

You can always recreate it from `jaka_infrastructure/` with `./scripts/cluster-up.sh`.

---

## Stage 1 — kind cluster

**Goal:** a Kubernetes cluster with one control-plane node and two worker nodes.

**File:** `kind-config.yaml`

| Field | Value |
|---|---|
| `kind` | `Cluster` |
| `apiVersion` | `kind.x-k8s.io/v1alpha4` |
| `name` | `jaka` |
| `nodes` | three entries: `role: control-plane`, `role: worker`, `role: worker` |

The reference also maps `containerPort: 30080` to `hostPort: 8080` on the control-plane node. Nothing uses it (the Gateway is reached with port-forward). Leave it out; see [Appendix F](#appendix-f--improvements-to-make-while-rebuilding).

### Commands

```bash
kind create cluster --config kind-config.yaml
kubectl get nodes
docker ps --format '{{.Names}}'
kubectl get pods -n kube-system -o wide
```

### What you are looking at, bottom up

1. **Three Docker containers** (`jaka-control-plane`, `jaka-worker`, `jaka-worker2`). Each one plays a machine. Each runs `containerd` (starts containers) and `kubelet` (the node agent that asks the API server which Pods this node should run).
2. **The control plane** runs as Pods in `kube-system` on `jaka-control-plane`: `kube-apiserver` (the only thing anyone talks to), `etcd` (where every object is stored), `kube-scheduler` (picks a node for each new Pod), `kube-controller-manager` (the loops that make reality match the stored objects; for example, the ReplicaSet loop creates missing Pods).
3. **Every node** runs `kube-proxy` (turns Service IPs into routes to Pod IPs) and `kindnet` (the network plugin that gives each Pod an IP).
4. **CoreDNS** (2 Pods) answers names like `order-db.dev.svc.cluster.local`.
5. **local-path-provisioner** (namespace `local-path-storage`) provides StorageClass `standard`. It creates a folder on the node for each PVC.

### Check

`kubectl get nodes` shows 3 nodes, all `Ready`.

### Concepts

- The cluster is the control plane plus nodes. "Kubernetes runs my Pod" means: the scheduler wrote a node name into the Pod object, and that node's kubelet started the containers.
- kind is for learning and CI only. THANG-3 replaces it with EKS, where AWS runs the control plane.

---

## Stage 2 — Namespaces and Secrets

**Goal:** two namespaces, and the four password Secrets every later stage reads.

### Namespaces

| Name | Holds |
|---|---|
| `dev` | everything of the application |
| `kafka` | the Strimzi operator and the Kafka cluster |

`kubectl create` fails if the object already exists. Rendering it with `--dry-run=client -o yaml` and piping into `kubectl apply` makes the command safe to repeat. Every script in this project uses that pattern.

```bash
for ns in dev kafka; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
done
```

### Secrets

| Secret (ns `dev`) | Key | Value from `.env` | Read by |
|---|---|---|---|
| `order-service-secret` | `SPRING_DATASOURCE_PASSWORD` | `ORDER_DB_PASS` | order Pods, order Job |
| `order-db-admin` | `password` | `ORDER_DB_ADMIN_PASS` | order-db, order Job |
| `payment-service-secret` | `SPRING_DATASOURCE_PASSWORD` | `PAYMENT_DB_PASS` | payment Pods, payment Job |
| `payment-db-admin` | `password` | `PAYMENT_DB_ADMIN_PASS` | payment-db, payment Job |

The key of the app Secret is the exact name of a Spring environment variable. That lets the Deployment load the whole Secret with `envFrom`, and Spring maps `SPRING_DATASOURCE_PASSWORD` to `spring.datasource.password`.

```bash
set -a; source .env; set +a
kubectl create secret generic order-service-secret -n dev \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$ORDER_DB_PASS" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic order-db-admin -n dev \
  --from-literal=password="$ORDER_DB_ADMIN_PASS" --dry-run=client -o yaml | kubectl apply -f -
```

Repeat both commands for payment with the `PAYMENT_*` variables.

### Check

```bash
kubectl get secrets -n dev
kubectl get secret order-service-secret -n dev -o jsonpath='{.data.SPRING_DATASOURCE_PASSWORD}' | base64 -d; echo
```

Four Secrets of type `Opaque`; the second command prints your password.

### Concepts

- Base64 in a Secret is **encoding, not encryption**. Anyone who can read the Secret can read the password. That is why the Secret is created from a command and never written to a YAML file in git.
- The admin password goes only to the database and the Job. The app Pods never see it. In THANG-1, every EC2 instance held the admin secret because the bootstrap script created the role at boot (THANG-1 Appendix F, item 9). Here that is fixed.

---

## Stage 3 — Databases: StatefulSets

**Goal:** one Postgres and one Redis per service, each keeping its data when the Pod is replaced.

**Files:** `k8s/order-service/order-db.yml`, `k8s/order-service/order-redis.yml`, `k8s/payment-service/payment-db.yml`, `k8s/payment-service/payment-redis.yml`.

Each file holds two objects separated by `---`: a headless Service, then a StatefulSet. Every object has `namespace: dev`.

### Postgres: the Service

| Field | order value | Why |
|---|---|---|
| `apiVersion` / `kind` | `v1` / `Service` | |
| `metadata.name` | `order-db` | becomes the DNS name the Job and the app use |
| `spec.clusterIP` | `None` | headless: DNS returns the Pod IP directly, and each Pod gets its own name (`order-db-0.order-db`) |
| `spec.selector` | `app: order-db` | must match the Pod label below |
| `spec.ports` | one entry, `port: 5432` | |

### Postgres: the StatefulSet

| Field | order value | Why |
|---|---|---|
| `apiVersion` / `kind` | `apps/v1` / `StatefulSet` | |
| `metadata.name` | `order-db` | the Pod will be `order-db-0` |
| `spec.serviceName` | `order-db` | the headless Service above; required for StatefulSets |
| `spec.replicas` | `1` | |
| `spec.selector.matchLabels` | `app: order-db` | |
| `template.metadata.labels` | `app: order-db` | |
| container `name` / `image` | `postgres` / `postgres:16` | same major version as RDS in THANG-1 |
| env `POSTGRES_DB` | `orderdb` | database created on first boot |
| env `POSTGRES_USER` | `order_admin` | the admin, like `order_admin` on RDS |
| env `POSTGRES_PASSWORD` | `secretKeyRef` → Secret `order-db-admin`, key `password` | |
| env `PGDATA` | `/var/lib/postgresql/data/pgdata` | a subfolder: on a real disk (an EBS volume, for example) the volume root contains `lost+found`, and `initdb` refuses a non-empty folder. kind's local-path folders are empty, so here it only keeps the file portable |
| `ports` | `containerPort: 5432` | |
| `volumeMounts` | name `data`, mountPath `/var/lib/postgresql/data` | |
| `readinessProbe` | `exec` command `pg_isready -U order_admin -d orderdb`, `periodSeconds: 5` | the Pod is Ready only when Postgres accepts connections |
| `volumeClaimTemplates` | one entry: `metadata.name: data`; `accessModes: [ReadWriteOnce]`; `storageClassName: standard`; `resources.requests.storage: 1Gi` | one PVC per Pod, named `data-order-db-0`, kept when the Pod is deleted |

For payment, replace every `order` with `payment`.

### Redis

Same two objects with these differences:

| Field | Value |
|---|---|
| names and labels | `order-redis` (Service, StatefulSet, `app` label) |
| container | `redis` / `redis:7-alpine` |
| port | `6379` |
| volume mount | `data` at `/data` |
| `readinessProbe` | `exec` command `redis-cli ping`, `periodSeconds: 5` |
| env | none (no password on kind) |

### Commands

```bash
kubectl apply --dry-run=server -f k8s/order-service/ -f k8s/payment-service/
kubectl apply -f k8s/order-service/ -f k8s/payment-service/
kubectl rollout status statefulset/order-db -n dev --timeout=5m
kubectl get pods,pvc -n dev
kubectl get pv
```

### Check: the data survives the Pod

```bash
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "CREATE TABLE ping(x int); INSERT INTO ping VALUES (1);"
kubectl delete pod -n dev order-db-0
kubectl wait --for=condition=Ready pod/order-db-0 -n dev --timeout=2m
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "SELECT * FROM ping;"
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "DROP TABLE ping;"
```

The `SELECT` returns the row. The new Pod has the same name and mounted the same PVC.

### Typical errors

| Symptom | Cause |
|---|---|
| Log: `initdb: error: directory "/var/lib/postgresql/data" exists but is not empty` | `PGDATA` missing, on a volume that has `lost+found` (block storage such as EBS; not kind's local-path) |
| PVC stays `Pending` | wrong `storageClassName` |
| You changed `POSTGRES_PASSWORD` and nothing happened | `initdb` runs only on an empty volume. Delete the StatefulSet **and** the PVC to start over |
| Pod `CreateContainerConfigError` | the Secret or key name does not exist (stage 2) |

### Concepts

- **Deployment vs StatefulSet.** A Deployment's Pods are interchangeable: random names, shared or no storage. A StatefulSet gives each Pod a fixed name (`-0`, `-1`), starts them in order, and gives each its own PVC that outlives the Pod.
- **PV, PVC, StorageClass.** The PVC is the request ("1 Gi, one writer"). The StorageClass is the supplier. The PV is the actual disk the supplier created. On EKS (THANG-3) only the supplier changes: an EBS volume instead of a folder on the node.

---

## Stage 4 — Job: create the app DB role

**Goal:** create `order_user` and `payment_user` with only the rights the apps need.

**Files:** `k8s/order-service/create-app-role-job.yml`, `k8s/payment-service/create-app-role-job.yml`.

**Why:** the database starts with only the admin. The app must log in as its own user, and with `ddl-auto: update` Hibernate creates tables, so that user needs `CREATE` on schema `public`. Since Postgres 15, ordinary users no longer get that by default. THANG-1 did this in the EC2 bootstrap script on every boot; here a one-shot Job does it once.

### The Job

| Field | order value | Why |
|---|---|---|
| `apiVersion` / `kind` | `batch/v1` / `Job` | |
| `metadata` | name `order-db-create-app-role`, namespace `dev` | |
| `spec.backoffLimit` | `5` | the DB may still be starting; retry instead of failing at once |
| `spec.ttlSecondsAfterFinished` | `300` | the finished Job deletes itself after 5 minutes, so a later `apply` can create it again |
| `template.spec.restartPolicy` | `OnFailure` | |
| container | name `psql`, image `postgres:16` | it has the `psql` client |
| env `PGPASSWORD` | `secretKeyRef` → `order-db-admin` / `password` | `psql` reads the password from this variable |
| env `APP_PASS` | `secretKeyRef` → `order-service-secret` / `SPRING_DATASOURCE_PASSWORD` | the password to give `order_user` |
| `command` | `["sh", "-c"]` | |
| `args` | one multi-line string (`- \|`), the script below | |

### The script, line by line

| Line | What it does |
|---|---|
| `psql -h order-db -U order_admin -d orderdb -v ON_ERROR_STOP=1 -v app_pass="$APP_PASS" <<'SQL'` | connect as admin; stop on the first error; put the password into a psql variable `app_pass`; read SQL from the here-document. `'SQL'` in quotes stops the shell from expanding `$` inside it |
| `SELECT EXISTS (SELECT FROM pg_roles WHERE rolname = 'order_user') AS role_exists \gset` | store `true`/`false` into psql variable `role_exists` |
| `\if :role_exists` | psql's own if |
| `ALTER ROLE order_user WITH PASSWORD :'app_pass';` | role exists: only reset the password |
| `\else` | |
| `CREATE ROLE order_user LOGIN PASSWORD :'app_pass';` | first run: create it |
| `\endif` | |
| `GRANT CREATE, USAGE ON SCHEMA public TO order_user;` | what `ddl-auto: update` needs |
| `SQL` | end of the here-document |

`:'app_pass'` makes psql insert the value as a correctly quoted SQL literal. Pasting `$APP_PASS` straight into the SQL would break on a password containing `'`, and is the shape of an SQL injection.

### Commands

```bash
kubectl apply -f k8s/order-service/create-app-role-job.yml -f k8s/payment-service/create-app-role-job.yml
kubectl wait --for=condition=Complete job/order-db-create-app-role -n dev --timeout=5m
kubectl logs -n dev job/order-db-create-app-role
```

### Check: log in over the network as the app

The Postgres image trusts connections from inside its own Pod, so test from another Pod. Only a network connection checks the password. Load `.env` first if this is a new terminal (`set -a; source .env; set +a`).

```bash
kubectl run psql-test -n dev --rm -it --restart=Never --image=postgres:16 \
  --env PGPASSWORD="$ORDER_DB_PASS" -- psql -h order-db -U order_user -d orderdb -c 'select current_user'
```

It prints `order_user`. Run the Job a second time (delete it, apply again): it must succeed again, now through the `ALTER` branch.

### Typical errors

| Symptom | Cause |
|---|---|
| `The Job "..." is invalid: spec.template: field is immutable` | you changed the Job while the old one still exists. Delete it, then apply |
| Job Pods in `Error` a few times, then `Completed` | normal: the DB was not ready yet |
| App log later: `permission denied for schema public` | the `GRANT` line is missing |
| `password authentication failed for user "order_user"` | `.env` changed after the Job ran: rerun the Job |

---

## Stage 5 — Service images

**Goal:** container images of both services, loaded into every kind node.

### What the app needs to run on Kubernetes

Nothing in the code changes. Each need is already covered:

| Need | How it is met |
|---|---|
| An image | the month-1 multi-stage `Dockerfile` (Gradle build stage, then `eclipse-temurin:17-jre-alpine`) |
| Config from outside | Spring relaxed binding: env `SPRING_DATASOURCE_URL` overrides `spring.datasource.url` in `application.yaml`. The `localhost` defaults stay for local runs |
| Port | `server.port` is 8081 (order) and 8082 (payment); the chart also sets `SERVER_PORT` |
| Health endpoints for probes | `spring-boot-starter-actuator` is there; the Deployment turns on `/actuator/health/liveness` and `/readiness` with env vars (stage 6) |

Note the ports: on EC2 in THANG-1, order and payment used 8080 and 8081. On Kubernetes each service keeps the port from its `application.yaml`.

### Commands

From `jaka_manual/` (the path is your `APP_ROOT`):

```bash
docker build -t order-service:v1 ../../jaka-services/order-service/order-service
docker build -t payment-service:v1 ../../jaka-services/payment-service/payment-service
kind load docker-image order-service:v1 --name jaka
kind load docker-image payment-service:v1 --name jaka
```

### Check

```bash
docker exec jaka-worker crictl images | grep -E 'order-service|payment-service'
```

Both images with tag `v1`. Repeat for `jaka-worker2` and `jaka-control-plane`: `kind load` copies the image into every node, because any node may run the Pod.

---

## Stage 6 — One service by hand

**Goal:** order-service running from three plain YAML files, so you know exactly what the chart in stage 7 must produce.

**Files:** `k8s/scratch/order-service/configmap.yml`, `deployment.yml`, `service.yml`. They are deleted at the end of stage 7.

### ConfigMap

Name `order-service`, namespace `dev`. `data` (every value is a string):

| Key | Value |
|---|---|
| `SERVER_PORT` | `"8081"` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://order-db:5432/orderdb` |
| `SPRING_DATASOURCE_USERNAME` | `order_user` |
| `SPRING_DATA_REDIS_HOST` | `order-redis` |

### Deployment

| Field | Value | Why |
|---|---|---|
| `metadata` | name `order-service`, namespace `dev`, label `app: order-service` | |
| `spec.replicas` | `2` | |
| `spec.selector.matchLabels` | `app: order-service` | which Pods this Deployment owns |
| `spec.strategy` | `RollingUpdate`, `maxSurge: 1`, `maxUnavailable: 0` | add one new Pod, wait until Ready, then remove one old Pod; capacity never drops |
| `template.metadata.labels` | `app: order-service` | |
| container `name` | `app` | |
| `image` / `imagePullPolicy` | `order-service:v1` / `IfNotPresent` | use the image `kind load` put on the node; never try a registry |
| `ports` | `containerPort: 8081` | |
| `resources.requests` | `cpu: 250m`, `memory: 512Mi` | what the scheduler reserves on a node; HPA percentages are relative to the CPU request |
| `resources.limits` | `memory: 768Mi` (no CPU limit) | over the memory limit the container is killed (`OOMKilled`); a CPU limit would only throttle the JVM |
| `envFrom` | `configMapRef: order-service`, `secretRef: order-service-secret` | every key becomes an env var |
| env `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` | heap = 75 % of the 768 Mi limit, leaving room for non-heap memory |
| env `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | `"true"` | exposes `/actuator/health/liveness` and `/readiness` |
| env `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE` | `readinessState,db` | not Ready while the database is down |
| `startupProbe` | `httpGet` path `/api/orders/actuator/health/liveness`, port 8081, `periodSeconds: 5`, `failureThreshold: 24` | up to 120 s for Spring Boot to start; the other probes wait for it |
| `readinessProbe` | `httpGet` path `/api/orders/actuator/health/readiness`, port 8081, `periodSeconds: 5` | failing removes the Pod from the Service, without restarting it |
| `livenessProbe` | `httpGet` path `/api/orders/actuator/health/liveness`, port 8081, `periodSeconds: 10`, `failureThreshold: 3` | failing restarts the container |
| `lifecycle.preStop` | `sleep: { seconds: 5 }` | on shutdown, keep serving 5 s while Service endpoints and Envoy stop sending traffic |

The probe paths start with the context path (`/api/orders`): actuator lives under it.

### Service

| Field | Value |
|---|---|
| name / namespace | `order-service` / `dev` |
| `type` | `ClusterIP` |
| `selector` | `app: order-service` |
| `ports` | `port: 8081`, `targetPort: 8081` |

### Commands

```bash
kubectl apply -f k8s/scratch/order-service/
kubectl get deploy,rs,pods -n dev -l app=order-service
kubectl logs -n dev deploy/order-service | grep -E 'Started|connectivity'
kubectl run curl -n dev --rm -it --restart=Never --image=curlimages/curl -- \
  curl -s http://order-service:8081/api/orders/actuator/health
```

### Check: exercises

| Do | You should see | Concept |
|---|---|---|
| `kubectl delete pod -n dev <one order pod>` | a new Pod within seconds | the ReplicaSet keeps the count |
| change a log line, build `order-service:v2`, `kind load`, then `kubectl set image deploy/order-service app=order-service:v2 -n dev` | `kubectl rollout status` waits; `kubectl get rs` shows an old and a new ReplicaSet | rolling update |
| `kubectl rollout undo deploy/order-service -n dev` | back to v1 in seconds | the old ReplicaSet is kept for rollback |
| `kubectl get endpointslices -n dev -l kubernetes.io/service-name=order-service` | the two Pod IPs | how a Service finds Pods |
| `kubectl scale statefulset order-db -n dev --replicas=0` | order Pods become `0/1` Ready but are **not** restarted | readiness includes `db`, liveness does not |
| scale `order-db` back to 1 | Pods Ready again | |

### Now look at what payment would need

Writing payment by hand means copying the three files and changing: the name, the image, the port, the context path in three probes, and the four config values. THANG-3 adds two more services. That copy-and-edit is what Helm removes.

### Typical errors

| Symptom | Cause |
|---|---|
| `ErrImagePull` / `ImagePullBackOff` | image not loaded with `kind load`, or tag `latest` (which forces `Always`) |
| `CrashLoopBackOff`, log `password authentication failed` | Secret password ≠ the one the Job set |
| Startup probe fails with 404 | context path missing from the probe path |
| `OOMKilled` | memory limit too low for the heap; check `MaxRAMPercentage` |

---

## Stage 7 — Helm chart `spring-service`

**Goal:** one chart that deploys any of the Spring services; one small values file per service.

**Why:** both services need the same ConfigMap, Deployment and Service, with about ten different values. In THANG-1 you solved the same problem with `modules/service` called with `for_each`. A chart is the same idea for Kubernetes YAML:

| Terraform (THANG-1) | Helm |
|---|---|
| `modules/service/` | `charts/spring-service/` |
| `variables.tf` with defaults | `values.yaml` |
| one entry in the `services` map | `values/order-dev.yml` |
| `module.service["order"]` | release `order-service` |
| `terraform plan` | `helm template` |
| `terraform apply` | `helm upgrade --install` |
| state file | release history (stored as Secrets in the namespace) |

### Files

| File | Holds |
|---|---|
| `Chart.yaml` | chart metadata |
| `values.yaml` | defaults |
| `templates/_helpers.tpl` | the name helper |
| `templates/configmap.yml` | ConfigMap |
| `templates/deployment.yml` | Deployment |
| `templates/service.yml` | Service |
| `templates/serviceaccount.yml` | ServiceAccount (used in THANG-3) |
| `templates/hpa.yml` | HPA, only when enabled |
| `values/order-dev.yml`, `values/payment-dev.yml` | per-service values for kind |
| `values/order-prod.yml`, `values/payment-prod.yml` | per-service values for a bigger cluster |

### `Chart.yaml`

`apiVersion: v2`, `name: spring-service`, `description: Shared chart for the Spring Boot services`, `type: application`, `version: 0.1.0`, `appVersion: "v1"`.

### `values.yaml`

| Key | Default | Note |
|---|---|---|
| `image.repository` | `""` | must be set per service; the template fails if empty |
| `image.tag` | `"v1"` | overridden by `--set image.tag=...` on each deploy |
| `image.pullPolicy` | `IfNotPresent` | |
| `port` | `8080` | |
| `contextPath` | `""` | |
| `replicaCount` | `2` | ignored when autoscaling is on |
| `resources.requests` | `cpu: 250m`, `memory: 512Mi` | |
| `resources.limits` | `memory: 768Mi` | |
| `autoscaling.enabled` | `false` | |
| `autoscaling.minReplicas` / `maxReplicas` / `targetCPU` | `2` / `6` / `50` | |
| `env` | `{}` | map of env var → value, rendered into the ConfigMap |
| `secretName` | `""` | when set, loaded with `envFrom.secretRef` |
| `serviceAccount.create` | `true` | |

### `templates/_helpers.tpl`

One named template, `spring-service.fullname`: the release name, truncated to 63 characters, trailing `-` removed (`.Release.Name | trunc 63 | trimSuffix "-"`, wrapped in `define` and `end` with `{{-` trimming).

Every object is named with it. Release `order-service` gives a Deployment, Service and ConfigMap all called `order-service`, the same names as stage 6, which `gateway.yml` and the scripts rely on.

### `templates/configmap.yml`

| Part | Template |
|---|---|
| name | `include "spring-service.fullname" .` |
| `data.SERVER_PORT` | `.Values.port \| quote` |
| the rest of `data` | `range $key, $value := .Values.env`, each line `$key: $value \| quote` |

`quote` matters: ConfigMap values must be strings. `MAIL_PORT: 1025` without quotes fails to install.

### `templates/deployment.yml`

The stage-6 Deployment with these holes:

| Field | Template | Why |
|---|---|---|
| names, labels, selectors | `app: <fullname>` | |
| `spec.replicas` | only inside `if not .Values.autoscaling.enabled` | when the HPA owns the count, a fixed `replicas` would reset it on every upgrade |
| `strategy` | literal, as in stage 6 | |
| Pod annotation `checksum/config` | `include (print $.Template.BasePath "/configmap.yml") . \| sha256sum` | a ConfigMap change alone does not restart Pods; changing this hash does |
| `image` | `required "image.repository is required" .Values.image.repository`, then `:`, then `.Values.image.tag`, all in quotes | a clear error instead of an image called `:v1` |
| `imagePullPolicy` | `.Values.image.pullPolicy` | |
| `containerPort` | `.Values.port` | |
| `resources` | `toYaml .Values.resources \| nindent 12` | `nindent` = newline + indent; 12 is the indentation of the lines **under** `resources:` (which itself sits at 10) |
| `envFrom` | `configMapRef` with the fullname; then `with .Values.secretName` → `secretRef` with `.` | the Secret line exists only when `secretName` is set |
| `env` | the three literal variables from stage 6 | |
| probe paths | `.Values.contextPath` followed by `/actuator/health/liveness` or `/readiness`; port `.Values.port` | |
| `lifecycle` | literal | |
| `serviceAccountName` | fullname, inside `if .Values.serviceAccount.create` | |

### `templates/service.yml`

`ClusterIP`, selector `app: <fullname>`, `port` and `targetPort` both `.Values.port`.

### `templates/serviceaccount.yml`

Inside `if .Values.serviceAccount.create`: a ServiceAccount named with the fullname. Nothing uses it on kind. On EKS (THANG-3), AWS gives a Pod an IAM role based on this name.

### `templates/hpa.yml`

Inside `if .Values.autoscaling.enabled`: `autoscaling/v2` `HorizontalPodAutoscaler`, named with the fullname; `scaleTargetRef` = `apps/v1` Deployment with the fullname; `minReplicas`, `maxReplicas` from values; one metric of `type: Resource`, `name: cpu`, target `type: Utilization`, `averageUtilization: .Values.autoscaling.targetCPU`.

### Values per service

| Key | `order-dev.yml` | `payment-dev.yml` |
|---|---|---|
| `image.repository` | `order-service` | `payment-service` |
| `port` | `8081` | `8082` |
| `contextPath` | `/api/orders` | `/api/payments` |
| `env.SPRING_DATASOURCE_URL` | `jdbc:postgresql://order-db:5432/orderdb` | `jdbc:postgresql://payment-db:5432/paymentdb` |
| `env.SPRING_DATASOURCE_USERNAME` | `order_user` | `payment_user` |
| `env.SPRING_DATA_REDIS_HOST` | `order-redis` | `payment-redis` |
| `env.SPRING_DATA_REDIS_PORT` | — | `"6379"` |
| `secretName` | `order-service-secret` | `payment-service-secret` |

Payment needs the Redis port because its `application.yaml` says `6380`, the port its local `docker-compose.yml` maps to avoid clashing with order's Redis. In the cluster each Redis listens on 6379.

`*-prod.yml` = the dev file plus: `replicaCount: 4`; `resources.requests` `cpu: 500m`, `memory: 1Gi`; `resources.limits.memory: 1536Mi`; `autoscaling` `enabled: true`, `minReplicas: 4`, `maxReplicas: 10`. They are too big for kind; you only render them.

### Commands

```bash
helm lint charts/spring-service -f charts/spring-service/values/order-dev.yml
helm template order-service charts/spring-service -f charts/spring-service/values/order-dev.yml
helm template order-service charts/spring-service -f charts/spring-service/values/order-prod.yml | grep -A3 'kind: HorizontalPodAutoscaler'
```

Compare the dev output with your three scratch files. Then hand the service over to Helm:

```bash
kubectl delete -f k8s/scratch/order-service/
helm upgrade --install order-service charts/spring-service -n dev -f charts/spring-service/values/order-dev.yml --set image.tag=v1
helm upgrade --install payment-service charts/spring-service -n dev -f charts/spring-service/values/payment-dev.yml --set image.tag=v1
helm list -n dev
kubectl get deploy,svc,cm,sa -n dev
```

### Check

- Both releases `deployed`; 4 app Pods Ready.
- `helm upgrade` with `--set image.tag=v2` then `helm history order-service -n dev` shows 2 revisions; `helm rollback order-service 1 -n dev` returns to v1.

### Typical errors

| Symptom | Cause |
|---|---|
| `invalid ownership metadata` / `exists and cannot be imported into the current release` | the scratch objects still exist; Helm does not adopt objects created by `kubectl` |
| `image.repository is required` | values file not passed with `-f` |
| `nil pointer evaluating interface {}.enabled` | `autoscaling` missing from `values.yaml` |
| `cannot unmarshal number into Go struct field ConfigMap.data of type string` | a value without `quote` |
| YAML parse error near `resources` | wrong number in `nindent` |

### Concepts

- Values precedence: `values.yaml` < each `-f` file in order < `--set`.
- A release is one install of a chart; each upgrade is a new revision.
- Template functions used: `include`, `required`, `quote`, `toYaml`, `nindent`, `with`, `range`, `if`, `sha256sum`, `trunc`, `trimSuffix`.

---

## Stage 8 — metrics-server

**Goal:** CPU and memory numbers for Pods, which the HPA needs.

**File:** `k8s/metrics-server-patch.json`, a JSON Patch: an array with one object, `op: add`, `path: /spec/template/spec/containers/0/args/-` (append to the args list), `value: --kubelet-insecure-tls`.

**Why the patch:** metrics-server reads each kubelet over HTTPS. kind kubelets use self-signed certificates, so without this flag every scrape fails. Real clusters (EKS) do not need it.

### Commands

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.1/components.yaml
kubectl patch deploy metrics-server -n kube-system --type=json --patch-file k8s/metrics-server-patch.json
kubectl rollout status deploy/metrics-server -n kube-system
```

### Check

After about a minute, `kubectl top nodes` and `kubectl top pods -n dev` print numbers.

Patching twice appends the flag twice. `cluster-up.sh` (stage 13) checks the args first.

---

## Stage 9 — Gateway API (Envoy Gateway)

**Goal:** one entry point that routes `/api/orders*` and `/api/payments*` to the right Service.

**Why:** in THANG-1, the ALB had listener rules that sent `/api/orders*` to the order target group. The Gateway API splits the same thing into three objects:

| THANG-1 | Gateway API | Who owns it |
|---|---|---|
| "we use ALB" | `GatewayClass eg` — which controller implements gateways | cluster admin |
| the ALB and its listener on port 80 | `Gateway web` | platform team |
| listener rules → target groups | `HTTPRoute api` → Services | app team |

Envoy Gateway is the controller. When it sees the `Gateway`, it creates an Envoy proxy Deployment and a `LoadBalancer` Service for it in `envoy-gateway-system`.

### Install the controller

Installs the Gateway API CRDs and the controller.

```bash
helm upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version v1.9.2 \
  -n envoy-gateway-system --create-namespace --wait
```

### File: `k8s/gateway.yml`

Three objects separated by `---`, all `apiVersion: gateway.networking.k8s.io/v1`.

| Object | Fields |
|---|---|
| `GatewayClass` | name `eg` (cluster-scoped, no namespace); `spec.controllerName: gateway.envoyproxy.io/gatewayclass-controller` |
| `Gateway` | name `web`, namespace `dev`; `gatewayClassName: eg`; one listener: `name: http`, `protocol: HTTP`, `port: 80` |
| `HTTPRoute` | name `api`, namespace `dev`; `parentRefs: [{ name: web }]`; rules below |

| Rule `matches` | `backendRefs` |
|---|---|
| `path: { type: PathPrefix, value: /api/orders }` | `{ name: order-service, port: 8081 }` |
| `path: { type: PathPrefix, value: /api/payments }` | `{ name: payment-service, port: 8082 }` |

THANG-3 adds `/api/inventory` (8083) and `/api/notifications` (8084).

### Commands

```bash
kubectl apply -f k8s/gateway.yml
kubectl wait --for=condition=Accepted gateway/web -n dev --timeout=2m
svc=$(kubectl get svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web -o name)
kubectl port-forward -n envoy-gateway-system "$svc" 8090:80
```

Keep the port-forward running; use a second terminal.

### Check

```bash
curl -s localhost:8090/api/orders/actuator/health
curl -s localhost:8090/api/payments/ | jq length
curl -si localhost:8090/api/orders | head -3
curl -si localhost:8090/nothing | head -1
```

| Command | Expected |
|---|---|
| health | `{"status":"UP"}` |
| payments list | a number (seeded rows) |
| `/api/orders` without trailing slash | `302` to `/api/orders/`: Spring redirects to the context root. Scripts always use the trailing slash |
| `/nothing` | `404` from Envoy: no rule matches |

### Typical errors

| Symptom | Cause |
|---|---|
| Gateway `Programmed: False`, address pending | normal on kind: there is no cloud to give the Service an IP. Do not wait for `Programmed` here; EKS gives it a real NLB in THANG-3 |
| 500 or 503 for one path | `kubectl describe httproute api -n dev`: `ResolvedRefs: False` means a Service name or port is wrong |
| `no matches for kind "Gateway"` | the controller (and its CRDs) is not installed yet |

---

## Stage 10 — HorizontalPodAutoscaler

**Goal:** see a Deployment add Pods under CPU load and remove them afterwards.

No new file: `templates/hpa.yml` from stage 7 already exists. The dev values keep it off (fixed 2 replicas); turn it on for this stage only.

```bash
helm upgrade --install order-service charts/spring-service -n dev \
  -f charts/spring-service/values/order-dev.yml --set image.tag=v1 --set autoscaling.enabled=true
kubectl get hpa -n dev
```

### Generate load

```bash
kubectl run load -n dev --image=busybox:1.36 --restart=Never -- /bin/sh -c \
  'while true; do wget -q -O- http://order-service:8081/api/orders/actuator/health >/dev/null; done'
kubectl get hpa -n dev -w
```

If CPU stays under 50 %, start two or three more load Pods (`load2`, `load3`). Delete them when done:

```bash
kubectl delete pod -n dev load load2 load3 --ignore-not-found
```

### Check

- `TARGETS` shows a real percentage, for example `cpu: 85%/50%`, and `REPLICAS` grows above 2.
- About 5 minutes after the load stops, replicas go back to 2 (scale-down waits so it does not flap).
- Turn it off again by rerunning the `helm upgrade` without `--set autoscaling.enabled=true`.

### Typical errors

| Symptom | Cause |
|---|---|
| `TARGETS <unknown>` | metrics-server not ready, or the container has no CPU request |
| Replicas jump back to 2 after each upgrade | `replicas` rendered while the HPA is on; check the `if` in `deployment.yml` |

### Concepts

- `desired = ceil(current × currentUtilization / target)`. Utilization is relative to the **request** (250m), not to the node.
- Same idea as the THANG-1 ASG target-tracking policy at 60 % CPU, but it adds Pods on existing nodes in seconds instead of booting EC2 instances in minutes.

---

## Stage 11 — Kafka on Kubernetes (Strimzi)

**Goal:** a one-node Kafka cluster named `jaka` in namespace `kafka`, and the topic `order-events`.

**Why:** THANG-3 makes the services talk through events. On AWS that broker would be MSK, which costs money per hour. On Kubernetes an **operator** runs it: a controller that turns a short custom object ("I want a Kafka cluster") into the Pods, disks and Services that make one.

### What Strimzi deploys, bottom up

1. **The operator**: a Deployment `strimzi-cluster-operator` in namespace `kafka`, plus CRDs (`Kafka`, `KafkaNodePool`, `KafkaTopic`, ...).
2. It **watches** those objects through the API server.
3. When `Kafka jaka` and `KafkaNodePool dual-role` exist, it creates: Pod `jaka-dual-role-0` (controller and broker in one process, KRaft mode, no ZooKeeper), PVC `data-0-jaka-dual-role-0`, Services `jaka-kafka-bootstrap` (the address clients use) and `jaka-kafka-brokers`, and the config.
4. It creates the **entity operator** Pod. Its topic operator turns each `KafkaTopic` object into a real topic.

### Install the operator

```bash
curl -sL https://github.com/strimzi/strimzi-kafka-operator/releases/download/1.3.1/strimzi-cluster-operator-1.3.1.yaml \
  | sed 's/namespace: myproject/namespace: kafka/' \
  | kubectl create -f - -n kafka
kubectl wait deploy/strimzi-cluster-operator -n kafka --for=condition=Available --timeout=5m
```

- `sed`: the release file assumes namespace `myproject`; its RoleBindings must point to `kafka`.
- `create`, not `apply`: Strimzi's own docs install with `create`; its CRDs are large and `apply` can fail on the size of its bookkeeping annotation. The script in stage 13 runs this only when the operator is missing.

### File: `k8s/kafka/kafka-single-node.yml`

Take Strimzi's `examples/kafka/kafka-single-node.yaml` **from the release tag you installed** (the `apiVersion` changes between releases). Rename the cluster to `jaka` in **both** places. No `namespace` in the file; you pass `-n kafka`.

`KafkaNodePool`:

| Field | Value | Why |
|---|---|---|
| `apiVersion` | `kafka.strimzi.io/v1` | must match the installed CRDs |
| `metadata.name` | `dual-role` | Pod name becomes `jaka-dual-role-0` |
| `metadata.labels` | `strimzi.io/cluster: jaka` | ties the pool to the cluster. Forgetting it = Kafka never becomes Ready |
| `spec.replicas` | `1` | |
| `spec.roles` | `controller`, `broker` | one process does both |
| `spec.storage` | `type: jbod`, one volume: `id: 0`, `type: persistent-claim`, `size: 5Gi`, `kraftMetadata: shared` | the metadata log shares the data volume |

`Kafka`:

| Field | Value |
|---|---|
| `metadata.name` | `jaka` |
| `spec.kafka.version` / `metadataVersion` | `4.3.1` / `4.3-IV0` (from the example of that release) |
| `spec.kafka.listeners` | `plain`: port 9092, `type: internal`, `tls: false`; `tls`: port 9093, `internal`, `tls: true` |
| `spec.kafka.config` | `offsets.topic.replication.factor: 1`, `transaction.state.log.replication.factor: 1`, `transaction.state.log.min.isr: 1`, `default.replication.factor: 1`, `min.insync.replicas: 1` |
| `spec.entityOperator` | `topicOperator: {}`, `userOperator: {}` |

Every replication setting is 1 because there is one broker. With the usual 3 the topics could never be created.

### File: `k8s/kafka/topics.yml`

One `KafkaTopic` for now (THANG-3 adds five):

| Field | Value |
|---|---|
| `apiVersion` | `kafka.strimzi.io/v1` |
| `metadata` | name `order-events`, namespace `kafka`, label `strimzi.io/cluster: jaka` |
| `spec.partitions` | `3` |
| `spec.replicas` | `1` |
| `spec.config.retention.ms` | `604800000` (7 days) |

### Commands

```bash
kubectl apply -f k8s/kafka/kafka-single-node.yml -n kafka
kubectl wait kafka/jaka -n kafka --for=condition=Ready --timeout=10m
kubectl apply -f k8s/kafka/topics.yml
kubectl get pods,pvc,svc -n kafka
kubectl get kafkatopic -n kafka
```

### Check: produce and consume by hand

Terminal 1, type a few lines, Ctrl+C to quit:

```bash
kubectl run kafka-producer -n kafka -ti --rm --restart=Never --image=quay.io/strimzi/kafka:1.3.1-kafka-4.3.1 -- \
  bin/kafka-console-producer.sh --bootstrap-server jaka-kafka-bootstrap:9092 --topic order-events
```

Terminal 2:

```bash
kubectl run kafka-consumer -n kafka -ti --rm --restart=Never --image=quay.io/strimzi/kafka:1.3.1-kafka-4.3.1 -- \
  bin/kafka-console-consumer.sh --bootstrap-server jaka-kafka-bootstrap:9092 --topic order-events --from-beginning
```

The consumer prints every line. From namespace `dev` the same broker is `jaka-kafka-bootstrap.kafka:9092` (`<service>.<namespace>`).

### Typical errors

| Symptom | Cause |
|---|---|
| `no matches for kind "Kafka" in version ...` | `apiVersion` from a different Strimzi release |
| Kafka stays `NotReady`, no broker Pod | node pool label does not say `jaka` |
| Operator logs `forbidden` | the `sed` for `myproject` was skipped |
| KafkaTopic `NotReady`, replication factor error | `replicas` larger than the number of brokers |

### Concepts

- **Topic, partition, offset.** A topic is split into partitions; each partition is an ordered log; an offset is a position in it. Messages with the same key always go to the same partition, so they stay in order.
- **Consumer group.** Each partition goes to exactly one consumer in a group. 3 partitions → at most 3 useful consumers per group. Each group keeps its own offsets.
- **Replication factor and `min.insync.replicas`.** See `note-taking.md`: production uses RF 3 and min ISR 2.

---

## Stage 12 — The first event

**Goal:** creating an order in order-service makes payment-service log it, through Kafka, across namespaces.

**Where:** the service worktree from stage 0. This is the code you deleted there.

### order-service

| File | Write |
|---|---|
| `build.gradle` | `implementation 'org.springframework.boot:spring-boot-starter-kafka'`; test: `spring-boot-starter-kafka-test`, `org.testcontainers:testcontainers-kafka` |
| `application.yaml` | the `spring.kafka` block below |
| `dto/CreateOrderRequest.java` | record `(String customerName, BigDecimal totalAmount, String currency)` |
| `dto/OrderCreatedEvent.java` | record `(String eventId, Long orderId, String orderNumber, String customerName, BigDecimal totalAmount, String currency, Instant occurredAt)` with `static from(Order)`: new random UUID as `eventId`, the order's fields, `Instant.now()` |
| `publisher/OrderEventPublisher.java` | `@Service`, `@RequiredArgsConstructor`; field `KafkaTemplate<String, OrderCreatedEvent>`; constant topic `order-events`; `publish(event)` sends with **key = `orderId` as string** |
| `service/OrderService.java` | `create(CreateOrderRequest)`: `@Transactional`, `@CacheEvict(value = "orders", allEntries = true)`; save an `Order` (`orderNumber` `"ORD-" + System.currentTimeMillis()`, status `CREATED`, fields from the request, `createdAt` now); then `publisher.publish(OrderCreatedEvent.from(order))`; return the order |
| `controller/OrderController.java` | `@PostMapping`, `@ResponseStatus(HttpStatus.CREATED)`, takes `@RequestBody CreateOrderRequest`, returns `orderService.create(req)` |

`@CacheEvict` matters: `GET /api/orders/` is cached in Redis, so without it the list would not show the new order.

order `spring.kafka`:

| Key | Value | Why |
|---|---|---|
| `bootstrap-servers` | `${KAFKA_BOOTSTRAP:localhost:9092}` | env var in the cluster, localhost when run locally |
| `producer.acks` | `all` | the broker confirms only after all in-sync replicas have it |
| `producer.key-serializer` | `org.apache.kafka.common.serialization.StringSerializer` | |
| `producer.value-serializer` | `org.springframework.kafka.support.serializer.JacksonJsonSerializer` | Boot 4 uses Jackson 3; the old `JsonSerializer` is Jackson 2 |
| `producer.properties.enable.idempotence` | `true` | producer retries never duplicate a message |
| `producer.properties.spring.json.add.type.headers` | `false` | otherwise each message carries the Java class name `com.example.order_service...`, which payment does not have |

### payment-service

| File | Write |
|---|---|
| `build.gradle` | the same three Kafka lines |
| `application.yaml` | the `spring.kafka` block below |
| `dto/OrderCreatedEvent.java` | a **copy** of order's record, same field names and types, in `com.jaka.payment_service.dto`, without `from` |
| `consumer/OrderEventConsumer.java` | `@Component`, `@Slf4j`; method `onOrderCreated(OrderCreatedEvent event)` with `@KafkaListener(topics = "order-events", groupId = "payment-service")`; logs `Received OrderCreated orderId=... orderNumber=... amount=... currency` |

No shared library between services: each keeps its own copy of the event. The event's field names are the contract.

payment `spring.kafka`:

| Key | Value | Why |
|---|---|---|
| `bootstrap-servers` | `${KAFKA_BOOTSTRAP:localhost:9092}` | |
| `consumer.group-id` | `payment-service` | |
| `consumer.auto-offset-reset` | `earliest` | a new group starts from the beginning of the topic |
| `consumer.key-deserializer` | `org.apache.kafka.common.serialization.StringDeserializer` | |
| `consumer.value-deserializer` | `org.springframework.kafka.support.serializer.ErrorHandlingDeserializer` | a message that is not valid JSON is logged and skipped, instead of blocking the partition forever |
| `consumer.properties.spring.deserializer.value.delegate.class` | `org.springframework.kafka.support.serializer.JacksonJsonDeserializer` | the real deserializer, wrapped by the one above |
| `consumer.properties.spring.json.value.default.type` | `com.jaka.payment_service.dto.OrderCreatedEvent` | there is no type header, so say which class to build |
| `consumer.properties.spring.json.trusted.packages` | `com.jaka.payment_service.dto` | |

### Values

Add `KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092` to `env` in `order-dev.yml`, `payment-dev.yml` and both prod files.

### Commands

```bash
cd ../../jaka-services/order-service/order-service && ./gradlew build -x test && cd -
cd ../../jaka-services/payment-service/payment-service && ./gradlew build -x test && cd -
docker build -t order-service:v3 ../../jaka-services/order-service/order-service
docker build -t payment-service:v3 ../../jaka-services/payment-service/payment-service
kind load docker-image order-service:v3 payment-service:v3 --name jaka
helm upgrade --install order-service charts/spring-service -n dev -f charts/spring-service/values/order-dev.yml --set image.tag=v3
helm upgrade --install payment-service charts/spring-service -n dev -f charts/spring-service/values/payment-dev.yml --set image.tag=v3
```

### Check

With the stage 9 port-forward running:

```bash
curl -s -X POST localhost:8090/api/orders/ -H 'Content-Type: application/json' \
  -d '{"customerName":"Tai","totalAmount":120.50,"currency":"USD"}'
kubectl logs -n dev -l app=payment-service --tail=50 | grep 'Received OrderCreated'
```

| Experiment | Expected |
|---|---|
| `kubectl scale deploy payment-service -n dev --replicas=0`, POST 5 orders, scale back to 2 | all 5 are logged: the group's offsets waited in Kafka |
| scale payment to 3 replicas | each Pod logs `partitions assigned` with one partition |
| scale payment to 4 | one Pod gets no partition and stays idle |

### The flaw you just built (THANG-3 fixes it)

`create` writes to Postgres and sends to Kafka in the same method, but they are two systems with no shared transaction:

| What fails | Result |
|---|---|
| Kafka goes down while the app runs | `send()` only queues the message and returns, so the order commits; delivery fails in the background up to 2 minutes later (`delivery.timeout.ms`), nobody looks at the result: the order exists, the event is lost |
| Kafka unreachable from the start | `send()` blocks up to 60 s (`max.block.ms`) waiting for topic metadata, then throws: the order is rolled back and the caller waits a minute for a 500 |
| The DB transaction rolls back after the send (for example the commit fails) | an event for an order that does not exist |
| payment receives a message twice (rebalance, retry) | it handles it twice; nothing deduplicates |

### Typical errors

| Symptom | Cause |
|---|---|
| No `KafkaTemplate` bean | only `spring-kafka` was added; Boot 4 needs `spring-boot-starter-kafka` for auto-configuration |
| payment: `ClassNotFoundException: com.example.order_service...` | type headers still on in order |
| payment: `The class ... is not in the trusted packages` | `trusted.packages` missing or wrong |
| `UnknownHostException: jaka-kafka-bootstrap.kafka` | namespace part missing, or Kafka not Ready |

---

## Stage 13 — One command: `cluster-up.sh`

**Goal:** the whole of stages 1–12 from one command, safe to run again and again.

**Files:** `scripts/cluster-up.sh`, `scripts/cluster-down.sh`. Make both executable (`chmod +x scripts/*.sh`).

### `scripts/cluster-down.sh`

A bash shebang and one command: `kind delete cluster --name jaka`.

### `scripts/cluster-up.sh`, in typing order

**Header**

| Line | Why |
|---|---|
| `#!/usr/bin/env bash` | |
| `set -euo pipefail` | stop on any error, on unset variables, and on failures inside pipes |
| `cd "$(dirname "$0")/.."` | run from `jaka_manual/` wherever you call it from |
| if `.env` exists: `set -a; source .env; set +a` | load the variables and export them |
| `services=(order payment)` | THANG-3 adds `inventory notification` |
| `app_root=${APP_ROOT:-..}` | where the service folders are (the reference hardcodes `..`) |
| `tag=${TAG:-dev-$(date +%s)}` | a new tag on every run, so `IfNotPresent` always picks the new image |

**0. Fail early.** For each service and each suffix `DB_PASS`, `DB_ADMIN_PASS`: build the name `${svc^^}_${suffix}` (`^^` = uppercase), read it with indirect expansion `${!name:-}`, and exit with `missing env <name>` if empty. Nothing gets created when a password is missing.

**1. Cluster.** `kind get clusters | grep -qx jaka` or else `kind create cluster --config kind-config.yaml`.

**1b. Preload third-party images.** For each image: `docker pull` if not local, `docker save --platform linux/amd64` to a tar under `${TMPDIR:-/tmp}/kind-images` (name: the image with `/` and `:` replaced by `_`), then `kind load image-archive`. Kind nodes pull slowly, and `kind load docker-image` fails with multi-platform images on recent Docker.

| Image | For |
|---|---|
| `postgres:16` | DBs and Jobs |
| `redis:7-alpine` | Redis |
| `quay.io/strimzi/kafka:1.3.1-kafka-4.3.1` | the broker |
| `quay.io/strimzi/operator:1.3.1` | the operator |
| `registry.k8s.io/metrics-server/metrics-server:v0.9.1` | metrics-server |
| `docker.io/envoyproxy/gateway:v1.9.2` | the Envoy Gateway controller |

**2. Service images.** For each service: `docker build -t "$svc-service:$tag" "$app_root/$svc-service/$svc-service"` then `kind load docker-image`.

**3. Namespaces and Secrets.** The stage 2 commands, in a loop. For the app variable name use `${up}_DB_PASS` and read it with `${!app_var}`.

**4. Add-ons, versions pinned** (a "latest" URL works today and breaks next month):

- metrics-server: `kubectl apply` the v0.9.1 URL; patch only if `kubectl get deploy metrics-server -n kube-system -o jsonpath='{.spec.template.spec.containers[0].args}'` does not contain `kubelet-insecure-tls`.
- Envoy Gateway: the stage 9 `helm upgrade --install`.
- Strimzi: run the stage 11 `curl | sed | kubectl create` only if `kubectl get deploy strimzi-cluster-operator -n kafka --ignore-not-found -o name` prints nothing; then `kubectl wait` for it.

**5. Kafka.** Apply the cluster file with `-n kafka`, wait for `Ready` (10 min), apply `topics.yml`.

**6. Data.** For each service `kubectl apply -f "k8s/$svc-service/"` (DB, Redis, Job), then `kubectl rollout status statefulset/$svc-db`.

**7. Apps.** For each service: `helm upgrade --install "$svc-service" charts/spring-service -n dev -f "charts/spring-service/values/$svc-dev.yml" --set image.tag="$tag"`.

**8. Gateway.** Apply `k8s/gateway.yml`; wait for `Accepted` on `gateway/web`; `kubectl wait --for=condition=Available deploy --all -n dev --timeout=10m`; print the two port-forward lines from stage 9.

**9. Clean old images on the nodes.** Each run loads a new tag, so old ones pile up on the nodes' disks. For each node (`jaka-control-plane`, `jaka-worker`, `jaka-worker2`): `docker exec <node> crictl images -o json`, take `.images[].repoTags[]` with `jq -r`, keep only `/(order|payment)-service:dev-` tags, drop the current tag, and pass the rest to `docker exec <node> crictl rmi` through `xargs -r`; end with `|| true` so a failed cleanup never fails the script.

### Check

```bash
bash -n scripts/cluster-up.sh
./scripts/cluster-down.sh
./scripts/cluster-up.sh
./scripts/cluster-up.sh
```

- The first run takes 10–15 minutes and ends with the port-forward lines.
- The second run finishes without errors and only rolls the apps to a new tag.
- With the port-forward, the stage 9 and stage 12 checks pass.

### Typical errors

| Symptom | Cause |
|---|---|
| `missing env ORDER_DB_PASS` | `.env` missing or in a different folder |
| `unbound variable` | a typo in a variable name (`set -u` catches it) |
| `field is immutable` on a Job | you changed the Job while the previous one still exists (it deletes itself 5 minutes after finishing) |
| `another operation (install/upgrade/rollback) is in progress` | a previous run was interrupted during `helm`; `helm rollback <release> -n dev` or `helm uninstall` |

---

## Appendix A — Definition of done

| Check | Passes when |
|---|---|
| One command | `./scripts/cluster-down.sh && ./scripts/cluster-up.sh` ends without errors; a second `cluster-up.sh` too |
| Pods | `kubectl get pods -n dev` shows 2 DBs, 2 Redis, 4 app Pods Running and Ready; Jobs `Completed` |
| Gateway | the stage 9 `curl` table matches |
| Persistence | the stage 3 delete-the-Pod test keeps the row |
| Rolling update | `helm upgrade --set image.tag=...` keeps the service answering; `helm rollback` works |
| HPA | `TARGETS` shows a percentage and replicas grow under load |
| Kafka | the stage 12 POST is logged by payment; the scale-to-zero experiment receives all events |
| Chart | `helm lint` passes for all four values files |

## Appendix B — Object inventory per file

| File | Objects |
|---|---|
| `kind-config.yaml` | 1 kind `Cluster` (3 nodes) |
| `k8s/<svc>-service/<svc>-db.yml` | Service (headless), StatefulSet |
| `k8s/<svc>-service/<svc>-redis.yml` | Service (headless), StatefulSet |
| `k8s/<svc>-service/create-app-role-job.yml` | Job |
| `k8s/metrics-server-patch.json` | 1 JSON Patch operation |
| `k8s/gateway.yml` | GatewayClass, Gateway, HTTPRoute (2 rules) |
| `k8s/kafka/kafka-single-node.yml` | KafkaNodePool, Kafka |
| `k8s/kafka/topics.yml` | 1 KafkaTopic |
| chart, per release | ConfigMap, Deployment, Service, ServiceAccount (+ HPA when enabled) |
| created by the scripts | 2 Namespaces, 4 Secrets |

```bash
kubectl get all,cm,secret,pvc,sa,jobs,hpa,gateway,httproute -n dev
kubectl get kafka,kafkanodepool,kafkatopic,pods,pvc -n kafka
```

## Appendix C — Concepts by stage

| Concept | Stage |
|---|---|
| Control plane vs node, kubelet, scheduler, controller loops | 1 |
| Namespace, Secret, `--dry-run=client \| apply` idempotency | 2 |
| StatefulSet, headless Service, PV/PVC/StorageClass | 3 |
| Job, `backoffLimit`, `ttlSecondsAfterFinished`, immutable Pod template | 4 |
| Spring relaxed binding, image loading | 5 |
| Pod → ReplicaSet → Deployment, labels and selectors, Service, probes, requests and limits | 6 |
| Helm: chart, values, release, revision, template functions | 7 |
| JSON Patch | 8 |
| Gateway API: GatewayClass, Gateway, HTTPRoute | 9 |
| HPA formula, metrics-server | 10 |
| Operator pattern, CRDs, KRaft, topics, partitions, consumer groups | 11 |
| Producer/consumer config, keys and ordering, the dual-write problem | 12 |
| Idempotent shell scripts, pinned versions | 13 |

## Appendix D — Names, ports and DNS

| Service | Port | Context path | DB Service / database | App user / admin | Redis Service |
|---|---|---|---|---|---|
| order | 8081 | `/api/orders` | `order-db` / `orderdb` | `order_user` / `order_admin` | `order-redis:6379` |
| payment | 8082 | `/api/payments` | `payment-db` / `paymentdb` | `payment_user` / `payment_admin` | `payment-redis:6379` |

| Item | Pattern |
|---|---|
| Release, Deployment, Service, ConfigMap, ServiceAccount | `<svc>-service` |
| Pod label | `app: <svc>-service` |
| App Secret / key | `<svc>-service-secret` / `SPRING_DATASOURCE_PASSWORD` |
| Admin Secret / key | `<svc>-db-admin` / `password` |
| Role Job | `<svc>-db-create-app-role` |
| DB Pod / PVC | `<svc>-db-0` / `data-<svc>-db-0` |
| Same-namespace DNS | `<service>` |
| Other-namespace DNS | `<service>.<namespace>` (full form `<service>.<namespace>.svc.cluster.local`) |
| Kafka bootstrap | `jaka-kafka-bootstrap.kafka:9092` |
| Gateway access on kind | `kubectl port-forward` to local port 8090 |
| Image tags | `v1`, `v2`, … by hand; `dev-<epoch>` from the script |

## Appendix E — Debug loop

The reference keeps this in `k8s/troubleshooting.md`:

```bash
kubectl get pods -n dev
kubectl describe pod <pod> -n dev                 # Events at the bottom
kubectl logs <pod> -n dev --previous              # logs of the crashed run
kubectl exec -it <pod> -n dev -- sh
kubectl get events -n dev --sort-by=.lastTimestamp
```

| Pod status | First look at |
|---|---|
| `Pending` | `describe` Events: no node has enough CPU/RAM, or a PVC is not bound |
| `CreateContainerConfigError` | a Secret or ConfigMap (or key) it references does not exist |
| `ErrImagePull` / `ImagePullBackOff` | image not loaded, wrong tag |
| `CrashLoopBackOff` | `logs --previous`: usually DB login or a wrong URL |
| `Running` but `0/1` Ready | the readiness probe: path, port, or the DB is down |
| `OOMKilled` | memory limit vs heap |

## Appendix F — Improvements to make while rebuilding

Weaknesses in the reference. Cheaper to fix as you type.

| # | Change | Stage |
|---|---|---|
| 1 | Drop the unused `extraPortMappings` (30080 → 8080) from `kind-config.yaml`, or use it with a NodePort instead of port-forward | 1 |
| 2 | Read `APP_ROOT` instead of hardcoding `app_root=..` | 13 |
| 3 | Put the cluster name in one variable; get node names with `kind get nodes --name "$cluster"` instead of hardcoding three names | 13 |
| 4 | `kubectl wait --for=condition=Complete job/<svc>-db-create-app-role` before the `helm` step, so apps never start before their DB user exists (today an app can fail its first start on login and recover after a restart) | 13 |
| 5 | Commit `metrics-server` `components.yaml` into `k8s/` instead of downloading it on every run | 8, 13 |
| 6 | Remove the unused `tls` listener and `userOperator` from the Kafka file to save memory | 11 |
| 7 | Add `topologySpreadConstraints` (or pod anti-affinity) to the chart, so the 2 replicas never land on the same node | 7 |
| 8 | Move probe timings and the rollout strategy into `values.yaml` | 7 |
| 9 | Give kind's Redis a password, so local config matches EKS (where Redis has an AUTH token) | 3 |
| 10 | Delete `k8s/scripts.ps1`; it is PowerShell from before the move to Linux | — |
| 11 | Put `SPRING_DATA_REDIS_PORT: "6379"` in `payment-prod.yml` too: the reference has it only in `payment-dev.yml`, so a prod install would connect to 6380 and fail | 7 |

## Appendix G — Not covered

- **inventory-service and notification-service**, the outbox, idempotent consumers, dead-letter topics, CQRS, the Saga: THANG-3.
- **EKS**: THANG-3, part B.
- **Ingress**: the Gateway API is its successor; this build uses only the Gateway.
- **NetworkPolicy, RBAC for people, TLS on the Gateway, database backups**: not built in any month so far.
- **CI/CD and monitoring** (GitHub Actions to cluster, ArgoCD, Prometheus, Grafana): month 4.
