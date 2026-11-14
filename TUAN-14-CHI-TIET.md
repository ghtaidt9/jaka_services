# WEEK 14 — CD: GitHub Actions deploys to EKS (push-based)

> Month 4, week 2 of 4. Series: [TUAN-13](TUAN-13-CHI-TIET.md) → **TUAN-14** → [TUAN-15](TUAN-15-CHI-TIET.md) → [TUAN-16](TUAN-16-CHI-TIET.md).

## TL;DR

By the end of this week, the full flow from roadmap weeks 13–14 works for real:

```
git push master ──▶ test ──▶ build ──▶ push ECR ──▶ deploy to EKS (namespace dev)
                                                     │
                                                     ├─ helm upgrade --install, with --wait
                                                     ├─ failure → automatic rollback
                                                     └─ smoke test: curl /actuator/health/readiness
```

Main tasks:

1. **A second AWS role**, `jaka-mod-gha-deploy-dev`, used only for deploying and separate from the CI role.
2. **An EKS access entry** giving that role Edit rights, locked to namespace `dev`.
3. Changes to the chart + `eks-up.sh`: infrastructure config (DB URL, Redis host) moves into a **ConfigMap**, so deploying from CI doesn't need to know any secret or URL.
4. A `deploy-service.sh` script (runs from both your laptop and CI) + a `deploy-dev.yml` workflow.

This week's model is called **push-based CD** (CI "pushes" into the cluster). It works well, but it has weaknesses that week 15 solves with GitOps. Doing this week first is what makes you *feel* why people need ArgoCD.

---

## Why each task (seen from the current repo)

| # | Current state | Problem when CI wants to deploy | This week's task |
|---|---|---|---|
| 1 | The `jaka-mod-gha-ci` role (week 13) only has ECR permissions | CI can't see the cluster | A new deploy-only role + an EKS access entry |
| 2 | `eks-up.sh` deploys the apps with a long list of `--set`: image, datasource URL, Redis host, read from Terraform outputs on the **laptop** | The GitHub runner has no Terraform state and shouldn't know the DB URL | Move the "infrastructure" part into a ConfigMap that `eks-up.sh` creates once; CI only sets the image |
| 3 | Deploy = a bare `helm upgrade`, no waiting, no check | A broken deploy still shows CI green; pods CrashLoop silently | `--wait --rollback-on-failure` + a smoke test |
| 4 | The dev-eks cluster is **ephemeral**: most of the time it doesn't exist | Every push to master fails at the deploy step while the cluster is off | An `EKS_DEV_UP` switch (GitHub variable) to turn the deploy job on and off |

> **Trade-off, stated up front:** the push-based model means GitHub holds access to the cluster (even if tightly scoped). It also means "the cluster's state" only exists in the history of workflow runs; nothing declares what the cluster *should* be running. Accept that this week; week 15 turns the model around.

> **Prerequisite:** the deploy job (stage C) checks out `ghtaidt9/jaka_infrastructure` at `master` **from GitHub**. GitHub's `master` must already contain the EKS work (`charts/spring-service/values/*-eks.yml`, `scripts/`). If you skipped the merge-and-push at the end of week 13 (D4), do it first. Check with:
>
> ```bash
> cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
> git ls-remote --heads origin master
> git rev-parse eks-platform
> ```
>
> The two hashes must be equal, or GitHub's master must be newer.

---

# STAGE A — The deploy identity (repo `jaka_infrastructure`)

## A1. Why a second role (instead of reusing the CI role)

With a single role that both pushes to ECR and deploys, any PR (which runs the build job) would in theory carry permission to touch the cluster. Splitting the roles gives you:

- The **CI** role: assumable from PRs + master, ECR only.
- The **deploy** role: assumable **only when** the job runs in the GitHub *environment* `dev`; EKS + read ECR only.

What a GitHub environment is: a "compartment" in the repo (Settings → Environments) that a job can only use by declaring `environment: dev`. Later you can attach protection rules to it (for example a manual approval before deploying). Most importantly for us: when a job runs in an environment, the `sub` claim in the OIDC token **changes format** to `repo:<owner>/<repo>:environment:dev`, so the trust policy can enforce "only jobs in the dev environment may assume this role".

