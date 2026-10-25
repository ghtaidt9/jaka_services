# Local Kubernetes setup — order-service + payment-service on kind

How to stand up the whole local stack from a fresh clone: a 3-node kind cluster running both Spring Boot services (via the Helm chart), their Postgres and Redis, Kafka (Strimzi), and an Envoy Gateway in front. No AWS account needed.

Commands are **bash on Linux**. Windows notes are at the end.

---

## Contents

- [TL;DR](#tldr)
- [What you end up with](#what-you-end-up-with)
- [Prerequisites](#prerequisites)
- [Step 0 — Install tools](#step-0--install-tools)
- [Step 1 — Create the cluster](#step-1--create-the-cluster)
- [Step 2 — Cluster add-ons](#step-2--cluster-add-ons)
- [Step 3 — Namespace and Secrets](#step-3--namespace-and-secrets)
- [Step 4 — Postgres, Redis and DB roles](#step-4--postgres-redis-and-db-roles)
- [Step 5 — Kafka](#step-5--kafka)
- [Step 6 — Build and load the app images](#step-6--build-and-load-the-app-images)
- [Step 7 — Install the services with Helm](#step-7--install-the-services-with-helm)
- [Step 8 — Gateway and end-to-end test](#step-8--gateway-and-end-to-end-test)
- [Verify everything](#verify-everything)
- [Day-to-day](#day-to-day)
- [Troubleshooting](#troubleshooting)
- [Windows notes](#windows-notes)

---

## TL;DR

All manifests live in the repo; nothing about the cluster is stored anywhere else. Secrets are **not** committed, so you create them by hand (step 3). Databases start empty and the services seed sample data on first boot.

| Step | What | Roughly |
|---|---|---|
| 0 | Install kind + helm | 2 min |
| 1 | kind cluster `jaka` (1 control plane, 2 workers) | 1 min |
| 2 | metrics-server, Envoy Gateway | 3 min |
| 3 | Namespace `dev` + 4 Secrets | 1 min |
| 4 | Postgres + Redis StatefulSets, Jobs that create `order_user`/`payment_user` | 3 min |
| 5 | Strimzi operator, Kafka cluster `jaka`, topic `order-events` | 5 min |
| 6 | Build `order-service:v3` / `payment-service:v3`, load into kind | 5–10 min |
| 7 | `helm upgrade --install` both services | 2 min |
| 8 | Gateway + port-forward + POST an order, see payment-service consume it | 2 min |

---

## What you end up with

```
kind cluster "jaka"  (3 Docker containers)
├─ namespace dev
│   ├─ order-service    (Helm release, 2 Pods, :8081, /api/orders)    ── produces ──┐
│   ├─ payment-service  (Helm release, 2 Pods, :8082, /api/payments)  ◄─ consumes ──┤
│   ├─ order-db, payment-db       (Postgres StatefulSets + PVC)                     │
│   ├─ order-redis, payment-redis (Redis StatefulSets + PVC)                        │
│   └─ Gateway "web" + HTTPRoute "api"                                              │
├─ namespace kafka                                                                  │
│   ├─ strimzi-cluster-operator, jaka-entity-operator                               │
│   └─ Kafka "jaka" (1 broker: jaka-dual-role-0), topic order-events ◄─────────────┘
├─ namespace envoy-gateway-system   (Envoy Gateway controller + Envoy proxy)
└─ namespace kube-system            (metrics-server, ...)
```

| Path in repo | Used in step |
|---|---|
| `jaka_infrastructure/kind-config.yaml` | 1 |
| `jaka_infrastructure/k8s/metrics-server-patch.json` | 2 |
| `jaka_infrastructure/k8s/order-service/`, `k8s/payment-service/` | 4 |
| `jaka_infrastructure/k8s/kafka/` | 5 |
| `order-service/order-service/Dockerfile`, `payment-service/payment-service/Dockerfile` | 6 |
| `jaka_infrastructure/charts/spring-service/` | 7 |
| `jaka_infrastructure/k8s/gateway.yml` | 8 |

---

## Prerequisites

| Tool | Version used | Check |
|---|---|---|
| Docker (daemon running, your user in the `docker` group) | 29.x | `docker info` |
| kubectl | 1.37 | `kubectl version --client` |
| kind | 0.33.0 | step 0 |
| helm | 3.x or 4.x | step 0 |
| ~8 GB RAM and ~30 GB free disk for Docker | | |

Java is **not** needed: images are built with the multi-stage Dockerfiles, which bring their own JDK 17.

---

## Step 0 — Install tools

```bash
curl -fsSLo /tmp/kind https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64
sudo install -m 0755 /tmp/kind /usr/local/bin/kind
kind version
```

Use the GitHub Releases URL above. If a download link 404s, `curl` without `-f` saves the error page as the binary, and running it fails with `line 1: Not: command not found`.

Helm (Fedora shown; use your distro's package manager or the official install script):

```bash
sudo dnf install -y helm
helm version
```

kind on Linux needs higher inotify limits once many Pods run, otherwise Pods fail with "too many open files":

```bash
echo -e "fs.inotify.max_user_watches=524288\nfs.inotify.max_user_instances=512" | sudo tee /etc/sysctl.d/99-kind.conf
sudo sysctl --system
```

---

## Step 1 — Create the cluster

All remaining commands run from `jaka_infrastructure/` unless stated otherwise.

```bash
cd jaka_infrastructure
kind create cluster --config kind-config.yaml
kubectl get nodes
```

Expect 3 nodes `Ready`: `jaka-control-plane`, `jaka-worker`, `jaka-worker2`. kind also sets your kubectl context to `kind-jaka`.

---

## Step 2 — Cluster add-ons

metrics-server (needed by `kubectl top` and HPA). The patch adds `--kubelet-insecure-tls`, required on kind only; apply it once:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl patch deploy metrics-server -n kube-system --type=json --patch-file k8s/metrics-server-patch.json
kubectl rollout status deploy/metrics-server -n kube-system
```

Envoy Gateway (installs the Gateway API CRDs too):

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm --version v1.9.2 -n envoy-gateway-system --create-namespace --wait
```

---

## Step 3 — Namespace and Secrets

Secrets are deliberately not in Git. These are local-only demo passwords; change them if you like, but keep the same names and keys.

```bash
kubectl create namespace dev
kubectl create secret generic order-service-secret   -n dev --from-literal=SPRING_DATASOURCE_PASSWORD=order_pass
kubectl create secret generic payment-service-secret -n dev --from-literal=SPRING_DATASOURCE_PASSWORD=payment_pass
kubectl create secret generic order-db-admin   -n dev --from-literal=password=order_admin_pass
kubectl create secret generic payment-db-admin -n dev --from-literal=password=payment_admin_pass
```

| Secret | Used by |
|---|---|
| `order-db-admin`, `payment-db-admin` | Postgres StatefulSets (`POSTGRES_PASSWORD` for `*_admin`) and the role Jobs |
| `order-service-secret`, `payment-service-secret` | The apps (`envFrom`) and the role Jobs (password set on `*_user`) |

---

## Step 4 — Postgres, Redis and DB roles

Each folder holds the service's Postgres StatefulSet, Redis StatefulSet, and a Job that creates the least-privilege app login (`order_user` / `payment_user`).

```bash
kubectl apply -f k8s/order-service/
kubectl apply -f k8s/payment-service/
kubectl rollout status statefulset/order-db statefulset/payment-db -n dev --timeout=5m
kubectl wait --for=condition=Complete job --all -n dev --timeout=5m
```

A Job Pod may fail once or twice while Postgres is still starting (`could not translate host name`); the Job retries on its own.

---

## Step 5 — Kafka

```bash
kubectl create namespace kafka
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
kubectl wait deploy/strimzi-cluster-operator -n kafka --for=condition=Available --timeout=5m
kubectl apply -f k8s/kafka/kafka-single-node.yml -n kafka
kubectl wait kafka/jaka -n kafka --for=condition=Ready --timeout=10m
kubectl apply -f k8s/kafka/topics.yml
kubectl get kafka,kafkatopic -n kafka
```

The Strimzi install uses `kubectl create`, not `apply`: its CRDs are too large for `apply`'s annotation. Running it twice reports `AlreadyExists`, which is harmless.

The manifests use `kafka.strimzi.io/v1` (Strimzi 1.x). If a future Strimzi version changes the API, check with:

```bash
kubectl get crd kafkas.kafka.strimzi.io -o jsonpath='{.spec.versions[*].name}'
```

---

## Step 6 — Build and load the app images

kind nodes can't see images in your local Docker, so every image must be loaded with `kind load`.

```bash
cd ../order-service/order-service
docker build -t order-service:v3 .
kind load docker-image order-service:v3 --name jaka

cd ../../payment-service/payment-service
docker build -t payment-service:v3 .
kind load docker-image payment-service:v3 --name jaka

cd ../../jaka_infrastructure
```

Use a **new tag** whenever the code changes (`v4`, `v5`, ...). The chart uses `imagePullPolicy: IfNotPresent`, so reusing a tag that kind already has keeps the old image.

---

## Step 7 — Install the services with Helm

One shared chart, one values file per service. Double-check that each release gets **its own** values file.

```bash
helm upgrade --install order-service   charts/spring-service -n dev -f charts/spring-service/values/order-dev.yml   --set image.tag=v3
helm upgrade --install payment-service charts/spring-service -n dev -f charts/spring-service/values/payment-dev.yml --set image.tag=v3
kubectl rollout status deploy/order-service deploy/payment-service -n dev --timeout=5m
```

Preview what Helm will create, without touching the cluster:

```bash
helm template order-service charts/spring-service -f charts/spring-service/values/order-dev.yml
```

---

## Step 8 — Gateway and end-to-end test

```bash
kubectl apply -f k8s/gateway.yml
kubectl wait --for=condition=Accepted gateway/web -n dev --timeout=2m
```

On kind the Gateway stays `Programmed=False` (`AddressNotAssigned`) because there is no cloud load balancer. That's expected; reach Envoy through a port-forward instead. Keep this terminal open:

```bash
svc=$(kubectl get svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web -o name)
kubectl port-forward -n envoy-gateway-system $svc 8090:80
```

In a second terminal:

```bash
curl -s http://localhost:8090/api/orders/actuator/health
curl -s http://localhost:8090/api/payments/actuator/health
curl -i -X POST http://localhost:8090/api/orders/ -H 'Content-Type: application/json' -d '{"customerName":"Tai","totalAmount":120.50,"currency":"USD"}'
kubectl logs -n dev -l app=payment-service --tail=20
```

Expected: both health checks `UP`, the POST returns `201 Created`, and payment-service logs `Received OrderCreated orderId=...`.

Don't use local port 8080: `kind-config.yaml` maps host 8080 to the control-plane node.

---

## Verify everything

| Check | Command | Expected |
|---|---|---|
| Nodes | `kubectl get nodes` | 3 × `Ready` |
| Data layer | `kubectl get statefulset,job -n dev` | 4 StatefulSets `1/1`, 2 Jobs `Complete` (Jobs disappear 5 min after finishing) |
| Apps | `helm list -n dev` | `order-service`, `payment-service` → `deployed` |
| Images | `kubectl get deploy -n dev -o wide` | `order-service:v3`, `payment-service:v3` |
| Kafka | `kubectl get kafka,kafkatopic -n kafka` | `jaka` and `order-events` `Ready` |
| Metrics | `kubectl top pods -n dev` | CPU/memory per Pod |
| End to end | step 8 | `201` + `Received OrderCreated` |

---

## Day-to-day

The cluster survives reboots: when Docker starts, the three `jaka-*` containers start again.

```bash
kubectl config use-context kind-jaka      # if kubectl points elsewhere
docker start jaka-control-plane jaka-worker jaka-worker2   # if they were stopped
```

Deploy a code change:

```bash
docker build -t order-service:v4 .
kind load docker-image order-service:v4 --name jaka
helm upgrade order-service charts/spring-service -n dev -f charts/spring-service/values/order-dev.yml --set image.tag=v4
helm rollback order-service -n dev        # back to the previous revision if needed
```

Start over from scratch (deletes all cluster data):

```bash
kind delete cluster --name jaka
```

Keep Docker's disk in check, since build cache grows with every image build:

```bash
docker system df
docker builder prune -f --filter "until=168h"
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `kubectl` → `localhost:8080 ... EOF` | No current context (cluster creation didn't finish) | `kind export kubeconfig --name jaka`, or delete and recreate the cluster |
| Pod `ImagePullBackOff` | Image not loaded into kind, or wrong tag | `kind load docker-image <img>:<tag> --name jaka` |
| Pod `CreateContainerConfigError` | Missing Secret/ConfigMap | Redo step 3; `kubectl describe pod` names the missing one |
| App `CrashLoopBackOff`, log `password authentication failed for user "order_user"` | Role Job hasn't run | `kubectl get jobs -n dev`; re-run step 4, then `kubectl rollout restart deploy/order-service -n dev` |
| `Error from server ... "invalid ownership metadata"` on `helm install` | An object with the same name was created by `kubectl` | Delete that Deployment/Service/ConfigMap, then install again |
| `/api/payments` returns 500 through the Gateway | Backend Service missing or on the wrong port | `kubectl describe httproute api -n dev` → `ResolvedRefs`; `kubectl get svc -n dev` |
| A release runs the wrong app | `helm upgrade` with the other service's values file | `helm get values <release> -n dev`; upgrade again with the right `-f` |
| `kubectl wait ... Programmed` never finishes | Expected on kind (no load balancer) | Wait for `Accepted` and port-forward (step 8) |
| `port-forward` → `address already in use` | Port taken (8080 by kind, 8081 by a local `bootRun`) | Use another local port |
| payment-service log `RecordDeserializationException ... offset N` | A non-JSON message in `order-events` (e.g. typed in the console producer) | Already handled by `ErrorHandlingDeserializer`; rebuild if you run an older image |
| Envoy / controllers keep restarting, `leader election lost` | Docker or the host was slow (often right after sleep/resume) | Wait a few minutes; if it persists, restart Docker |
| `kubectl -l strimzi.io/kind=kafka` finds nothing | Label values are case-sensitive | Use `strimzi.io/kind=Kafka` |

---

## Windows notes

The same steps work on Windows with Docker Desktop, with these differences:

| Linux (this doc) | Windows PowerShell |
|---|---|
| `curl -fsSLo ...` / `sudo install` for kind | `winget install Kubernetes.kind`, `winget install Helm.Helm` |
| `svc=$(kubectl get svc ...)` | `$svc = kubectl get svc ...` |
| `curl -X POST ... -d '{...}'` | `Invoke-RestMethod -Method Post -Uri ... -ContentType 'application/json' -Body '{...}'` |
| `curl` | `curl.exe` (`curl` is an alias for `Invoke-WebRequest`) |
| inotify sysctl | not needed |

Docker Desktop keeps all data in `%LOCALAPPDATA%\Docker\wsl\disk\docker_data.vhdx`, which grows but never shrinks on its own. If drive C: fills up, prune Docker, then quit Docker Desktop, run `wsl --shutdown`, and compact the file with `diskpart` (`select vdisk file=...`, `attach vdisk readonly`, `compact vdisk`, `detach vdisk`). Always run `detach vdisk`, or Docker Desktop fails to start with `disk not found`.
