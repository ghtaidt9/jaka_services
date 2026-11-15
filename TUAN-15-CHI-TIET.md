# WEEK 15 — GitOps with ArgoCD + Metrics with Prometheus/Grafana

> Month 4, week 3 of 4. Series: [TUAN-13](TUAN-13-CHI-TIET.md) → [TUAN-14](TUAN-14-CHI-TIET.md) → **TUAN-15** → [TUAN-16](TUAN-16-CHI-TIET.md).

## TL;DR

Two areas this week:

**First half — GitOps:** flip the deploy model from *push* (week 14) to *pull*:

```
Week 14 (push):  CD deploy job --(helm upgrade, holds cluster credentials)--> EKS

Week 15 (pull):  CI --(edit image.tag, git commit)--> repo jaka_infrastructure
                                                          ▲
                                                          │ polls every 3 minutes
                                                   ArgoCD (sits INSIDE the cluster)
                                                          │ applies + self-heals drift
                                                          ▼
                                                     namespace dev
```

Git becomes the **single source of truth**: what the cluster runs = read the `values/<svc>-eks.yml` files on master. GitHub Actions no longer has any access to the cluster.

**Second half — Metrics:** install kube-prometheus-stack (Prometheus + Grafana), expose `/actuator/prometheus` on the 4 services, let Prometheus find and scrape them through a ServiceMonitor, and look at RPS / error rate / p95 latency in Grafana.

---

## Why change the model (answering question 4 from last week)

The five weaknesses of push-based CD, mapped straight onto what you just built:

| # | Week-14 weakness | Concrete symptom | How GitOps fixes it |
|---|---|---|---|
| 1 | You can't tell which version the cluster runs without `helm get` | You need a kubeconfig to answer "what's running in dev?" | Read `image.tag` in git; anyone can see it |
| 2 | GitHub holds credentials into the cluster | A deploy role + access entry + environment: a whole machinery just so the deploy job can get into the cluster | ArgoCD sits **inside** the cluster and pulls outward; nobody gets in from outside |
| 3 | Nobody notices drift | Someone runs `kubectl set image` by hand → the cluster diverges from git forever | `selfHeal: true`: ArgoCD restores git's state within seconds |
| 4 | Chart changes don't deploy | Edit `deployment.yml` in the infra repo → nothing runs (the workflows only trigger from the app repo) | ArgoCD watches the infra repo; a chart change is synced |
| 5 | Pushing while the cluster is off loses the deploy | `EKS_DEV_UP=false` → job skipped → that image is never deployed automatically | The tag is still written to git; when the cluster comes back, ArgoCD catches up by itself |