## A2. `envs/shared/github-deploy.tf`

Add a new file to `envs/shared`. It reuses `aws_iam_openid_connect_provider.github` from `github-oidc.tf` (week 13) and `module.ecr` from `main.tf`, both in the same directory:

```hcl
# Role for the deploy job — assumable only from the "dev" environment
resource "aws_iam_role" "gha_deploy_dev" {
  name = "${local.project}-gha-deploy-dev"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.github.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_owner}/${var.app_repo}:environment:dev"
        }
      }
    }]
  })
}

data "aws_caller_identity" "current" {}

resource "aws_iam_role_policy" "gha_deploy_dev" {
  name = "eks-deploy"
  role = aws_iam_role.gha_deploy_dev.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DescribeCluster"
        Effect   = "Allow"
        Action   = ["eks:DescribeCluster"]
        Resource = "arn:aws:eks:${var.region}:${data.aws_caller_identity.current.account_id}:cluster/jaka-mod-dev-eks"
      },
      {
        Sid      = "ReadEcrTags"
        Effect   = "Allow"
        Action   = ["ecr:DescribeImages"]
        Resource = values(module.ecr.repository_arns)
      },
    ]
  })
}
```

And an output in `outputs.tf`:

```hcl
output "gha_deploy_role_arn" {
  description = "ARN of the deploy role for GitHub Actions"
  value       = aws_iam_role.gha_deploy_dev.arn
}
```

Explanation:

- `sub` uses `StringEquals` (not `StringLike`) to match **exactly** `repo:ghtaidt9/springboot_aws_compute_databases:environment:dev`. PRs don't run in the environment, so they can never assume this role.
- `eks:DescribeCluster` is the **only** AWS API permission needed for `aws eks update-kubeconfig` (the command that creates the kubeconfig). Every permission "inside" the cluster (get pods, helm install...) **does not go through the IAM policy** but through the **EKS access entry** in step A3. This is the most confusing point: IAM lets you through the "gate"; the access entry decides what you can do "inside the house".
- `ecr:DescribeImages` lets the "deploy the newest tag" feature (B4) query ECR.
- Where the cluster name `jaka-mod-dev-eks` comes from: `envs/dev-eks/main.tf` sets `local.name_prefix = "${local.project}-${local.environment}"` = `jaka-mod-dev-eks` and passes it as `name_prefix` to `modules/eks`, which uses it as the cluster `name`. After an apply, `terraform -chdir=envs/dev-eks output -raw cluster_name` prints the same value.

Apply:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/envs/shared
terraform plan -var-file terraform.tfvars
terraform apply -var-file terraform.tfvars
```

## A3. Access entry in `envs/dev-eks` — the file `ci-access.tf`

Modern EKS manages cluster access with **access entries**: real Terraform resources, replacing the old `aws-auth` ConfigMap. Your `modules/eks` uses `terraform-aws-modules/eks` v21 (21.29.0 in `envs/dev-eks/.terraform/modules/modules.json`) and doesn't set `authentication_mode`, so the module's default applies: `API_AND_CONFIG_MAP`. Access entries work in that mode (and in `API`); only `CONFIG_MAP` mode would ignore them. Your own admin access already comes from an access entry: `enable_cluster_creator_admin_permissions = true` in `modules/eks/main.tf`.

Create `envs/dev-eks/ci-access.tf`:

```hcl
# This role is created by envs/shared — read it with a data source, don't recreate it
data "aws_iam_role" "gha_deploy" {
  name = "jaka-mod-gha-deploy-dev"
}

# 1) Let the role into the cluster
resource "aws_eks_access_entry" "gha_deploy" {
  cluster_name  = module.eks.cluster_name
  principal_arn = data.aws_iam_role.gha_deploy.arn
  type          = "STANDARD"
}

