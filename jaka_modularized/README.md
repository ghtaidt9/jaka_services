# jaka_modularized — Terraform, module edition

The same AWS platform as `../jaka_aws_networking` (two-AZ VPC, ALB with path routing, one ASG + one RDS Postgres per service, shared ElastiCache Redis, S3, ECR, Secrets Manager + KMS, CloudWatch alarms + dashboard), rebuilt **from scratch** as reusable modules with dev/prod environments, remote state, tests and CI.

`jaka_aws_networking` is untouched and keeps working. This is a separate project with its own state and its own resource names (`jaka-mod-<env>-*`), so both can live in the same AWS account.

---

## Contents

- [Layout](#layout)
- [Why these module boundaries](#why-these-module-boundaries)
- [Data flow between modules](#data-flow-between-modules)
- [dev vs prod](#dev-vs-prod)
- [State: where it lives and how it is locked](#state-where-it-lives-and-how-it-is-locked)
- [Tests without AWS](#tests-without-aws)
- [Runbook](#runbook)
- [Differences from jaka_aws_networking](#differences-from-jaka_aws_networking)

---

## Layout

```
jaka_modularized/
├── bootstrap/              # S3 bucket for remote state (local state, run once)
├── modules/                # building blocks - know nothing about "order" or "payment"
│   ├── network/            # VPC, subnets, IGW, NAT, route tables, 4 security groups
│   ├── security/           # KMS key for secrets, SSM-caller IAM user
│   ├── storage/            # shared S3 bucket (per-service prefixes)
│   ├── alb/                # ALB + :80 listener (404 default)
│   ├── postgres/           # ONE service database: RDS, param group, log group, app secret, JDBC URL param
│   ├── redis/              # shared ElastiCache replication group + endpoint params + AUTH secret
│   ├── service/            # ONE microservice: ECR, IAM, target group, route, launch template, ASG
│   └── observability/      # SNS, every alarm, RDS events, dashboard
├── stack/                  # composition: wires the modules; for_each over var.services
├── envs/
│   ├── dev/                # calls stack with cheap settings; own backend key
│   └── prod/               # calls stack with HA settings; own backend key
├── localstack/             # docker-compose for LocalStack
├── .github/workflows/      # fmt / test / validate / tflint / checkov
└── .tflint.hcl
```

Each module has `variables.tf` (every variable has a `description`), `outputs.tf`, `versions.tf` and `tests/*.tftest.hcl`.

---

## Why these module boundaries

| Module | Boundary rule | Instances |
|---|---|---|
| `network` | Changes rarely; everything else sits inside it | 1 |
| `security` | KMS key is shared by DBs, Redis and every service role | 1 |
| `storage` | One bucket, prefix per service | 1 |
| `alb` | Shared entry point; services attach their own rules | 1 |
| `postgres` | **Database-per-service**: one module call = one service's DB | 1 per service |
| `redis` | One shared cluster, a DB index per service | 1 |
| `service` | Everything one microservice owns | 1 per service |
| `observability` | Depends on everything, nothing depends on it | 1 |

The payoff is `stack/main.tf`:

```hcl
module "postgres" { for_each = var.services ... }
module "service"  { for_each = var.services ... }
```

In the flat project, order and payment were copy-pasted resource pairs (`order_lt`/`payment_lt`, `order_asg`/`payment_asg`, 2 × each alarm...). Here, adding `inventory-service` (week 9) is **one map entry** in `var.services`. Its DB, ASG, route, IAM role, alarms and dashboard lines follow automatically. `stack/tests/stack.tftest.hcl` proves this with the `third_service_is_one_map_entry` test.

---

## Data flow between modules

```
network ──(vpc_id, private_subnet_ids, sg ids)──► alb, postgres, redis, service
security ─(kms_key_arn / kms_key_id)────────────► postgres, redis, service
storage ──(bucket_arn)──────────────────────────► service (IAM: only <service>s/*)
alb ──────(http_listener_arn)───────────────────► service (adds its path rule)
postgres ─(host, secrets, url param)────────────► service (user-data + IAM)
redis ────(param names, auth secret)────────────► service (user-data + IAM)
service ──(asg name, tg suffix, log group)──────► observability
postgres ─(identifier)──────────────────────────► observability
redis ────(cluster_ids)─────────────────────────► observability
alb ──────(arn_suffix)──────────────────────────► observability
```

Ordering without `depends_on` across modules: `postgres.app_secret_arn` and `redis.auth_secret_arn` are read from the **secret version** resource, so a launch template can't be created before the secret has a value (the flat project needed an explicit `depends_on` for this).

---

## dev vs prod

`diff envs/dev/main.tf envs/prod/main.tf` shows the whole list:

| Setting | dev | prod |
|---|---|---|
| NAT gateways | 1 shared | 1 per AZ |
| `instance_type` | `t3.micro` | `t3.small` |
| ASG min / max / desired | 1 / 2 / 1 | 2 / 6 / 2 |
| RDS Multi-AZ | `false` | `true` |
| RDS backup retention | 1 day | 7 days |
| RDS final snapshot / deletion protection | skip / off | take / on |
| Redis nodes | 1 (no failover) | 2 (failover + Multi-AZ) |
| S3 `force_destroy` | `true` | `false` |
| Secret recovery window | 0 (delete now) | 7 days |
| Log retention | 7 days | 30 days |

`environment` is validated: anything other than `dev`/`prod` fails at plan time.

---

## State: where it lives and how it is locked

- **Where**: S3 bucket `jaka-mod-tfstate-<account_id>`, created by `bootstrap/`. Keys `dev/terraform.tfstate` and `prod/terraform.tfstate`, so a broken dev apply can't touch prod state.
- **Lock**: `use_lockfile = true` (Terraform >= 1.10). Terraform writes `<key>.tflock` next to the state; no DynamoDB table.
- **Two people apply at once**: the second one gets `Error acquiring the state lock` and waits or stops. Nothing is written.
- **Protection**: bucket versioning (roll back a bad state), SSE, public access blocked, HTTPS-only policy, and `prevent_destroy` on the bucket.
- **Chicken and egg**: the bucket can't hold the state of its own creation, so `bootstrap/` keeps local state. It is tiny, only changes once, and can be re-imported if lost.

---

## Tests without AWS

Every module and `stack/` has `tests/*.tftest.hcl` using `mock_provider "aws" {}` + `command = plan`, so they need no credentials and create nothing.

| Suite | Checks |
|---|---|
| network | 1 public + 1 private subnet per AZ, NAT per AZ vs single, RDS/Redis SG reference EC2 SG (no CIDR), SSH closed by default, bad CIDR rejected |
| security | KMS rotation on, SSM user optional, deletion window validated |
| storage | private + encrypted + versioned, 1 lifecycle rule per prefix, `force_destroy` off by default |
| alb | internet-facing, unknown path → 404, needs 2 subnets |
| postgres | not public, encrypted, `rds.force_ssl=1`, naming, prod-safe defaults, SQL-unsafe username rejected |
| redis | encrypted, failover only with 2+ nodes, cluster ID naming |
| service | private subnets only, IMDSv2, routing from inputs, IAM scoped to `/<name_prefix>/` params, explicit AMI skips lookup |
| observability | 1 alarm set per service/DB/node, every alarm notifies SNS, dashboard scales with services |
| stack | 2 services → 2 DBs + 2 ASGs, a 3rd service is one map entry, `staging` rejected, duplicate listener priority rejected |

---

## Runbook

Every step starts from `jaka_modularized/`. Use the **space form** of flags (`-var-file terraform.tfvars`, `-backend-config backend.hcl`); the installed `terraform.exe` misparses the `=` form.

### 1. Run the tests (no AWS, no LocalStack)

```bash
for d in modules/* stack; do (cd $d && terraform init -input=false >/dev/null && terraform test); done
```

### 2. LocalStack (optional)

Start LocalStack (stop the one in `../jaka_aws_networking/localstack` first; same container name and port):

```bash
cd localstack
docker compose up -d
cd ..
```

Point the AWS provider and CLI at it:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=ap-southeast-1
```

Find an AMI ID and put it in `envs/dev/localstack.tfvars`:

```bash
aws ec2 describe-images --owners 099720109477 --query "Images[].[ImageId,Name]" --output table
```

### 3. Bootstrap the state bucket (once)

AWS:

```bash
cd bootstrap
terraform init
terraform apply
terraform output account_id
```

Then replace `<ACCOUNT_ID>` in `envs/dev/backend.hcl` and `envs/prod/backend.hcl`.

LocalStack:

```bash
cd bootstrap
terraform init
terraform apply -var use_localstack=true
```

### 4. dev

AWS:

```bash
cd envs/dev
terraform init -backend-config backend.hcl
terraform plan -var-file terraform.tfvars
terraform apply -var-file terraform.tfvars
```

LocalStack:

```bash
cd envs/dev
terraform init -backend-config backend.localstack.hcl
terraform plan -var-file terraform.tfvars -var-file localstack.tfvars
```

Check the state really is in S3:

```bash
aws s3 ls s3://jaka-mod-tfstate-000000000000 --recursive
```

### 5. Prove the lock

Run the same `plan` in two terminals at the same time. The second one fails with:

```
Error: Error acquiring the state lock
```

### 6. prod

Same as dev, from `envs/prod`. Every difference is in `envs/prod/main.tf`.

### 7. Teardown

```bash
cd envs/dev
terraform destroy -var-file terraform.tfvars
```

prod has deletion protection on RDS and `force_destroy = false` on S3, so `destroy` there stops on purpose. The state bucket has `prevent_destroy`.

---

## Differences from jaka_aws_networking

Intentional changes, not just a file reshuffle:

| Area | jaka_aws_networking | jaka_modularized | Why |
|---|---|---|---|
| Names | `vpc-lab-dev-*`, SSM `/dev/...`, secrets `dev/...`, ECR `order-service`, logs `/microservice/order-service` | everything under `jaka-mod-<env>` | Both projects (and dev + prod) can live in one account without name clashes |
| Order / payment | copy-pasted resource pairs | `for_each` over `var.services` | A new service = one map entry |
| ALB default action | forward to order TG | fixed 404 | Unknown paths shouldn't hit order-service; ALB doesn't need to know any service |
| SSH rule | `0.0.0.0/0:22` by default | none unless `ssh_allowed_cidr` is set | Instances are private and reached through SSM |
| Instance role SSM access | wildcard `/dev/order-*`, `/dev/redis-*` | exact list of the 4 parameters the bootstrap reads | Least privilege |
| NAT gateways | always 2 | 1 in dev, 2 in prod | Cost |
| Redis | min 2 nodes (validation) | 1 in dev (failover auto-off), 2 in prod | Cost; failover is derived from node count |
| RDS | always Multi-AZ, no deletion protection | per env | dev cheap, prod safe |
| Log-error metrics namespace | `Microservices/Logs` | `Microservices/Logs/<name_prefix>` | dev and prod metrics don't mix |
| Subnets / NAT / route tables | `_1` / `_2` resources | `for_each` keyed by AZ | Adding an AZ doesn't renumber the others |
| LocalStack AMI | `*_override.tf` file | `ami_id` variable | No hidden override file |
| RDS log groups | `import` blocks (created by RDS first) | created before the DB in the module | Greenfield: no import needed |

Not ported: `scripts/` (build-and-push, deploy, rollback, smoke tests). They read `terraform output` from `jaka_aws_networking` and the `/dev/<svc>-image-tag` parameter names; here the outputs are maps (`ecr_repository_urls`, `image_tag_parameters`) and the parameters are `/jaka-mod-<env>/<svc>-image-tag`.