> **Trade-off, stated up front:** (1) deploys are no longer "instant": by default ArgoCD polls the repo every **3 minutes** (acceptable; there's a way to force an immediate refresh for demos). (2) CI now needs **write access to the infra repo**, granted with a deploy key scoped to that repo only, which is far narrower than cluster access. (3) One more system to run (ArgoCD), in exchange for removing all of week 14's deploy-role/access-entry machinery.

---

# STAGE A — Remove the week-14 pieces + bigger nodes

## A1. Remove the push-based pieces

Week 14 was a learning stepping stone. Remove it now so you don't have two deploy paths side by side (two live paths are a source of drift):

**App repo:**
- Delete `.github/workflows/deploy-dev.yml`.
- Delete the `deploy-dev` job from each of the 4 service workflows (`order-ci.yml`, `payment-ci.yml`, `inventory-ci.yml`, `notification-ci.yml`). The new `release` job in `_build-service.yml` replaces it (stage B).

**Infra repo:**
- Delete `envs/dev-eks/ci-access.tf` (the access entry is no longer needed).
- Delete `envs/shared/github-deploy.tf` + the `gha_deploy_role_arn` output.
- Delete `scripts/deploy-service.sh` too. Once ArgoCD owns the 4 Deployments, the script can't work. Helm refuses to install a release over resources it didn't create (`invalid ownership metadata`): ArgoCD renders the chart with `helm template` and applies the result itself, so those resources carry no Helm release annotations. Even if Helm did deploy, `selfHeal` (C4) would put git's version back.

**GitHub variables:**

```bash
cd /home/taidt9/projects/springboot_aws_compute_databases
gh variable delete AWS_DEPLOY_ROLE_ARN
gh variable delete EKS_DEV_UP
```

Then re-apply the 2 Terraform environments (shared first, then dev-eks if it's running).

If dev-eks **is running** with the week-14 Helm releases, remove them before the ApplicationSet (C4) creates the ArgoCD versions. This gives a short outage in `dev`, which is fine:

```bash
for svc in order payment inventory notification; do helm uninstall "$svc-service" -n dev; done
```

## A2. Bigger nodes: t3.medium → t3.large

A purely technical reason you can compute: on EKS (VPC CNI without prefix delegation, which is your setup), each node is **limited in pod count by the instance type's ENIs/IPs**: `max pods = ENIs × (IPs per ENI − 1) + 2`. A `t3.medium` has 3 ENIs × 6 IPs → **17 pods per node**, including system pods like aws-node and kube-proxy. Your cluster is about to carry more:

- ArgoCD: 5 pods. With the C2 values, `helm template` of chart 10.17.2 renders 4 Deployments (`applicationset-controller`, `repo-server`, `server`, `redis`) and 1 StatefulSet (`application-controller`).
- kube-prometheus-stack: 5 pods (Deployments `kps-grafana`, `kps-kube-state-metrics`, `kps-operator`, plus the `kps-prometheus` and `kps-alertmanager` pods the operator creates), plus one `node-exporter` pod per node (DaemonSet).
- Next week: Loki, Tempo and Alloy, 1 pod each.

All of that comes on top of the 4 apps + Kafka + the existing controllers. It **exceeds the ceiling**, and pods hang in `Pending` even with CPU/RAM to spare.

`t3.large` has 3 ENIs × 12 IPs → **35 pods per node**, and twice the RAM (Prometheus uses a fair amount of RAM).

The instance type isn't in `terraform.tfvars`; it's a literal in `envs/dev-eks/main.tf`, inside `module "eks"` (`node_instance_type = "t3.medium"`, next to `node_min = 2`). Change that line:

```hcl
  node_instance_type = "t3.large"
```

It applies the next time you build the cluster. If the cluster is running, the apply replaces the node group. The managed node group in `terraform-aws-modules/eks` v21 has `create_before_destroy = true` and a name prefix, so the new group comes up before the old one goes. Pods move over, with a few minutes of churn, which is normal for a learning environment.

---

# STAGE B — CI writes the tag into git (the infra repo becomes the source of truth)

## B1. The mechanism: a deploy = a commit

CI's new flow after pushing the image successfully:

```
image sha-abc123 built
  └─ job release:
       1. clone jaka_infrastructure
       2. edit values/order-eks.yml:  image.tag: "sha-abc123"
       3. git commit + push (author: github-actions[bot])
```

That's it. No kubeconfig, no helm, no AWS. ArgoCD handles the rest.

For this to work, `values/<svc>-eks.yml` must contain an image block (until now the image came from `--set`, so the files don't have one; the chart default in `values.yaml` is `repository: ""`, `tag: "v1"`). Add to **all 4 files** `charts/spring-service/values/<svc>-eks.yml`:

```yaml
image:
  repository: ""          # ArgoCD fills this in (stage C) — don't hardcode the account ID
  tag: "sha-placeholder"  # CI overwrites this line
```

## B2. Deploy key — the narrowest possible write access

CI (app repo) needs to push to the infra repo. Options: a personal PAT (far too broad, the whole account), a GitHub App (the enterprise standard, but many steps), or a **deploy key** (an SSH key attached to exactly one repo). Use the deploy key.

```bash
# 1) Create the key pair (no passphrase, since CI uses it)
ssh-keygen -t ed25519 -C "gha-release-bot" -f /tmp/infra_deploy_key -N ""

# 2) PUBLIC key -> INFRA repo, with "Allow write access"
gh repo deploy-key add /tmp/infra_deploy_key.pub \
  --repo ghtaidt9/jaka_infrastructure --title "gha-release-bot" --allow-write

# 3) PRIVATE key -> a secret in the APP repo
gh secret set INFRA_DEPLOY_KEY --repo ghtaidt9/springboot_aws_compute_databases \
  < /tmp/infra_deploy_key

# 4) Remove from the machine
rm /tmp/infra_deploy_key /tmp/infra_deploy_key.pub
```

This key can do exactly one thing: read/write `jaka_infrastructure`. If it leaks, it can't touch any other repo or the AWS account.

## B3. The `release` job in `_build-service.yml` (app repo)

The job goes into the **reusable** workflow from week 13 (`.github/workflows/_build-service.yml`), as a second job after `build`. All 4 service workflows call that file, so the release logic is written once. Add at the end of its `jobs:`:

```yaml
  release:
    needs: build
    if: github.event_name != 'pull_request'
    runs-on: ubuntu-latest
    concurrency:
      group: release-infra-${{ inputs.service }}
    steps:
      - name: Checkout infra repo (SSH, with write access)
        uses: actions/checkout@v7
        with:
          repository: ghtaidt9/jaka_infrastructure
          ref: master
          ssh-key: ${{ secrets.INFRA_DEPLOY_KEY }}

      - name: Bump image tag
        env:
          SERVICE: ${{ inputs.service }}
          TAG: sha-${{ github.sha }}
        run: |
          yq -i ".image.tag = \"$TAG\"" "charts/spring-service/values/${SERVICE}-eks.yml"
          echo "bump $SERVICE -> $TAG"

      - name: Commit and push
        env:
          SERVICE: ${{ inputs.service }}
          TAG: sha-${{ github.sha }}
        run: |
          git config user.name  "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add charts/spring-service/values/
          if git diff --cached --quiet; then
            echo "Nothing changed (re-run?) — skipping."
            exit 0
          fi
          git commit -m "release: $SERVICE -> $TAG"
          for i in 1 2 3 4 5; do
            if git push; then exit 0; fi
            echo "Push failed (another service's release just landed?) — rebasing and retrying ($i/5)"
            git pull --rebase
          done
          exit 1
```

**Pass the secret down.** A reusable workflow does **not** see the caller's secrets automatically (unlike `vars`). In each of the 4 service workflows, add `secrets: inherit` to the `build` job that calls `_build-service.yml`:

```yaml
jobs:
  build:
    uses: ./.github/workflows/_build-service.yml
    permissions:
      contents: read
      id-token: write
    secrets: inherit
    with:
      service: order
```

Explanation:

- `needs: build`: only after this service's test + push succeeded. `if: github.event_name != 'pull_request'`: pushes to master and manual runs release; PRs don't.
- `concurrency: release-infra-<service>`: two releases of **the same** service queue up instead of racing. It is per service on purpose. GitHub keeps only **one pending** run per concurrency group and cancels any older pending one. With one shared group, 4 services finishing together would leave 1 running, 1 pending and 2 **cancelled**, and those 2 tag bumps would be lost.
- Different services can therefore push at the same moment. The `push → fail → pull --rebase → push` loop handles that: the later push is rejected (non-fast-forward), rebases onto the other service's commit and pushes again. They edit different files (`order-eks.yml` vs `payment-eks.yml`), so the rebase never conflicts. 5 attempts covers all 4 services landing together.
- `ssh-key:` in checkout: the action configures an SSH agent with that key; later `git push` uses this identity.
- `yq -i '.image.tag = ...'`: edits YAML **structurally**, by the key path `.image.tag`. yq 4.54.1 is preinstalled on the `ubuntu-24.04` runner (its published software list). Don't use `sed` on YAML: sed matches text, so a pattern like `tag:` hits any line with that text. Today `image.tag` is the only `tag:` key in these files, but the first time you add another one (a sidecar image, for example), sed edits both.
- `SERVICE` and `TAG` reach the script through `env:`, not as `${{ }}` inside `run:`. GitHub pastes `${{ }}` expressions into the script text **before** the shell runs, so outside content in them could become commands (script injection). Through `env:` they're plain environment variables.
- The email `41898282+github-actions[bot]@users.noreply.github.com` is GitHub's standard bot identity: the commit shows the bot avatar, and git history documents itself as "this deploy was done by a machine".
- `git diff --cached --quiet` → exit 0: a re-run doesn't create an empty commit.

**First seed:** after merging, run each service workflow once by hand to write real tags into all 4 values files:

```bash
for svc in order payment inventory notification; do gh workflow run "$svc-ci.yml"; done
```

---

# STAGE C — ArgoCD

## C1. What ArgoCD is, in terms of a model you already know

You understood the Kubernetes controller pattern in month 2: the *Deployment controller* constantly compares "desired pod count" with "actual pod count" and fixes the difference. **ArgoCD is the same pattern one level up**: it compares "desired state **in git**" with "actual state **in the cluster**" and fixes the difference (sync). Core concepts:

- **Application** (CRD): one "watched" unit: *which repo + which path + how to render it (helm/kustomize) + which target cluster/namespace*. Each of your services will be one Application.
- **ApplicationSet** (CRD): a factory that stamps out Applications from a template + a list of parameters. Your 4 services are identical except for the name → 1 ApplicationSet instead of 4 hand-copied Application files.
- **Sync**: the act of applying git to the cluster. **OutOfSync** = the cluster differs from git.

## C2. Values for the ArgoCD chart — `k8s/argocd/values.yml` (infra repo)

```yaml
dex:
  enabled: false

notifications:
  enabled: false

configs:
  params:
    server.insecure: true
```

- `dex.enabled: false`: Dex is the SSO layer (login via Google/GitHub...). The built-in admin account is enough for a learning environment; turn it off to keep the cluster light.
- `notifications.enabled: false`: the controller that sends Slack/email notifications on sync; not needed yet.
- `server.insecure: true`: the ArgoCD server uses a self-signed TLS cert. We reach the UI through `kubectl port-forward` (an already-encrypted channel), so letting the server speak plain HTTP avoids browser certificate warnings. **Don't** do this if you ever expose ArgoCD publicly.

## C3. Install it from `eks-up.sh`

Add these lines to step 4 (`# 4. add-on`) of `scripts/eks-up.sh`, after the `eg` (Envoy Gateway) install. The script `cd`s to the infra repo root on line 5 (`cd "$(dirname "$0")/.."`), so paths are relative to it, like the existing `kubectl apply -f k8s/...` lines:

```bash
helm repo add argo https://argoproj.github.io/argo-helm --force-update
helm upgrade --install argocd argo/argo-cd --version 10.17.2 \
    -n argocd --create-namespace -f k8s/argocd/values.yml --wait --timeout 10m
```

- `--version 10.17.2`: **always pin the chart version** (`helm show chart` for 10.17.2 reports `appVersion: v3.6.1`). Without a pin, every cluster build gets a different version and nothing is reproducible.
- The chart's `values.yaml` sets `nameOverride: argocd`, so the Services get short names like `argocd-server`, `argocd-repo-server`... in namespace `argocd`.

## C4. ApplicationSet — `k8s/argocd/applicationset.yml.tpl`

The file ends in `.tpl` because it has one variable, `${REGISTRY}`, filled in at runtime (the account ID must not live in a public repo):

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: jaka-services
  namespace: argocd
spec:
  goTemplate: true
  generators:
    - list:
        elements:
          - svc: order
          - svc: payment
          - svc: inventory
          - svc: notification
  template:
    metadata:
      name: '{{.svc}}-service'
      finalizers:
        - resources-finalizer.argocd.argoproj.io
    spec:
      project: default
      source:
        repoURL: https://github.com/ghtaidt9/jaka_infrastructure.git
        targetRevision: master
        path: charts/spring-service
        helm:
          valueFiles:
            - values/{{.svc}}-eks.yml
          parameters:
            - name: image.repository
              value: '${REGISTRY}/jaka-mod-{{.svc}}-service'
      destination:
        server: https://kubernetes.default.svc
        namespace: dev
      syncPolicy:
        automated:
          prune: true
          selfHeal: true
```

Field by field:

- `goTemplate: true`: enables Go-template syntax `{{.svc}}` in the template (the default is the older `{{svc}}` syntax, with fewer features).
- `generators: list: elements`: the parameter source. The `list` generator is the simplest: list the values by hand. (Other generators, `git`, `cluster`, `matrix`, are for when you have many environments.) Each element produces one Application named `<svc>-service`.
- `finalizers: resources-finalizer...`: when the Application is deleted, also delete **the resources it deployed** (cascade). Without it, removing the app from ArgoCD leaves the Deployment/Service behind in the cluster as orphans.
- `source`: "where the truth lives": the infra repo, branch master, chart `charts/spring-service`.
- `helm.valueFiles`: the path is **relative to `path`** → `charts/spring-service/values/order-eks.yml`. That's exactly the file the `release` job writes the tag into: the link connecting CI to ArgoCD.
- `helm.parameters`: equivalent to helm's `--set`; fills in `image.repository` (the part with the account ID). `image.tag` is **not** set here; it lives in the git-managed values file.
- `destination.server: https://kubernetes.default.svc`: the API server address as seen from inside the cluster, meaning "deploy into this same cluster".
- `syncPolicy.automated`: without this block ArgoCD only *reports* OutOfSync and waits for a button press. With it on:
  - `prune: true`: a resource **removed from git** is removed from the cluster (by default ArgoCD doesn't dare delete anything).
  - `selfHeal: true`: a resource **edited by hand in the cluster** is restored to git's version. Together, the two flags make git the absolute truth, in both directions.

Add a render + apply step to `eks-up.sh` after step 8 (Kafka + Mailpit) and before step 10 (gateway). At that point ArgoCD (step 4), the ExternalSecrets (step 6), the DB-role Jobs (step 7) and the infra ConfigMaps (step 3b, week 14) all exist. After week 14 the script no longer holds the registry in any variable (step 2 was removed), so read it from shared's `ecr_registry` output:

```bash
# 9. Apps: register them with ArgoCD (it deploys them from git)
REGISTRY=$(terraform -chdir=envs/shared output -raw ecr_registry) \
    envsubst '${REGISTRY}' < k8s/argocd/applicationset.yml.tpl | kubectl apply -f -
```

- `envsubst '${REGISTRY}'` substitutes **only that one variable**; the argument list keeps envsubst from touching the `{{.svc}}` placeholders in the file. The same pattern is in step 6 (`envsubst '${SVC} ${ADMIN_SECRET_ARN}'`).
- `REGISTRY=... envsubst ...` sets the variable for that one command only, so no `export` is needed.
- Step 10's `kubectl wait --for=condition=Available deploy --all -n dev` may now run before ArgoCD has created the 4 Deployments (sync is asynchronous). In that case it only waits for `mailpit`. Watch the apps in the ArgoCD UI (C5) instead.

## C5. Open the UI + get the password

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d; echo
kubectl -n argocd port-forward svc/argocd-server 8080:80
```

The `argocd-server` Service exposes port 80 (and 443), both pointing at the container's 8080. With `server.insecure: true`, that port speaks plain HTTP. The `argocd-initial-admin-secret` Secret isn't part of the chart: ArgoCD creates it on first start.

Open `http://localhost:8080`, user `admin`, password as printed. You'll see the 4 apps as cards; click one to get a **visual resource tree**: Application → Deployment → ReplicaSet → Pod, with the status of each level. It's the most visual Kubernetes debugging tool you've had so far.

## C6. Three required experiments (understand GitOps hands-on)

**Experiment 1 — deploy through git:** edit one line in order-service → push to master → CI builds + the release job commits to the infra repo → wait (≤3 minutes) → the order-service card in the UI goes to Syncing → pods roll over. Look at the bot's commit:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
git pull && git log --oneline -3
```

To avoid waiting 3 minutes during a demo, click **Refresh** in the UI, or:

```bash
kubectl -n argocd annotate application order-service \
  argocd.argoproj.io/refresh=normal --overwrite
```

**Experiment 2 — drift gets self-healed:** play "someone editing prod by hand":

```bash
kubectl set env deployment/order-service -n dev HACK=true
kubectl get deployment order-service -n dev -o yaml | grep -A2 HACK   # it's there
sleep 15
kubectl get deployment order-service -n dev -o yaml | grep -A2 HACK   # gone!
```

`selfHeal` detects that the spec differs from git and re-applies it within seconds. The lesson: from now on **every** change must go through git; hand edits are pointless (which is exactly what we want).

**Experiment 3 — rollback = git revert:** no more `helm rollback`; going back is a pure git operation:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
git log --oneline charts/spring-service/values/   # find the "release: ..." commit
git revert <commit-sha>
git push
```

ArgoCD sees the old tag back in git → syncs → the pod runs the old image again. The advantage over helm rollback: the rollback **is in the git history**, visible to everyone, instead of hidden state inside the cluster.

---

# STAGE D — Prometheus + Grafana

## D1. Prometheus's pull model

Prometheus does **not** wait for the app to send metrics (like the CloudWatch agent you used in month 1); it actively **pulls**: every 15 seconds it does a GET on `http://<pod>:<port>/actuator/prometheus` for each service and stores the time series. Three things to do:

1. The app exposes `/actuator/prometheus` (D3).
2. Tell Prometheus *whom to scrape*, via the **ServiceMonitor** CRD (D4).
3. Install Prometheus + Grafana (D2).

## D2. Install kube-prometheus-stack — `scripts/observability-up.sh` (infra repo)

The `kube-prometheus-stack` chart bundles the whole set: Prometheus Operator, Prometheus, Alertmanager, Grafana, node-exporter, kube-state-metrics, plus built-in dashboards and alerts for Kubernetes itself.

Create `k8s/observability/kps-values.yml`:

```yaml
fullnameOverride: kps

# Control-plane components that EKS hides and can't be scraped -> off, to avoid false alarms
kubeEtcd:
  enabled: false
kubeControllerManager:
  enabled: false
kubeScheduler:
  enabled: false
kubeProxy:
  enabled: false

prometheus:
  prometheusSpec:
    # The 3 most important lines in the file — see below
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelectorNilUsesHelmValues: false
    ruleSelectorNilUsesHelmValues: false
    retention: 2d
    resources:
      requests:
        cpu: 200m
        memory: 1Gi
      limits:
        memory: 2Gi

grafana:
  adminPassword: ""   # empty -> the chart generates a random password into a Secret
```

Explanation:

- `fullnameOverride: kps`: without it, every resource gets a long name like `kps-kube-prometheus-stack-prometheus...`; a short name saves a lot of typing.
- `kubeEtcd/...: enabled: false`: on EKS, AWS manages the control plane; etcd/scheduler/controller-manager **can't be scraped from inside the cluster**. Left at the defaults, these 4 targets are permanently red and 4 false alerts keep firing. Turn them off from the start.
- **`serviceMonitorSelectorNilUsesHelmValues: false`**: the chart's most notorious field. By default (`true`), Prometheus **only accepts ServiceMonitors carrying that helm release's own label**: the ServiceMonitor you create in the `spring-service` chart would be ignored *without a single error line*. `false` = "accept every ServiceMonitor in the cluster". Same for PodMonitor and PrometheusRule (rules are needed in week 16). Forgetting these 3 lines is the classic symptom "targets list is empty and there's no error anywhere".
- `retention: 2d`: keep 2 days of data; a build-and-destroy learning environment doesn't need more, and it saves RAM/disk.
- `grafana.adminPassword: ""`: the chart generates a password into the `kps-grafana` Secret (retrieved in D5), so no password is hardcoded in the repo.

Create `scripts/observability-up.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update

helm upgrade --install kps prometheus-community/kube-prometheus-stack \
  --version 93.2.0 \
  -n monitoring --create-namespace \
  -f "$ROOT/k8s/observability/kps-values.yml" \
  --wait --timeout 15m

echo ">> Grafana password:"
kubectl -n monitoring get secret kps-grafana \
  -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

```bash
chmod +x scripts/observability-up.sh
```

Call it from `eks-up.sh` **before** step 9 (the ApplicationSet), so the ServiceMonitor CRD exists before the app chart renders; D4 explains why that matters. `eks-up.sh` runs from the repo root, so the relative path works:

```bash
./scripts/observability-up.sh
```

(`observability-up.sh` defines its own `ROOT` variable, so it works from any directory. `eks-up.sh` has no `ROOT`; it uses `cd` plus relative paths instead.)

## D3. App side: expose Prometheus metrics (app repo — all 4 services)

**`build.gradle`:**

```groovy
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

Micrometer is the metrics API (already part of Boot actuator); this registry is the Prometheus-format "output". `runtimeOnly` because the code doesn't call any of its classes directly.

**`application.yaml`**: the `management:` section of each service already has `endpoint.health.show-details`, `endpoints.web.exposure.include: health,info,metrics`, and `metrics.tags` (`service: <name>-service`, `environment: dev`). Change two things and leave the rest:

1. Add `prometheus` to the exposure list:

   ```yaml
       web:
         exposure:
           include: health,info,metrics,prometheus
   ```

2. Add a `distribution` block under the existing `metrics:` key, next to `tags:`:

   ```yaml
     metrics:
       tags:
         service: order-service      # already there; each service has its own name
         environment: dev
       distribution:
         percentiles-histogram:
           http.server.requests: true
   ```

- Adding `prometheus` to exposure opens the `/actuator/prometheus` endpoint. It exists once the registry is on the classpath: Boot 4.1's `spring-boot-micrometer-metrics` (pulled in by `spring-boot-starter-actuator`) contains `PrometheusMetricsExportAutoConfiguration`.
- `percentiles-histogram: http.server.requests: true`: exports **histogram buckets** for HTTP request duration. Without it you can't compute server-side p95 (percentiles must be computed from buckets with `histogram_quantile`; ready-made percentile numbers can't be aggregated across pods).
- A heads-up for D6: the `service` tag you set yourself shows up in Prometheus as `exported_service`, because Prometheus also attaches its own `service` label (= the name of the scraped Kubernetes Service) and the target's label wins. Not a bug.

**A gauge for the outbox**: a home-made business metric measuring "how many events are not yet published" (it becomes week 16's main alert). In order-service:

Add to `OutboxRepository` (`com.jaka.order_service.repository`, which today has only `lockUnpublishedEvents`):

```java
long countByPublishedAtIsNull();
```

Spring Data derives the query from the method name. It works because `OutboxEvent` has an `Instant publishedAt` field (the same column `lockUnpublishedEvents` filters on as `published_at IS NULL`).

Create `src/main/java/com/jaka/order_service/config/OutboxMetricsConfig.java` (the `config` package already holds `RedisCacheConfig`, `OrderDataSeeder`, ...):

```java
package com.jaka.order_service.config;

import com.jaka.order_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutboxMetricsConfig {

    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, OutboxRepository repo) {
        return Gauge.builder("outbox.pending", repo, r -> r.countByPublishedAtIsNull())
                .description("Number of outbox events not yet published")
                .register(registry);
    }
}
```

A `Gauge` is "the value read at that moment" (unlike a `Counter`, which only goes up): on every Prometheus scrape, Micrometer calls the count function once (every 15s, cheap). The name `outbox.pending` becomes `outbox_pending` in Prometheus.

Check the endpoint once the new order-service image runs in `dev`. The `order-service/order-service/docker-compose.yml` stack has only Postgres + Redis (no Kafka), so the full app isn't set up to run locally. The Service listens on 8081 (`port` in `values/order-eks.yml`):

```bash
kubectl -n dev port-forward svc/order-service 8081:8081
# in another terminal:
curl -s localhost:8081/api/orders/actuator/prometheus | grep -E 'outbox_pending|http_server_requests_seconds_bucket' | head
```

## D4. Chart side: a proper Service + ServiceMonitor (infra repo)

A ServiceMonitor works as a chain: *the ServiceMonitor selects **Services** by label → Prometheus scrapes every endpoint behind that Service, by **port name***. But `charts/spring-service/templates/service.yml` currently has **no metadata labels and an unnamed port**, so fix that first.

**`templates/service.yml`**, full version after the fix (same 4-space indent and flow style as your current file; the port value stays `.Values.port`, the chart's top-level key):

```yaml
apiVersion: v1
kind: Service
metadata:
    name: {{ include "spring-service.fullname" . }}
    labels: { app: {{ include "spring-service.fullname" . }} }
spec:
    type: ClusterIP
    selector: { app: {{ include "spring-service.fullname" . }} }
    ports:
        - name: http
          port: {{ .Values.port }}
          targetPort: {{ .Values.port }}
```

Two changes: a `labels` line (so the ServiceMonitor can *find* the Service) and `name: http` on the port (so the ServiceMonitor can *refer to it by name*).

**`templates/servicemonitor.yml`**, a new file:

```yaml
{{- if and .Values.metrics.serviceMonitor.enabled (.Capabilities.APIVersions.Has "monitoring.coreos.com/v1") }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "spring-service.fullname" . }}
  labels:
    app: {{ include "spring-service.fullname" . }}
spec:
  selector:
    matchLabels:
      app: {{ include "spring-service.fullname" . }}
  endpoints:
    - port: http
      path: {{ .Values.contextPath }}/actuator/prometheus
      interval: 15s
{{- end }}
```

- The guard `.Capabilities.APIVersions.Has "monitoring.coreos.com/v1"`: on a cluster **without** the Prometheus Operator (for example the kind cluster from months 2–3), the ServiceMonitor CRD doesn't exist, so rendering it would make the apply fail. The guard makes the chart skip this file where the API group isn't present. (That's why `observability-up.sh` must run *before* the ApplicationSet in `eks-up.sh`.) The check uses the plain `group/version` form: the guard only needs to know that the Prometheus Operator's API group is installed. You can test it with `--api-versions` exactly as written (see the render check below).
- `selector.matchLabels.app`: matches the label just added to the Service.
- `port: http`: by **name**, not number: the 4 services have 4 different port numbers but the same name → one shared template.
- `path`: includes each service's context path: `/api/orders/actuator/prometheus`...
- `interval: 15s`: the scrape interval.

The chart's **`values.yaml`**: off by default (older environments keep their behaviour):

```yaml
metrics:
  serviceMonitor:
    enabled: false
```

**`values/<svc>-eks.yml`** (4 files): on for EKS:

```yaml
metrics:
  serviceMonitor:
    enabled: true
```

Check the render before pushing. With the API version, the output contains `kind: ServiceMonitor` with `path: /api/orders/actuator/prometheus`; without it, the count is `0`:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/charts/spring-service
helm template order-service . -f values/order-eks.yml --set image.repository=test \
  --api-versions monitoring.coreos.com/v1 | grep -A12 'kind: ServiceMonitor'
helm template order-service . -f values/order-eks.yml --set image.repository=test | grep -c 'kind: ServiceMonitor'
```

Push the chart changes to the infra repo's master and notice: **ArgoCD deploys this chart change to all 4 services by itself** (last week's weakness #4 is gone).

## D5. Check the scrape chain

```bash
kubectl -n monitoring port-forward svc/kps-prometheus 9090:9090
```

Open `http://localhost:9090` → **Status → Targets**: you should see 4 targets `serviceMonitor/dev/<svc>-service/0` in state **UP**. If not, walk the chain in order:

```bash
kubectl get servicemonitor -n dev                         # 1) was the CRD object created?
kubectl get svc order-service -n dev --show-labels        # 2) does the Service have app=?
kubectl get svc order-service -n dev -o yaml | grep -B1 'name: http'   # 3) is the port named?
```

(If all 3 look fine and the targets are still empty, go back to D2 and check the 3 `*NilUsesHelmValues: false` lines.)

## D6. Grafana + your first PromQL queries

```bash
kubectl -n monitoring get secret kps-grafana -o jsonpath='{.data.admin-password}' | base64 -d; echo
kubectl -n monitoring port-forward svc/kps-grafana 3000:80
```

`http://localhost:3000`, user `admin`. `kps-grafana` is the Grafana Service (port 80) and also the Secret holding `admin-password`. With `adminPassword: ""`, the chart generates a random password into that Secret. The Prometheus datasource is preconfigured with uid `prometheus` (you can see it in the rendered datasource ConfigMap). Week 16's dashboard needs exactly this uid. Go to **Explore** and run each query, making sure you understand it:

```promql
# 1) Requests/second per service (summed by the "service" label = the Kubernetes Service name)
sum by (service) (rate(http_server_requests_seconds_count{namespace="dev"}[5m]))

# 2) 5xx error rate (%)
100 * sum(rate(http_server_requests_seconds_count{namespace="dev", status=~"5.."}[5m]))
    / sum(rate(http_server_requests_seconds_count{namespace="dev"}[5m]))

# 3) p95 latency — computed from histogram buckets (why percentiles-histogram is on in D3)
histogram_quantile(0.95,
  sum by (service, le) (rate(http_server_requests_seconds_bucket{namespace="dev"}[5m])))

# 4) How many events the outbox is holding
outbox_pending

# 5) JVM heap in use
sum by (service) (jvm_memory_used_bytes{area="heap", namespace="dev"})

# 6) Live JVM threads, then active Hikari (DB pool) connections
jvm_threads_live_threads{namespace="dev"}
hikaricp_connections_active{namespace="dev"}
```

The two most important patterns: `rate(x_count[5m])` = how fast a counter grows over a 5-minute window = "per second"; `histogram_quantile(0.95, ...bucket...)` = estimate p95 from the histogram. Both reappear in every week-16 alert.

Send some traffic so the graphs have shape. `GATEWAY_URL` is the NLB in front of the gateway `web`; `eks-up.sh` reads it the same way at its end. `GET /api/orders` is `OrderController.getAllOrders()`, routed by `k8s/gateway.yml` (`PathPrefix /api/orders` → `order-service:8081`):

```bash
GATEWAY_URL="http://$(kubectl get gateway web -n dev -o jsonpath='{.status.addresses[0].value}')"
for i in $(seq 1 100); do curl -s "$GATEWAY_URL/api/orders" > /dev/null; done
```

Finally, import a ready-made JVM dashboard: Dashboards → New → Import → enter ID **4701** ("JVM (Micrometer)" on grafana.com) → pick the Prometheus datasource. That shows heap/GC/threads for all 4 services without drawing anything.

## D7. End-of-week self-check

1. Trace a line of code from `git push` to a running pod: it now involves **two commits** (your code commit + the bot's release commit). Point out the step where git is the source of truth.
2. Run the drift experiment (C6-2) and explain how selfHeal and prune differ.
3. Answer without the doc: what are the 3 `*NilUsesHelmValues: false` lines for? Why must the Service's port have a name?
4. Rewrite the p95 PromQL query from memory and explain what `le` is (hint: *less than or equal*, the upper bound of a bucket).

---

## Next week

Metrics answer "is **something** wrong?". [WEEK 16](TUAN-16-CHI-TIET.md) answers the rest, "what, **where, and why**": centralised logs (Loki), distributed tracing (Tempo) across Kafka, alerts that email you on errors/high latency, and an overview dashboard for all 4 services. The month-4 checkpoint is at the end of that file.