# 2) Once inside, what it may do: Edit, only in namespace "dev"
resource "aws_eks_access_policy_association" "gha_deploy_edit" {
  cluster_name  = module.eks.cluster_name
  principal_arn = data.aws_iam_role.gha_deploy.arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy"

  access_scope {
    type       = "namespace"
    namespaces = ["dev"]
  }

  depends_on = [aws_eks_access_entry.gha_deploy]
}
```

Field by field:

- `data "aws_iam_role"`: reads the role by **name** instead of hardcoding the account ARN into a public repo. Consequence: **`shared` must be applied before `dev-eks`** (the role must exist for the data source to read it). Remember this order every time you rebuild from scratch.
- `type = "STANDARD"`: the entry type for a normal IAM role (other types like `EC2_LINUX` are for nodes).
- `AmazonEKSEditPolicy`: an EKS-managed policy equivalent to Kubernetes' `edit` ClusterRole: create/update/delete Deployments, Services, ConfigMaps, Secrets..., but **not** RBAC and not nodes. Its siblings: `AmazonEKSViewPolicy` (read only), `AmazonEKSAdminPolicy` (admin of a namespace), `AmazonEKSClusterAdminPolicy` (whole cluster — the one you use yourself).
- `access_scope namespace ["dev"]`: the second fence. Even with Edit, the role can only touch namespace `dev`. If CI were compromised, it still couldn't delete Strimzi in the `kafka` namespace or monitoring later on.

This file lives in `dev-eks`, so its **lifecycle follows the cluster**: destroying the cluster removes the entry too, leaving nothing behind. Whenever you build the cluster (`terraform apply` in `envs/dev-eks`), the entry is created automatically.

---

# STAGE B — Chart + deploy script (repo `jaka_infrastructure`)

## B1. The problem: `eks-up.sh` passes config through `--set`

Open `scripts/eks-up.sh`. Step 9 (`# 9. App: ...`) runs `helm upgrade --install` for each service with these `--set` flags:

- `image.repository="$(pick "$ecr_urls" "$svc")"` and `image.tag="$tag"`
- `env.SPRING_DATASOURCE_URL="jdbc:postgresql://$(pick "$db_hosts" "$svc"):5432/${svc}db?sslmode=require"`
- for order and payment only (the `extra` array): `env.SPRING_DATA_REDIS_HOST="$redis_host"`

`db_hosts` and `redis_host` come from `terraform -chdir=envs/dev-eks output` (lines 18 and 20). If CI did that, the GitHub runner would need to read Terraform state, which is both complex and grants extra access.

**New design: split the two kinds of config by lifecycle:**

| Kind | Examples | Changes when | Provided by |
|---|---|---|---|
| **Infrastructure** config | `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST` | The infrastructure is rebuilt | `eks-up.sh` creates a `<svc>-service-infra` ConfigMap once, when building the cluster |
| **Release** config | `image.repository`, `image.tag` | Every deploy | CI/`deploy-service.sh` via `--set` |

The pod reads the ConfigMap through `envFrom`, so a CI deploy only needs to say "which image"; everything else is already in the cluster.

## B2. Update the chart `charts/spring-service`

**`values.yaml`**: add a new key (empty by default so older environments like kind don't break):

```yaml
# List of external ConfigMaps whose keys are loaded as env vars
envFromConfigMaps: []
```

**`templates/deployment.yml`**: the `envFrom` block currently has the chart's ConfigMap and the optional secret, in flow style (`{ name: ... }`). Add the 3 middle lines, between the ConfigMap and the `with .Values.secretName` block:

```yaml
          envFrom:
            - configMapRef: { name: {{ include "spring-service.fullname" . }} }
            {{- range .Values.envFromConfigMaps }}
            - configMapRef: { name: {{ . }} }
            {{- end }}
            {{- with .Values.secretName }}
            - secretRef: { name: {{ . }} }
            {{- end }}
```

Explanation:

- `envFrom` + `configMapRef`: loads **every key** in the ConfigMap as a container env var. Spring Boot maps `SPRING_DATASOURCE_URL` → `spring.datasource.url` (relaxed binding, the same mechanism you've used since month 1 with SSM).
- `range .Values.envFromConfigMaps`: loops over the list; each element `{{ . }}` is a ConfigMap name. An empty list renders nothing, so the old behaviour stays.
- Order in `envFrom` matters: for duplicate keys, **the later entry wins**. Putting the infra ConfigMap after the chart's ConfigMap means infrastructure values override the chart's defaults.
- Keys under `env:` in the Deployment (`JAVA_TOOL_OPTIONS`, the two `MANAGEMENT_*` lines) beat every `envFrom` source.

**`values/<svc>-eks.yml`** (4 files): declare the infra ConfigMap for the EKS environment:

```yaml
envFromConfigMaps:
  - order-service-infra
```

(payment/inventory/notification the same, with their own names.)

Check the render before committing. The order output must contain `configMapRef: { name: order-service-infra }` between the chart ConfigMap and the secret. The kind values file (`order-dev.yml`) must render exactly as before, with no infra line:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/charts/spring-service
helm template order-service . -f values/order-eks.yml --set image.repository=test | grep -A4 'envFrom:'
helm template order-service . -f values/order-dev.yml | grep -A4 'envFrom:'
```

## B3. Update `scripts/eks-up.sh`

**Add** a new step right after step 3 (the namespace loop, `for ns in dev kafka`), because the ConfigMaps go into namespace `dev`. It uses only names the script already defines: the `services` array (line 7), `db_hosts` and `redis_host` (lines 18 and 20), and `pick` (line 21). The URL is the same string step 9 passes today:

```bash
# 3b. infra ConfigMap per service: config that changes only when the infrastructure is rebuilt
for svc in "${services[@]}"; do
    args=(--from-literal "SPRING_DATASOURCE_URL=jdbc:postgresql://$(pick "$db_hosts" "$svc"):5432/${svc}db?sslmode=require")
    [[ $svc == order || $svc == payment ]] && args+=(--from-literal "SPRING_DATA_REDIS_HOST=$redis_host")
    kubectl create configmap "$svc-service-infra" -n dev "${args[@]}" \
        --dry-run=client -o yaml | kubectl apply -f -
done
```

- `[[ ... ]] && args+=(...)` is the same pattern step 9 already uses for `extra`. Under `set -e`, a false `[[ ]]` on the left of `&&` doesn't stop the script.
- The `--dry-run=client -o yaml | kubectl apply -f -` trick: running `kubectl create` a second time fails with "already exists"; piping it through `apply` makes it **idempotent**, so running it any number of times ends in the desired state. This pattern is used all over Kubernetes scripting.

**Remove** these parts, which deploying from CI replaces:

| What | Where in `eks-up.sh` | Why it goes |
|---|---|---|
| `tag=${TAG:-eks-$(date +%s)}` | line 9 | nothing builds images here any more |
| `ecr_urls=$(terraform -chdir=envs/shared output -json ecr_repository_urls)` | line 17 (as changed in week 13) | only steps 2 and 9 used it |
| Step 2 (`# 2. image: build + push to ECR`): the `registry=...` line, `docker login`, and the build/push loop | lines 26–33 | CI builds and pushes now. It also stops the `eks-*` tags from week 13's leftover note |
| Step 9 (`# 9. App: ...`): the `helm upgrade --install` loop | lines 90–100 | `deploy-service.sh` / CI deploys now |

From now on `eks-up.sh` only builds the *platform*: controllers, operators, secrets, the DB-role Jobs, Kafka, Mailpit, the infra ConfigMaps, and the gateway. Two side effects at the end of the script:
- Step 10's `kubectl wait --for=condition=Available deploy --all -n dev` now only waits for `mailpit` (the only Deployment left in `dev`).
- The last `echo` still suggests `saga-demo.sh`. That only works after the first deploy (D1 step 4).

> **Operational note:** a pod reads env vars **only at startup**. After changing an infra ConfigMap, run the following so the pods pick up the new values:
>
> ```bash
> kubectl rollout restart deployment <svc>-service -n dev
> ```

## B4. The script `scripts/deploy-service.sh`

One deploy script, shared by the laptop and CI (CI is just one way of calling it):

```bash
#!/usr/bin/env bash
set -euo pipefail

# Usage: REGISTRY=<acct>.dkr.ecr.ap-southeast-1.amazonaws.com ./deploy-service.sh <svc> [tag]
# <svc>: order | payment | inventory | notification
# [tag]: empty = newest tag in ECR

svc="${1:?Missing service name (order|payment|inventory|notification)}"
tag="${2:-}"
region="${AWS_REGION:-ap-southeast-1}"

: "${REGISTRY:?REGISTRY must be set (e.g. <acct>.dkr.ecr.${region}.amazonaws.com)}"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
repo_name="jaka-mod-${svc}-service"

# No tag given -> ask ECR for the newest one
newest_tag() {
  aws ecr describe-images --region "$region" \
    --repository-name "$repo_name" \
    --query 'sort_by(imageDetails,&imagePushedAt)[-1].imageTags[0]' \
    --output text
}

if [ -z "$tag" ] || [ "$tag" = "latest" ]; then
  tag="$(newest_tag)"
  echo ">> Using newest tag in ECR: $tag"
fi

release="${svc}-service"

echo ">> Deploying $release image=${REGISTRY}/${repo_name}:${tag}"
if ! helm upgrade --install "$release" "$repo_root/charts/spring-service" \
    -n dev \
    -f "$repo_root/charts/spring-service/values/${svc}-eks.yml" \
    --set "image.repository=${REGISTRY}/${repo_name}" \
    --set "image.tag=${tag}" \
    --wait --rollback-on-failure --timeout "${TIMEOUT:-10m}"; then
  echo "!! Deploy failed — helm rolled back to the previous revision. History:"
  helm history "$release" -n dev | tail -5
  exit 1
fi

# ----- Smoke test: call readiness from inside the cluster -----
port="$(helm get values "$release" -n dev --all -o json | jq -r '.port')"
ctx="$(helm get values "$release" -n dev --all -o json | jq -r '.contextPath')"

echo ">> Smoke test: http://${release}:${port}${ctx}/actuator/health/readiness"
if kubectl run "smoke-${svc}-$$" --rm -i --restart=Never --quiet -n dev \
    --image=curlimages/curl:8.23.0 -- \
    curl -sf "http://${release}:${port}${ctx}/actuator/health/readiness"; then
  echo ">> OK: $release is ready."
else
  echo "!! Smoke test failed — roll back manually:"
  echo "   helm rollback $release -n dev"
  exit 1
fi
```

```bash
chmod +x scripts/deploy-service.sh
```

The decisions in the script:

- `set -euo pipefail`: the standard opening of every script. Stop on any failing command (`-e`), treat undefined variables as errors (`-u`), don't swallow errors in the middle of a pipe (`pipefail`).
- `${1:?...}` / `${REGISTRY:?...}`: bash prints the message and exits if the value is missing. "Fail early, fail clearly."
- `REGISTRY` is an environment variable, not hardcoded, because the repo is **public** and the account ID must not be in the code. The laptop gets it from `terraform output ecr_registry`; CI computes it from the caller identity (C2).
- `newest_tag()`: `describe-images` + `sort_by(imagePushedAt)[-1]` = the most recently pushed image. Used for "rebuild the cluster, deploy all 4 services at their latest version" without remembering any SHA.
- `--wait`: helm waits until the Deployment is really ready (enough replicas passing the readiness probe) before returning 0. Without it, helm is "done" the moment it sends the YAML, and nobody notices a CrashLooping pod.
- `--rollback-on-failure`: (Helm 4; replaces Helm 3's `--atomic`) if `--wait` times out or fails, helm **automatically goes back to the previous revision**. The cluster is never left half-deployed. Your local Helm is `v4.2.2`; `helm upgrade --help` lists the flag and notes that it turns on `--wait` (strategy `watcher`) by itself.
- `--timeout "${TIMEOUT:-10m}"`: 10 minutes by default; the drill in stage D sets `TIMEOUT=2m` to save time.
- Smoke test with `kubectl run --rm -i --restart=Never`: a throwaway curl pod running **inside** the cluster calls the internal Service directly, e.g. `http://order-service:8081/api/orders/actuator/health/readiness`. No need to go through the NLB/gateway. `--quiet` keeps the log clean; `$$` (the PID) avoids name clashes when two runs are close together.
- `port` and `ctx` come from `helm get values --all -o json`: the chart itself says which port/context path it deployed, so the script doesn't hardcode a port table. One source of truth. The keys are top-level in the chart's `values.yaml` (`port: 8080`, `contextPath: ""`), overridden per service in `values/<svc>-eks.yml` (order: `port: 8081`, `contextPath: /api/orders`). That's why the `jq` paths are `.port` and `.contextPath`. There is no `service.port` key; `templates/service.yml` uses `.Values.port` too.
- The probes in `templates/deployment.yml` already call `{{ .Values.contextPath }}/actuator/health/readiness` on `{{ .Values.port }}`, and the chart turns the endpoint on with `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`. The smoke test calls the same URL Kubernetes uses.

## B5. Try it from the laptop (cluster running)

Whenever dev-eks exists:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure

REGISTRY="$(cd envs/shared && terraform output -raw ecr_registry)"
export REGISTRY

./scripts/deploy-service.sh order                 # newest tag in ECR
./scripts/deploy-service.sh order sha-abc123...   # or a specific tag
```

---

# STAGE C — Deploy workflow (app repo)

## C1. Design: a separate, callable workflow

Deploying lives in its own workflow file, `deploy-dev.yml`, because it needs **two triggers**:

- `workflow_call`: called by each service's CI workflow (`order-ci.yml`, ... from week 13 stage D) as a child job after the build finishes (the automatic flow).
- `workflow_dispatch`: a manual button, "redeploy order with tag X", without rebuilding anything.

## C2. `.github/workflows/deploy-dev.yml` (app repo)

```yaml
name: deploy-dev

on:
  workflow_call:
    inputs:
      services:
        description: 'JSON array of services, e.g. ["order","payment"]'
        required: true
        type: string
      tag:
        description: 'Image tag; empty = newest tag in ECR'
        required: false
        type: string
        default: ''
  workflow_dispatch:
    inputs:
      services:
        description: 'JSON array of services'
        required: true
        type: string
        default: '["order","payment","inventory","notification"]'
      tag:
        description: 'Image tag; empty = newest tag in ECR'
        required: false
        type: string
        default: ''

permissions:
  contents: read

env:
  AWS_REGION: ap-southeast-1
  CLUSTER: jaka-mod-dev-eks

jobs:
  deploy:
    if: vars.EKS_DEV_UP == 'true'
    runs-on: ubuntu-latest
    environment: dev
    permissions:
      contents: read
      id-token: write
    strategy:
      fail-fast: false
      matrix:
        service: ${{ fromJSON(inputs.services) }}
    concurrency:
      group: deploy-dev-${{ matrix.service }}
    steps:
      - name: Checkout infra repo
        uses: actions/checkout@v7
        with:
          repository: ghtaidt9/jaka_infrastructure
          ref: master
          path: infra

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v6
        with:
          role-to-assume: ${{ vars.AWS_DEPLOY_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      - uses: azure/setup-helm@v5
        with:
          version: v4.2.2

      - name: Update kubeconfig
        run: aws eks update-kubeconfig --name "$CLUSTER" --region "$AWS_REGION"

      - name: Compute ECR registry
        run: |
          acct="$(aws sts get-caller-identity --query Account --output text)"
          echo "REGISTRY=${acct}.dkr.ecr.${AWS_REGION}.amazonaws.com" >> "$GITHUB_ENV"

      - name: Deploy
        env:
          TAG: ${{ inputs.tag }}
        run: |
          chmod +x infra/scripts/deploy-service.sh
          infra/scripts/deploy-service.sh "${{ matrix.service }}" "$TAG"
```

Explanation:

- `workflow_call` with `inputs`: turns the workflow into a "function" with parameters. Note that `workflow_call` inputs only support `string/number/boolean`, no arrays, so `services` is a **JSON string** that's `fromJSON`-ed inside.
- `if: vars.EKS_DEV_UP == 'true'`: the switch for the ephemeral cluster (problem #4). If the variable doesn't exist, the expression is an empty string ≠ 'true' → the job is skipped and CI stays green. Set it to `true` when the cluster is up; turn it off when you destroy it.
- `environment: dev`: the most important line in the file. Without it, the token's `sub` claim is `...:ref:refs/heads/master`, which **doesn't match** the `...:environment:dev` trust policy from A2 → AssumeRole fails. (On the first run, GitHub creates the `dev` environment in Settings automatically.)
- `concurrency: deploy-dev-<svc>`: two deploys of **the same service** must not overlap (helm would fail with "another operation in progress"); different services still run in parallel.
- Checkout `repository: ghtaidt9/jaka_infrastructure` into `path: infra`: the chart and script live in the infra repo; it's public (`https://api.github.com/repos/ghtaidt9/jaka_infrastructure` answers without a token), so checking it out needs no extra token. It reads GitHub's `master`; see the prerequisite at the top.
- `Compute ECR registry`: gets the account ID from `sts get-caller-identity` **after** assuming the role and writes it to `$GITHUB_ENV` for later steps. That way the account ID never appears in the public YAML.
- `azure/setup-helm@v5` + `version: v4.2.2`: pins the same Helm version you use locally. `--rollback-on-failure` is a Helm 4 flag, and the `ubuntu-24.04` runner image (what `ubuntu-latest` points to) ships Helm 3.22.1, according to its published software list.

## C3. Hook it into each service's CI workflow

Add a second job to `.github/workflows/order-ci.yml`, after the `build` job:

```yaml
  deploy-dev:
    needs: build
    if: github.event_name == 'push'
    uses: ./.github/workflows/deploy-dev.yml
    permissions:
      contents: read
      id-token: write
    with:
      services: '["order"]'
      tag: sha-${{ github.sha }}
```

Do the same in `payment-ci.yml`, `inventory-ci.yml` and `notification-ci.yml`; only `services` changes (`'["payment"]'`, ...).

- `uses: ./.github/workflows/deploy-dev.yml`: the syntax for calling a reusable workflow in the same repo. This job has no `steps`; its whole body lives in the other file.
- `needs: build`: runs only after this service's `build` job **succeeded** (a failed or cancelled build skips the deploy).
- `if: github.event_name == 'push'`: only deploy on a push to master; PRs stop at build.
- `services: '["order"]'`: `deploy-dev.yml` takes a JSON array (so the manual `workflow_dispatch` can deploy several services at once). From a service workflow it's always exactly that one service.
- The `permissions` declared here must be a superset of what the child workflow needs; `id-token: write` must be **handed down** explicitly, the same rule as for the `build` job.
- `tag: sha-${{ github.sha }}`: deploy exactly the image just built, not "the newest" (with two pushes close together, "the newest" might belong to the other push).
- The OIDC `sub` inside `deploy-dev.yml` is `repo:ghtaidt9/springboot_aws_compute_databases:environment:dev`: the **caller's** repo plus the environment of the job. That matches the A2 trust policy.

## C4. Set the variables

```bash
cd /home/taidt9/projects/springboot_aws_compute_databases

gh variable set AWS_DEPLOY_ROLE_ARN --body "arn:aws:iam::<ACCOUNT_ID>:role/jaka-mod-gha-deploy-dev"
gh variable set EKS_DEV_UP --body "false"    # keep false while the cluster is off
```

(Get the ARN from `terraform output gha_deploy_role_arn`.)

---

# STAGE D — End-to-end run + failure drills

## D1. Order of operations for the full flow

```bash
# 1) Build the cluster (shared was applied earlier)
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/envs/dev-eks
terraform apply -var-file terraform.tfvars

# 2) Build the in-cluster platform (now including the infra ConfigMaps)
cd ../.. && ./scripts/eks-up.sh

# 3) Turn on the deploy switch
gh variable set EKS_DEV_UP --body "true"

# 4) First deploy of all 4 services (newest tag in ECR) — manual trigger:
gh workflow run deploy-dev.yml \
  -f services='["order","payment","inventory","notification"]' -f tag=''
```

Then try the automatic flow: edit one line in order-service → commit → push to master → watch Actions: only the `order-ci` workflow starts → job `build / build` → job `deploy-dev / deploy (order)` → the smoke-test log `OK: order-service is ready.`

## D2. Drill 1 — a broken deploy, watch the automatic rollback

Deliberately deploy a tag that doesn't exist, with a short timeout so you don't wait long (from the laptop):

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
REGISTRY="$(cd envs/shared && terraform output -raw ecr_registry)" \
TIMEOUT=2m ./scripts/deploy-service.sh order sha-doesnotexist
```

Watch while it waits (in another terminal):

```bash
kubectl get pods -n dev -w
```

The new pod gets stuck in `ImagePullBackOff` → after 2 minutes helm prints "Deploy failed — helm rolled back" → the old pod **is still running and never stopped**. Verify:

```bash
helm history order-service -n dev
kubectl get pods -n dev
```

`helm history` shows the failed revision (status `failed`) and the newest rollback revision (`deployed`). The service never had a second of downtime, because a rolling update only removes old pods once new ones are ready, and the new pod never became ready.

> **About cleanliness:** this drill leaves nothing behind. The helm rollback returns the release to exactly its previous state, the broken pod is removed, and `helm history` only gains a log line (that's a feature, not clutter).

## D3. Drill 2 — manual rollback

The real-world case: the deploy *succeeded*, but 10 minutes later you find a logic bug. CI can't help here; this is when you roll back by hand:

```bash
helm history order-service -n dev          # find the last good revision
helm rollback order-service -n dev         # back to the previous revision
# or: helm rollback order-service <N> -n dev
kubectl rollout status deployment order-service -n dev
```

`helm rollback` doesn't need the old image anywhere but ECR: it re-applies the old revision's manifest (which includes the old tag). That's one more reason tags must be **immutable**: revision 3's `sha-abc` is guaranteed to still be that image.

## D4. Teardown at the end of a session

```bash
gh variable set EKS_DEV_UP --body "false"   # IMPORTANT: turn it off before destroying
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
kubectl delete gateway web -n dev
kubectl wait --for=delete svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web --timeout=5m
kubectl delete ns kafka
terraform -chdir=envs/dev-eks destroy -var-file terraform.tfvars
```

- If you forget `EKS_DEV_UP=false`, every push to master fails its deploy job (the cluster is gone). Nothing breaks, but a red CI is annoying.
- The 3 `kubectl` lines are THANG-3 stage 18's teardown order. The NLB (created by the LB controller for the gateway) and Kafka's EBS volume (created through its PVC) are made by controllers inside the cluster, so Terraform doesn't know them. Delete them while the controllers still run. Otherwise they stay, cost money, and `destroy` fails with `DependencyViolation` on the VPC.
- `envs/shared` **stays; don't destroy it**: ECR and the 2 roles cost almost nothing.

## D5. End-of-week self-check

1. Redraw from memory: push to master → which jobs run, which AWS role is assumed at which step, and what each role can do.
2. Answer: why does `environment: dev` change the `sub` claim? Why aren't "inside the cluster" permissions in the IAM policy?
3. Run drill 1 and explain why the service had no downtime.
4. Name 3 weaknesses of this push-based model (hints: who holds the credentials? Does a chart change get deployed? An image pushed while the cluster was off: who deploys it later?).

Question 4 is next week's problem statement.

---

## Next week

[WEEK 15](TUAN-15-CHI-TIET.md): turn the model into **pull-based GitOps**: ArgoCD sits inside the cluster and pulls state from git; CI's only job is to write "the new tag" into the infra repo. Plus Prometheus + Grafana: see how the 4 services are actually doing.
