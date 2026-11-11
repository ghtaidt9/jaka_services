# Manual build guide — THANG-1: rebuilding `jaka_modularized` by hand

A step-by-step specification for typing the whole project yourself, from an empty folder to the finished layout. It describes **what** to write and **how to check it**, not the code itself.

**Series:** **THANG-1 (this file)** · THANG-2 → [MANUAL-BUILD-GUIDE-THANG-2.md](MANUAL-BUILD-GUIDE-THANG-2.md) (Kubernetes on kind, Helm, Kafka) · THANG-3 → [MANUAL-BUILD-GUIDE-THANG-3.md](MANUAL-BUILD-GUIDE-THANG-3.md) (event-driven Saga, then EKS)

**Answer keys.** Open them only after you have tried a stage.

| Source | Notes |
|---|---|
| `git show fbfc964:jaka_modularized/<path>` | the original reference; the folder was deleted from the working tree in commit `21f55d6`, but git history still has it |
| `jaka_infrastructure/` | your own build of this guide (prefix `jaka-mod`, no `envs/prod`). It already carries THANG-3 changes: `modules/ecr` split out of `modules/service`, subnet tag variables in `modules/network`, and `>= 5.50` provider pins in the modules EKS reuses |

---

## Contents

- [TL;DR](#tldr)
- [The big picture (read this first)](#the-big-picture-read-this-first)
- [Why this order](#why-this-order)
- [Know before you start](#know-before-you-start)
- [Stage map](#stage-map)
- [The check loop](#the-check-loop)
- [Stage 0 — Skeleton](#stage-0--skeleton)
- [Stage 1 — modules/network](#stage-1--modulesnetwork)
- [Stage 2 — modules/security](#stage-2--modulessecurity)
- [Stage 3 — modules/storage](#stage-3--modulesstorage)
- [Stage 4 — modules/alb](#stage-4--modulesalb)
- [Stage 5 — modules/redis](#stage-5--modulesredis)
- [Stage 6 — modules/postgres](#stage-6--modulespostgres)
- [Stage 7 — modules/service](#stage-7--modulesservice)
- [Stage 8 — modules/observability](#stage-8--modulesobservability)
- [Stage 9 — stack](#stage-9--stack)
- [Stage 10 — bootstrap](#stage-10--bootstrap)
- [Stage 11 — envs/dev](#stage-11--envsdev)
- [Stage 12 — envs/prod](#stage-12--envsprod)
- [Stage 13 — Tooling](#stage-13--tooling)
- [Appendix A — Definition of done](#appendix-a--definition-of-done)
- [Appendix B — Resource-block count per folder](#appendix-b--resource-block-count-per-folder)
- [Appendix C — Concepts by stage](#appendix-c--concepts-by-stage)
- [Appendix D — Naming conventions](#appendix-d--naming-conventions)
- [Appendix E — Test gotchas](#appendix-e--test-gotchas)
- [Appendix F — Improvements to make while rebuilding](#appendix-f--improvements-to-make-while-rebuilding)
- [Appendix G — Not covered](#appendix-g--not-covered)

---

## TL;DR

- Build in a new sibling folder with its own project prefix, so nothing clashes with `jaka_infrastructure` (or anything else already applied in your AWS account).
- Go bottom-up in 14 stages: skeleton → 8 modules → `stack` → `bootstrap` → `envs` → tooling.
- Stages 0–9 need no AWS account and cost nothing; every module is checked with mock-provider tests.
- AWS (or LocalStack) is first needed at stage 10.

## The big picture (read this first)

The stages build bottom-up, but the finished project is easier to understand top-down. Keep this map open: every stage fills in one box.

### Who calls whom

```
envs/dev/main.tf                     ← root: picks sizes for dev, owns the backend (S3 state from bootstrap/)
└── module "stack"  (stack/)         ← the whole application, called once per environment
    ├── module "network"             VPC, 2 public + 2 private subnets, IGW, NAT, 4 security groups (alb, ec2, rds, redis)
    ├── module "security"            KMS key for secrets, IAM user allowed to start SSM sessions
    ├── module "storage"             S3 bucket shared by the services, one prefix per service
    ├── module "alb"                 public ALB + HTTP listener (services add their own path rules)
    ├── module "redis"               ElastiCache replication group shared by the services
    ├── module "postgres"  ×N        one RDS instance per service           (for_each = var.services)
    ├── module "service"   ×N        ECR, IAM role, launch template, ASG, target group, listener rule, log group
    │                                                                       (for_each = var.services)
    └── module "observability"       SNS topic, CloudWatch alarms, dashboard over everything above

bootstrap/                           ← separate root, applied once: the S3 bucket that holds every env's state
envs/prod/                           ← same "stack" call as dev, different sizes (stage 12)
```

`envs/dev` contains almost no resources of its own. It only decides **how big**; `stack` decides **what exists**; each module decides **how one piece is built**.

### What flows between modules

Inside `stack/main.tf`, modules never see each other's resources. They only receive another module's **outputs** as inputs:

| From | Output | To |
|---|---|---|
| network | `public_subnet_ids`, `alb_sg_id` | alb |
| network | `private_subnet_ids`, `redis_sg_id` | redis |
| network | `private_subnet_ids`, `rds_sg_id` | postgres |
| network | `vpc_id`, `private_subnet_ids` | service |
| security | `kms_key_arn` | redis, postgres, service |
| security | `kms_key_id` | postgres |
| storage | `bucket_arn` | service |
| alb | `http_listener_arn` | service |
| postgres[svc] | `address`, `port`, `db_name`, usernames, secret ARNs, `db_url_parameter_name` | service[svc] |
| redis | `primary_endpoint_parameter_name`, `port_parameter_name`, `auth_secret_arn` | service |
| service[svc] | `asg_name`, `target_group_arn_suffix`, `log_group_name`, `container_name` | observability |
| postgres, redis, alb | `identifier`, `cluster_ids`, `arn_suffix` | observability |

Reading the table top to bottom gives the dependency order: `network`, `security` and `storage` need nothing; `service` needs almost everything; `observability` watches everything.

### Why the build order is the reverse of the map

- A module can only be tested once the modules it depends on have defined their outputs. So the leaves (`network`, `security`, `storage`) come first and `stack` comes last, even though `stack` is what you read first.
- After each stage, come back to this map and to the table above: the outputs you just wrote must match what the next module expects as inputs.
- If you would rather start from something that runs and split it into modules later, build only `envs/dev` with flat resources for one service, add a second service, and extract `modules/service` when the duplication shows (use `moved {}` blocks so Terraform does not recreate anything). That top-down path suits work you have not designed yet, such as `envs/prod` or `envs/prod-eks`.

## Why this order

- A module can only be tested once its own inputs are understood, so leaf modules come first.
- `service` and `observability` come last among the modules because they consume outputs of all the others.
- `bootstrap` is delayed until just before `envs`, because it is the first step that needs credentials.

## Know before you start

| Tradeoff | Consequence |
|---|---|
| The state bucket is created by a separate `bootstrap` project | One manual step: paste the account ID into each `backend.hcl` |
| The CI workflow assumes the folder is its own git repo root | Run `git init` in stage 0, or the workflow never runs |
| Tests use `mock_provider` | They prove structure and validations, not that AWS accepts the config |
| Deploy scripts are not part of this build | After stage 11 the infra exists, but nothing updates image tags |
| Applying dev creates billable resources | NAT gateway, 2 RDS instances, ElastiCache, EC2; destroy when finished |

## Stage map

| Stage | Folder | Consumes | Needs AWS |
|---|---|---|---|
| 0 | repo skeleton | — | no |
| 1 | `modules/network` | — | no |
| 2 | `modules/security` | — | no |
| 3 | `modules/storage` | — | no |
| 4 | `modules/alb` | network | no |
| 5 | `modules/redis` | network, security | no |
| 6 | `modules/postgres` | network, security | no |
| 7 | `modules/service` | network, alb, postgres, redis, security, storage | no |
| 8 | `modules/observability` | service, postgres, redis, alb | no |
| 9 | `stack` | all modules | no |
| 10 | `bootstrap` | — | yes |
| 11 | `envs/dev` | stack, bootstrap | yes |
| 12 | `envs/prod` | stack, bootstrap | yes |
| 13 | tooling | — | no |

## The check loop

Run from inside the module folder after every stage. The stage is done when all four pass.

```bash
terraform fmt
terraform init -input=false
terraform validate
terraform test
```

---

## Stage 0 — Skeleton

**Goal:** an empty repo with the final folder shape.

### Commands

```bash
mkdir jaka_manual
cd jaka_manual
git init
mkdir -p modules/network/tests modules/security/tests modules/storage/tests modules/alb/tests modules/postgres/tests modules/redis/tests modules/service/tests modules/service/templates modules/observability/tests stack/tests bootstrap envs/dev envs/prod localstack .github/workflows
```

### Decide now

- Your project prefix, for example `jaka-hand`. Every resource name will be `<project>-<environment>-...`.
- This guide writes `<prefix>` for that combined value (for example `jaka-hand-dev`).

### `.gitignore`

Ignore these:

- `.terraform/`
- `*.tfstate` and `*.tfstate.*`
- `crash.log`, `crash.*.log`
- `override.tf`, `override.tf.json`, `*_override.tf`, `*_override.tf.json`
- `*.auto.tfvars`, `*.auto.tfvars.json`
- `*.plan`, `*.tfplan`
- `localstack/volume/`, `localstack/.env`

Do **not** ignore `.terraform.lock.hcl`. The reference does, and that is a mistake: lock files pin provider versions across machines and CI.

### The pattern every module repeats

| File | Holds |
|---|---|
| `versions.tf` | `required_version` and `required_providers` |
| `variables.tf` | every variable with a `description` and a `type` |
| `main.tf` | resources (split into more files when large) |
| `outputs.tf` | every output with a `description` |
| `tests/<module>.tftest.hcl` | mock providers and `run` blocks with `command = plan` |

### `versions.tf` values

| Folder | `required_version` | Providers |
|---|---|---|
| network, security, storage, alb, service, observability | `>= 1.9.0` | `hashicorp/aws ~> 5.50` |
| postgres, redis, stack | `>= 1.9.0` | aws `~> 5.50`, `hashicorp/random ~> 3.6` |
| bootstrap | `>= 1.10.0` | aws `~> 5.50` |
| envs/dev, envs/prod | `>= 1.10.0` | aws `~> 5.50`, random `~> 3.6` |

Modules never contain a `provider` block. Only `bootstrap` and `envs/*` do.

THANG-3 (stage 9) changes `~> 5.50` to `>= 5.50` in the modules the EKS root reuses (`network`, `security`, `storage`, `redis`, `postgres`), because the EKS community module needs AWS provider 6. The rule behind it: modules declare a minimum, roots pin. You can type `>= 5.50` in those five modules now and save the edit later.

---

## Stage 1 — `modules/network`

**Goal:** VPC, subnets, internet gateway, NAT, route tables, and the four security groups.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `security_groups.tf`, `outputs.tf`, `tests/network.tftest.hcl`.

### Variables

| Name | Type | Default | Validation |
|---|---|---|---|
| `name_prefix` | string | — | — |
| `vpc_cidr` | string | — | `can(cidrhost(var.vpc_cidr, 0))` |
| `azs` | list(string) | — | length ≥ 2 |
| `public_subnet_cidrs` | list(string) | — | length equals length of `azs` |
| `private_subnet_cidrs` | list(string) | — | length equals length of `azs` |
| `single_nat_gateway` | bool | `false` | — |
| `app_port_range` | object({from, to}) of numbers | 8080 / 8081 | — |
| `redis_port` | number | `6379` | — |
| `ssh_allowed_cidr` | string | `null` | — |

### Locals

| Local | Meaning |
|---|---|
| `az_index` | map of AZ name → its index in `var.azs` |
| `nat_azs` | only the first AZ when `single_nat_gateway` is true, otherwise `az_index` |

### `main.tf` — resources in typing order

| # | Resource | Local name | Iteration | Key arguments |
|---|---|---|---|---|
| 1 | `aws_vpc` | `this` | — | DNS support and DNS hostnames on; tag `Name = <prefix>-vpc` |
| 2 | `aws_subnet` | `public` | `for_each = az_index` | CIDR by index, AZ = key, `map_public_ip_on_launch = true`; tags `Name = <prefix>-public-<n>`, `Tier = public` |
| 3 | `aws_subnet` | `private` | `for_each = az_index` | same without public IP; tags `Name = <prefix>-private-<n>`, `Tier = private` |
| 4 | `aws_internet_gateway` | `this` | — | tag `<prefix>-igw` |
| 5 | `aws_eip` | `nat` | `for_each = nat_azs` | `domain = "vpc"`; `depends_on` the IGW |
| 6 | `aws_nat_gateway` | `this` | `for_each = nat_azs` | EIP and public subnet of the same key; `depends_on` the IGW |
| 7 | `aws_route_table` | `public` | — | tag `<prefix>-public-rt` |
| 8 | `aws_route` | `public_internet` | — | `0.0.0.0/0` → IGW |
| 9 | `aws_route_table_association` | `public` | `for_each = aws_subnet.public` | every public subnet → public table |
| 10 | `aws_route_table` | `private` | `for_each = az_index` | tag `<prefix>-private-rt-<n>` |
| 11 | `aws_route` | `private_nat` | `for_each = az_index` | `0.0.0.0/0` → NAT of the first AZ when single, else NAT of its own AZ |
| 12 | `aws_route_table_association` | `private` | `for_each = aws_subnet.private` | private subnet → private table of the same key |

`<n>` is the index plus one.

### `security_groups.tf`

| Group | Name | Rules |
|---|---|---|
| `alb` | `<prefix>-alb-sg` | ingress tcp 80 and tcp 443 from `0.0.0.0/0`; egress all |
| `ec2` | `<prefix>-ec2-sg` | ingress tcp `app_port_range` from the ALB group; ingress tcp 22 from `ssh_allowed_cidr` only when not null; egress all |
| `rds` | `<prefix>-rds-sg` | ingress tcp 5432 from the EC2 group |
| `redis` | `<prefix>-redis-sg` | ingress tcp `redis_port` from the EC2 group |

- Use `aws_vpc_security_group_ingress_rule` and `aws_vpc_security_group_egress_rule`, one resource per rule.
- Group-to-group rules use `referenced_security_group_id`; internet rules use `cidr_ipv4`.
- Egress-all uses `ip_protocol = "-1"`.
- The SSH rule uses `count` so it disappears when the CIDR is null.

### Outputs

| Name | Value |
|---|---|
| `vpc_id` | VPC ID |
| `public_subnet_ids` | list built by looping over `var.azs` (stable order) |
| `private_subnet_ids` | same for private |
| `nat_public_ips` | public IPs of the EIPs |
| `alb_sg_id`, `ec2_sg_id`, `rds_sg_id`, `redis_sg_id` | the four group IDs |

### Tests

Setup: `mock_provider "aws" {}`; variables with prefix `jaka-test`, `10.0.0.0/16`, two AZs, two public and two private CIDRs.

| Run | Asserts |
|---|---|
| `one_public_and_one_private_subnet_per_az` | 2 public and 2 private subnets; no private subnet auto-assigns public IPs |
| `nat_per_az_by_default` | 2 NAT gateways |
| `single_nat_gateway_for_dev` | with the flag on: 1 NAT, still 2 private routes |
| `data_tier_not_reachable_from_internet` | RDS and Redis ingress rules have null `cidr_ipv4` |
| `no_ssh_rule_unless_asked` | SSH rule count is 0 |
| `invalid_cidr_is_rejected` | `expect_failures = [var.vpc_cidr]` |
| `subnet_count_must_match_azs` | `expect_failures = [var.private_subnet_cidrs]` |

### Concepts

- `for_each` keyed by AZ name, not `count`, so adding an AZ never renumbers existing subnets.
- A conditional expression inside a resource argument.
- `count` as an on/off switch.

---

## Stage 2 — `modules/security`

**Goal:** the KMS key for every application secret, and the optional IAM user allowed to open SSM sessions.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `tests/security.tftest.hcl`.

### Variables

| Name | Type | Default | Validation |
|---|---|---|---|
| `name_prefix` | string | — | — |
| `kms_deletion_window_in_days` | number | `7` | between 7 and 30 |
| `create_ssm_caller_user` | bool | `true` | — |

### Resources

| # | Resource | Local name | Key arguments |
|---|---|---|---|
| 1 | `aws_kms_key` | `secrets` | description, `enable_key_rotation = true`, deletion window from the variable |
| 2 | `aws_kms_alias` | `secrets` | `alias/<prefix>-secrets` → the key |
| 3 | `aws_iam_user` | `ssm_caller` | `count`; name `<prefix>-ssm-caller`, path `/` |
| 4 | `aws_iam_policy` | `ssm_caller` | `count`; name `<prefix>-ssm-caller-policy`; three statements below |
| 5 | `aws_iam_user_policy_attachment` | `ssm_caller` | `count`; attaches 4 to 3 |

### SSM caller policy statements

| Sid | Actions | Resource | Condition |
|---|---|---|---|
| `AllowSSMStartOnTaggedInstances` | `ssm:StartSession`, `ssm:TerminateSession`, `ssm:DescribeInstanceInformation` | `*` | `StringEquals` on `ec2:ResourceTag/SSMAccess` = `true` |
| `AllowSSMMessagesChannels` | the four `ssmmessages:` create/open control and data channel actions | `*` | — |
| `DescribeNeededResources` | `ssm:DescribeInstanceInformation`, `ec2:DescribeInstances` | `*` | — |

No access key is created, so no secret lands in state.

### Outputs

| Name | Value |
|---|---|
| `kms_key_arn` | key ARN |
| `kms_key_id` | key ID |
| `ssm_caller_username` | the user name, or null when not created (use `one()` on a splat) |

### Tests

| Run | Asserts |
|---|---|
| `kms_key_rotates` | rotation is on; alias is `alias/jaka-test-secrets` |
| `ssm_caller_user_is_optional` | with the flag off: 0 users and 0 policies |
| `deletion_window_out_of_range_is_rejected` | value 3 fails on `var.kms_deletion_window_in_days` |

### Concepts

- `count = condition ? 1 : 0` and the `[0]` index that follows.
- `one(resource[*].attr)` to turn a 0-or-1 list into a value or null.

---

## Stage 3 — `modules/storage`

**Goal:** one private, versioned, encrypted bucket with a tiering rule per service prefix.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `tests/storage.tftest.hcl`.

### Variables

| Name | Type | Default | Validation |
|---|---|---|---|
| `name_prefix` | string | — | — |
| `tiered_prefixes` | list(string) | — | — |
| `force_destroy` | bool | `false` | — |
| `ia_transition_days` | number | `30` | ≥ 30 |
| `glacier_transition_days` | number | `90` | — |
| `noncurrent_version_expiration_days` | number | `90` | — |

### Resources

| # | Resource | Key arguments |
|---|---|---|
| — | `data "aws_caller_identity" "current"` | for the account ID in the bucket name |
| 1 | `aws_s3_bucket.this` | name `<prefix>-microservices-<account_id>`; `force_destroy` from the variable |
| 2 | `aws_s3_bucket_versioning.this` | status `Enabled` |
| 3 | `aws_s3_bucket_server_side_encryption_configuration.this` | `AES256` |
| 4 | `aws_s3_bucket_public_access_block.this` | all four flags true |
| 5 | `aws_s3_bucket_policy.this` | one `Deny` statement `DenyInsecureTransport` on the bucket and `bucket/*` when `aws:SecureTransport` is false; `depends_on` the public access block |
| 6 | `aws_s3_bucket_lifecycle_configuration.this` | a `dynamic "rule"` per prefix |

### Lifecycle rule (one per prefix)

- `id` is `<prefix-name>-tiering`, status `Enabled`.
- `filter` with prefix `<prefix-name>/`.
- Transition to `STANDARD_IA` after `ia_transition_days`.
- Transition to `GLACIER` after `glacier_transition_days`.
- `noncurrent_version_expiration` after `noncurrent_version_expiration_days`.

### Outputs

`bucket_name`, `bucket_arn`.

### Tests

Setup: mock the caller identity with account `123456789012`; prefixes `orders` and `payments`.

| Run | Asserts |
|---|---|
| `bucket_is_private_encrypted_and_versioned` | bucket name matches the convention; all four public-access flags true; versioning `Enabled` |
| `one_lifecycle_rule_per_prefix` | 2 rules |
| `prod_default_keeps_objects_on_destroy` | `force_destroy` is false |
| `ia_transition_too_early_is_rejected` | value 7 fails on `var.ia_transition_days` |

### Concepts

- `dynamic` blocks over `toset(list)`.
- `mock_data` to give a data source a predictable value.

---

## Stage 4 — `modules/alb`

**Goal:** the shared entry point, which knows nothing about any service.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `tests/alb.tftest.hcl`.

### Variables

| Name | Type | Validation |
|---|---|---|
| `name_prefix` | string | — |
| `public_subnet_ids` | list(string) | length ≥ 2 |
| `security_group_id` | string | — |

### Resources

| # | Resource | Key arguments |
|---|---|---|
| 1 | `aws_lb.this` | name `<prefix>-alb`, `internal = false`, type `application`, the security group, the public subnets, `drop_invalid_header_fields = true` |
| 2 | `aws_lb_listener.http` | port 80, protocol `HTTP`; default action `fixed-response`: content type `application/json`, body `{"error":"no route"}`, status `404` |

### Outputs

`arn`, `arn_suffix`, `dns_name`, `http_listener_arn`.

### Tests

| Run | Asserts |
|---|---|
| `public_alb_with_404_default` | not internal; default fixed response status is `404` |
| `single_subnet_is_rejected` | one subnet fails on `var.public_subnet_ids` |

### Concept

Services attach their own listener rules later, so this module never changes when a service is added.

---

## Stage 5 — `modules/redis`

**Goal:** the shared ElastiCache replication group, with its connection info published for the apps.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `tests/redis.tftest.hcl`.

### Variables

| Name | Type | Default | Validation |
|---|---|---|---|
| `name_prefix` | string | — | — |
| `description` | string | `Shared Redis cache for the microservices` | — |
| `private_subnet_ids` | list(string) | — | — |
| `security_group_id` | string | — | — |
| `kms_key_arn` | string | — | — |
| `engine_version` | string | `7.1` | — |
| `node_type` | string | `cache.t3.micro` | — |
| `port` | number | `6379` | — |
| `num_cache_clusters` | number | `2` | between 1 and 6 |
| `secret_recovery_window_in_days` | number | `7` | — |

### Locals

| Local | Meaning |
|---|---|
| `replication_group_id` | `<prefix>-redis` |
| `ha` | `num_cache_clusters >= 2` |
| `cluster_ids` | list of `<replication_group_id>-001`, `-002`, … built with `range` and `format("%03d", ...)` |

### Resources

| # | Resource | Key arguments |
|---|---|---|
| 1 | `random_password.auth` | length 32, no special characters |
| 2 | `aws_elasticache_subnet_group.this` | name `<prefix>-redis-subnet-group` |
| 3 | `aws_elasticache_replication_group.this` | engine `redis`, version, node type, port, node count; failover and multi-AZ both from `local.ha`; at-rest and transit encryption on; `auth_token` from the password; subnet group; security group; `apply_immediately = true` |
| 4 | `aws_ssm_parameter.primary_endpoint` | `/<prefix>/redis-primary-endpoint`, type `String` |
| 5 | `aws_ssm_parameter.reader_endpoint` | `/<prefix>/redis-reader-endpoint` |
| 6 | `aws_ssm_parameter.port` | `/<prefix>/redis-port`, value via `tostring` |
| 7 | `aws_secretsmanager_secret.auth` | name `<prefix>/redis/auth-token`, KMS key, recovery window |
| 8 | `aws_secretsmanager_secret_version.auth` | the password as the secret string |

### Outputs

| Name | Value |
|---|---|
| `primary_endpoint`, `reader_endpoint`, `port` | from the replication group |
| `cluster_ids` | `local.cluster_ids` (known at plan time) |
| `primary_endpoint_parameter_name`, `reader_endpoint_parameter_name`, `port_parameter_name` | the SSM parameter names |
| `auth_secret_arn` | the ARN of the secret **version**, not the secret |

### Tests

Setup: `mock_provider "aws" {}` and `mock_provider "random" {}`.

| Run | Asserts |
|---|---|
| `encrypted_with_auth` | both encryption flags true (wrap in `tobool`, the at-rest flag is a string in provider v5) |
| `two_nodes_enable_failover` | failover and multi-AZ on; `output.cluster_ids` equals the two expected IDs |
| `single_node_for_dev` | with 1 node: failover and multi-AZ off |
| `zero_nodes_is_rejected` | 0 fails on `var.num_cache_clusters` |

### Concepts

- Deriving a setting from another (`ha`) instead of exposing two variables that can contradict each other.
- An output read from the secret version forces consumers to wait until the secret has a value, without any cross-module `depends_on`.

---

## Stage 6 — `modules/postgres`

**Goal:** one service's database. The stack calls this module once per service.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `tests/postgres.tftest.hcl`.

### Variables

| Name | Type | Default | Validation |
|---|---|---|---|
| `name_prefix` | string | — | — |
| `service` | string | — | regex `^[a-z][a-z0-9]*$` |
| `db_name` | string | — | — |
| `admin_username` | string | — | — |
| `app_username` | string | — | regex `^[a-z_][a-z0-9_]*$` |
| `private_subnet_ids` | list(string) | — | — |
| `security_group_id` | string | — | — |
| `kms_key_id` | string | — | — |
| `kms_key_arn` | string | — | — |
| `engine_version` | string | `16` | — |
| `instance_class` | string | `db.t3.micro` | — |
| `allocated_storage` | number | `20` | — |
| `multi_az` | bool | `true` | — |
| `backup_retention_period` | number | `1` | — |
| `skip_final_snapshot` | bool | `false` | — |
| `deletion_protection` | bool | `true` | — |
| `log_retention_days` | number | `7` | — |
| `admin_secret_rotation_days` | number | `30` | — |
| `secret_recovery_window_in_days` | number | `7` | — |

Defaults are the production-safe ones; dev overrides them.

### Locals

| Local | Meaning |
|---|---|
| `identifier` | `<prefix>-<service>-db` |
| `parameter_group_family` | `postgres` + the major version, taken with `split(".", engine_version)[0]` |

### Resources in typing order

| # | Resource | Key arguments |
|---|---|---|
| 1 | `aws_db_subnet_group.this` | name `<identifier>-subnet-group` |
| 2 | `aws_db_parameter_group.this` | name `<prefix>-<service>-pg`; parameter `rds.force_ssl = 1`, apply method `pending-reboot` |
| 3 | `aws_cloudwatch_log_group.postgresql` | name `/aws/rds/instance/<identifier>/postgresql`; retention from the variable |
| 4 | `aws_db_instance.this` | see below |
| 5 | `aws_secretsmanager_secret_rotation.admin` | on the RDS-managed secret; `rotate_immediately = false`; schedule `rate(<n> days)` |
| 6 | `random_password.app` | length 20, special on, `override_special = "!#$%&*()-_=+[]{}<>:?"` |
| 7 | `aws_secretsmanager_secret.app` | name `<prefix>/<service>-db/app-credentials`, KMS key ARN, recovery window |
| 8 | `aws_secretsmanager_secret_version.app` | JSON with `engine`, `username`, `password`, `host`, `port`, `dbname` |
| 9 | `aws_ssm_parameter.db_url` | `/<prefix>/<service>-db-url`; value `jdbc:postgresql://<address>:<port>/<db_name>?sslmode=require` |

### `aws_db_instance.this` arguments

- Identity: `identifier`, engine `postgres`, version, class, storage.
- Credentials: `db_name`, `username`, `manage_master_user_password = true`, `master_user_secret_kms_key_id`.
- Network: subnet group, security group, `publicly_accessible = false`.
- Durability: `multi_az`, `backup_retention_period`, `skip_final_snapshot`, `final_snapshot_identifier = <identifier>-final-snapshot`, `deletion_protection`, `storage_encrypted = true`.
- Parameter group.
- `enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]`.
- `apply_immediately = true` in the reference. See Appendix F.
- `depends_on` the log group, so RDS writes into a group you own instead of creating one with no retention.

### Outputs

| Name | Value |
|---|---|
| `identifier`, `address`, `port`, `endpoint`, `db_name` | from the instance |
| `admin_username`, `app_username` | pass through the variables |
| `admin_secret_arn` | `master_user_secret[0].secret_arn` |
| `app_secret_arn` | ARN of the secret **version** |
| `db_url_parameter_name` | the SSM parameter name |

### Tests

Setup: mock both providers; service `order`, db `orders`, users `order_admin` and `order_user`.

| Run | Asserts |
|---|---|
| `private_encrypted_ssl_only` | not public; encrypted; the `rds.force_ssl` parameter is `1`; family is `postgres16` |
| `names_follow_the_service` | identifier, JDBC parameter name and secret name follow the conventions |
| `prod_safe_defaults` | multi-AZ on, deletion protection on, final snapshot taken |
| `dev_overrides` | with overrides: multi-AZ and deletion protection off |
| `sql_unsafe_app_username_is_rejected` | an injection-style username fails on `var.app_username` |

### Concepts

- The app role (`order_user`) is **not** created by Terraform. The instance bootstrap script creates it, reading this module's secrets.
- `manage_master_user_password` makes RDS own and rotate the admin secret.

---

## Stage 7 — `modules/service`

**Goal:** everything one microservice owns. This is the largest module.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `iam.tf`, `compute.tf`, `outputs.tf`, `templates/` (two files), `tests/service.tftest.hcl`.

### Templates

These two files are shell and JSON, not Terraform. Copy them rather than retyping, then read them so you know which variables they expect. From your build folder at the repo root:

```bash
cp ../jaka_infrastructure/modules/service/templates/service-bootstrap.sh.tpl modules/service/templates/
cp ../jaka_infrastructure/modules/service/templates/cloudwatch-agent-config.json.tpl modules/service/templates/
```

THANG-3 (stage 10) later moves the two ECR resources below into their own `modules/ecr`, so the EKS root can use them without the Auto Scaling group. Build them here as described; THANG-3 shows the move, including the `moved` blocks that keep existing repositories.

What `service-bootstrap.sh.tpl` does at instance boot:

1. Installs Docker, AWS CLI, `psql`, `redis-tools`, `jq` and the CloudWatch agent.
2. Loads the agent config from the SSM parameter.
3. Reads the image tag from SSM and pulls the image from ECR.
4. Reads the JDBC URL, Redis endpoint and port from SSM, and the three secrets from Secrets Manager.
5. Connects as the DB admin and creates or updates the app role with its grants.
6. Runs the container with the Spring environment variables.

### Variables

| Group | Name | Type | Default | Validation |
|---|---|---|---|---|
| Identity | `name_prefix` | string | — | — |
| | `environment` | string | — | — |
| | `service` | string | — | regex `^[a-z][a-z0-9]*$` |
| Routing | `container_port` | number | — | — |
| | `health_check_path` | string | — | — |
| | `path_patterns` | list(string) | — | — |
| | `listener_arn` | string | — | — |
| | `listener_rule_priority` | number | — | — |
| Compute | `vpc_id` | string | — | — |
| | `private_subnet_ids` | list(string) | — | — |
| | `security_group_ids` | list(string) | — | — |
| | `instance_type` | string | `t3.micro` | — |
| | `ami_id` | string | `null` | — |
| | `asg_min_size` | number | `1` | — |
| | `asg_max_size` | number | `4` | — |
| | `asg_desired_capacity` | number | `2` | — |
| | `asg_target_cpu` | number | `60` | — |
| Image | `image_tag` | string | `latest` | — |
| | `ecr_keep_tagged_images` | number | `10` | — |
| Dependencies | `database` | object | — | — |
| | `redis` | object | — | — |
| | `kms_key_arn` | string | — | — |
| | `s3_bucket_arn` | string | — | — |
| Observability | `metrics_namespace` | string | `Microservices/App` | — |
| | `log_retention_days` | number | `7` | — |

`database` object fields: `host`, `port` (number), `name`, `admin_username`, `admin_secret_arn`, `app_username`, `app_secret_arn`, `url_parameter_name`.

`redis` object fields: `primary_endpoint_parameter_name`, `port_parameter_name`, `auth_secret_arn`, `database_index` (number).

### `main.tf`

Data sources: `aws_caller_identity.current`, `aws_region.current`.

| Local | Meaning |
|---|---|
| `full_name` | `<prefix>-<service>` |
| `container_name` | `<service>-service` |
| `region`, `account_id` | from the data sources |
| `ecr_registry` | `<account_id>.dkr.ecr.<region>.amazonaws.com` |

| # | Resource | Key arguments |
|---|---|---|
| 1 | `aws_ecr_repository.this` | name `<full_name>-service`, `MUTABLE`, scan on push, `AES256` |
| 2 | `aws_ecr_lifecycle_policy.this` | rule 1: expire untagged after 1 day; rule 2: keep the newest N images tagged with prefix `v` |
| 3 | `aws_ssm_parameter.image_tag` | `/<prefix>/<service>-image-tag`; `ignore_changes = [value]` |
| 4 | `aws_ssm_parameter.image_tag_previous` | same name plus `-previous`; `ignore_changes = [value]` |
| 5 | `aws_cloudwatch_log_group.app` | `/microservice/<prefix>/<container_name>` |
| 6 | `aws_ssm_parameter.cloudwatch_agent_config` | name `AmazonCloudWatch-<full_name>-service-agent-config`; value from `templatefile` with `log_group_name` and `log_stream_name = <container_name>/{instance_id}` |

The agent config name must start with `AmazonCloudWatch-`, because that is what the managed `CloudWatchAgentServerPolicy` allows reading.

### `iam.tf`

| Local | Meaning |
|---|---|
| `readable_parameter_arns` | ARNs of exactly four parameters: image tag, DB URL, Redis primary endpoint, Redis port |
| `readable_secret_arns` | app secret, admin secret, Redis auth secret |
| `managed_policy_arns` | map: `AmazonSSMManagedInstanceCore`, `AmazonEC2ContainerRegistryReadOnly`, `CloudWatchAgentServerPolicy` |
| `s3_prefix` | `<service>s` |

| # | Resource | Key arguments |
|---|---|---|
| 1 | `aws_iam_role.app` | name `<full_name>-app-role`; trust policy for `ec2.amazonaws.com` |
| 2 | `aws_iam_policy.app_access` | name `<full_name>-app-access`; five statements below |
| 3 | `aws_iam_role_policy_attachment.app_access` | attaches 2 to 1 |
| 4 | `aws_iam_role_policy_attachment.managed` | `for_each` over the managed policy map |
| 5 | `aws_iam_instance_profile.app` | name `<full_name>-ec2-profile` |

| Sid | Action | Resource |
|---|---|---|
| `ReadOwnParameters` | `ssm:GetParameter` | the four parameter ARNs |
| `ReadOwnSecrets` | `secretsmanager:GetSecretValue` | the three secret ARNs |
| `DecryptSecrets` | `kms:Decrypt` | the KMS key |
| `ReadWriteOwnObjects` | `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` | `<bucket>/<s3_prefix>/*` |
| `ListOwnPrefix` | `s3:ListBucket` | the bucket, with `StringLike` on `s3:prefix` = `<s3_prefix>/*` |

### `compute.tf`

| # | Resource | Key arguments |
|---|---|---|
| — | `data "aws_ami" "ubuntu"` | `count` 0 when `ami_id` is set; most recent, owner `099720109477`; filters: name `ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*`, virtualization `hvm`, architecture `x86_64` |
| 1 | `aws_lb_target_group.this` | name `<full_name>-tg`, port = container port, `HTTP`; health check: path from variable, port `traffic-port`, interval 10, healthy threshold 2 |
| 2 | `aws_lb_listener_rule.this` | priority and path patterns from variables; forward to the target group |
| 3 | `aws_launch_template.this` | see below |
| 4 | `aws_autoscaling_group.this` | see below |
| 5 | `aws_autoscaling_policy.cpu_target` | `TargetTrackingScaling`, `ASGAverageCPUUtilization`, target from variable, warmup 180 |

Launch template:

- `name_prefix = <full_name>-lt-`.
- `image_id` is the variable when set, otherwise the data source at index 0.
- Instance profile by name; the security groups.
- `user_data` is `base64encode(templatefile(...))` with 21 values: `region`, `ecr_registry`, `image_repo`, `image_tag_param`, `container_name`, `container_port`, `db_host`, `db_port`, `db_name`, `db_admin_username`, `db_admin_secret_arn`, `db_app_username`, `db_url_param`, `db_app_secret_arn`, `cw_ssm_parameter_name`, `redis_endpoint_param`, `redis_port_param`, `redis_auth_secret_arn`, `redis_database_index`, `environment`, `metrics_namespace`.
- Instance tags: `Name = <full_name>`, `SSMAccess = true`.
- `metadata_options`: endpoint enabled, tokens `required`, hop limit 2 (so the Docker container can reach the metadata service).

Auto Scaling group:

- Name `<full_name>-asg`; the private subnets; the target group.
- `health_check_type = "ELB"`, grace period 300.
- Min, max, desired from variables.
- Launch template version `$Latest`.
- `instance_refresh`: strategy `Rolling`, min healthy 100, max healthy 200, warmup 300.
- `lifecycle { ignore_changes = [desired_capacity] }`.
- Two propagated tags: `Name = <full_name>-asg`, `SSMAccess = true`.

### Outputs

`container_name`, `asg_name`, `target_group_arn`, `target_group_arn_suffix`, `log_group_name`, `ecr_repository_url`, `image_tag_parameter_name`, `role_name`.

### Tests

Setup: mock `aws_region` (`ap-southeast-1`) and `aws_caller_identity` (`123456789012`); realistic ARNs for the listener, KMS key, bucket and secrets.

| Run | Asserts |
|---|---|
| `instances_are_private_and_imdsv2_only` | ASG subnets equal the inputs; IMDSv2 required |
| `routing_matches_the_service` | target group port and health path from inputs; rule priority from input |
| `iam_is_scoped_to_this_service` | every readable parameter ARN starts with the `/jaka-test/` path; secrets list has no `*`; 3 managed attachments |
| `explicit_ami_skips_the_lookup` | with `ami_id` set: 0 data sources and the image ID is used |
| `uppercase_service_is_rejected` | `Order` fails on `var.service` |

### Concepts

- Object-typed variables to group related inputs.
- `templatefile` plus `base64encode` for user data.
- `ignore_changes` for values owned by something other than Terraform.
- Tests can assert on `local.*` values.

---

## Stage 8 — `modules/observability`

**Goal:** the SNS topic, every alarm, the RDS event subscription and the dashboard.

**Files:** `versions.tf`, `variables.tf`, `sns.tf`, `alarms.tf`, `dashboard.tf`, `outputs.tf`, `tests/observability.tftest.hcl`.

**Suggested pace:** write one alarm and one widget, run the check loop, then add the rest.

### Variables

| Name | Type | Default |
|---|---|---|
| `name_prefix` | string | — |
| `environment` | string | — |
| `alarm_email` | string | `null` |
| `metrics_namespace` | string | `Microservices/App` |
| `services` | map(object) with `container_name`, `asg_name`, `target_group_arn_suffix`, `log_group_name` | — |
| `databases` | map(string): service key → RDS identifier | — |
| `redis_cluster_ids` | list(string) | — |
| `alb_arn_suffix` | string | — |
| `thresholds` | object of optional numbers | `{}` |

`thresholds` fields and defaults:

| Field | Default |
|---|---|
| `asg_cpu_percent` | 70 |
| `asg_memory_percent` | 80 |
| `asg_disk_percent` | 80 (validate between 50 and 90) |
| `rds_cpu_percent` | 75 |
| `rds_connections` | 80 |
| `rds_free_storage_bytes` | 2147483648 |
| `alb_5xx_count` | 5 |
| `alb_p95_latency_sec` | 2 |
| `redis_cpu_percent` | 75 |
| `redis_memory_percent` | 75 |
| `log_errors_count` | 10 |
| `hikari_pool_percent` | 90 |

### `sns.tf`

Data sources: caller identity, region. Locals: `account_id`, `region`, `alarm_actions` (a list holding the topic ARN).

| # | Resource | Key arguments |
|---|---|---|
| 1 | `aws_sns_topic.alarms` | name `<prefix>-alarms` |
| 2 | `aws_sns_topic_subscription.alarms_email` | `count` 0 when the email is null; protocol `email` |
| — | `data "aws_iam_policy_document" "alarms_topic"` | three statements below |
| 3 | `aws_sns_topic_policy.alarms` | the document's JSON |
| 4 | `aws_db_event_subscription.rds_critical` | name `<prefix>-rds-critical`; source type `db-instance`; source IDs = values of `databases`; categories `failover`, `failure`, `availability`, `low storage`; `depends_on` the topic policy |

| Sid | Principal | Actions | Condition |
|---|---|---|---|
| `AccountOwner` | AWS `*` | the nine SNS management actions | `StringEquals` `AWS:SourceOwner` = account ID |
| `AllowCloudWatchAlarms` | service `cloudwatch.amazonaws.com` | `SNS:Publish` | `StringEquals` `aws:SourceAccount` = account ID |
| `AllowRdsEvents` | service `events.rds.amazonaws.com` | `SNS:Publish` | `ArnLike` `aws:SourceArn` = `arn:aws:rds:<region>:<account>:*` |

### `alarms.tf`

Locals: `t` (shorthand for `var.thresholds`), `log_metrics_namespace` (`Microservices/Logs/<prefix>`), `redis_clusters` (map of index string → cluster ID).

Every alarm sets `alarm_actions` and `ok_actions` to `local.alarm_actions`. Every alarm except the two Redis ones sets `treat_missing_data = "notBreaching"`. All use `GreaterThanThreshold` unless noted.

| Resource | Iteration | Namespace / metric | Statistic | Period | Eval periods | Threshold | Dimension |
|---|---|---|---|---|---|---|---|
| `asg_cpu_high` | services | `AWS/EC2` `CPUUtilization` | Average | 300 | 1 | `asg_cpu_percent` | `AutoScalingGroupName` |
| `asg_memory_high` | services | `CWAgent` `mem_used_percent` | Average | 300 | 2 | `asg_memory_percent` | `AutoScalingGroupName` |
| `asg_disk_high` | services | `CWAgent` `disk_used_percent` | Average | 300 | 2 | `asg_disk_percent` | `AutoScalingGroupName` |
| `tg_unhealthy_hosts` | services | `AWS/ApplicationELB` `UnHealthyHostCount` | Maximum | 60 | 2 | 0 | `TargetGroup`, `LoadBalancer` |
| `hikari_pool_high` | services | metric math, see below | — | 60 | 3 | `hikari_pool_percent` | — |
| `app_log_errors_high` | services | log metrics namespace, the filter's metric | Sum | 300 | 1 | `log_errors_count` | — |
| `db_cpu_high` | databases | `AWS/RDS` `CPUUtilization` | Average | 300 | 2 | `rds_cpu_percent` | `DBInstanceIdentifier` |
| `db_connections_high` | databases | `AWS/RDS` `DatabaseConnections` | Average | 300 | 2 | `rds_connections` | `DBInstanceIdentifier` |
| `db_free_storage_low` | databases | `AWS/RDS` `FreeStorageSpace` | Average | 300 | 2 | `rds_free_storage_bytes`, `LessThanThreshold` | `DBInstanceIdentifier` |
| `alb_5xx_high` | single | `AWS/ApplicationELB` `HTTPCode_Target_5XX_Count` | Sum | 300 | 1 | `alb_5xx_count` | `LoadBalancer` |
| `alb_latency_high` | single | `AWS/ApplicationELB` `TargetResponseTime` | `extended_statistic = "p95"` | 300 | 2 | `alb_p95_latency_sec` | `LoadBalancer` |
| `redis_cpu_high` | redis nodes | `AWS/ElastiCache` `CPUUtilization` | Average | 300 | 2 | `redis_cpu_percent` | `CacheClusterId` |
| `redis_memory_high` | redis nodes | `AWS/ElastiCache` `DatabaseMemoryUsagePercentage` | Average | 300 | 2 | `redis_memory_percent` | `CacheClusterId` |

Alarm names follow `<prefix>-<key>-<suffix>`, for example `<prefix>-order-asg-cpu-high`. Log-error names use the container name: `<prefix>-order-service-log-errors-high`. Redis names are `<prefix>-redis-<index>-cpu-high`.

Hikari alarm:

- One `metric_query` with `id = "usage"`, expression `100 * active / max`, `return_data = true`.
- A `dynamic "metric_query"` over two entries (`active` → `hikaricp.connections.active.value`, `max` → `hikaricp.connections.max.value`).
- Each metric: the app namespace, period 60, stat `Maximum`, dimensions `service` (container name), `environment`, `pool = HikariPool-1`.

Log metric filter (`aws_cloudwatch_log_metric_filter.app_errors`, per service):

- Name `<prefix>-<container_name>-errors`, on the service's log group.
- Pattern is the quoted word `ERROR`.
- Transformation: name `<container_name>-errors-count`, the log metrics namespace, value `1`, default `0`.

### `dashboard.tf`

A local `dashboard_widgets` list, and one `aws_cloudwatch_dashboard.main` named `<prefix>-overview` whose body is `jsonencode({ widgets = local.dashboard_widgets })`.

Widgets are 12 wide and 6 high on a two-column grid, except the last two which are 24 wide.

| # | Position (x, y) | Title | Lines built from |
|---|---|---|---|
| 1 | 0, 0 | ASG CPU Utilization | services; annotation at the CPU threshold |
| 2 | 12, 0 | CWAgent Memory Utilization | services; annotation at the memory threshold |
| 3 | 0, 6 | CWAgent Disk Utilization | services; annotation at the disk threshold |
| 4 | 12, 6 | ALB Errors and Latency | target 5xx (Sum), response time (p95) |
| 5 | 0, 12 | RDS CPU and Connections | databases, two metrics each |
| 6 | 12, 12 | Redis CPU and Memory | redis nodes, two metrics each |
| 7 | 0, 18 | ALB Traffic | request count, target 4xx, active connections (period 60) |
| 8 | 12, 18 | Target Group Health | services: healthy (Minimum) and unhealthy (Maximum) |
| 9 | 0, 24 | RDS Latency and Free Storage | databases: read/write latency, free storage on the right axis |
| 10 | 12, 24 | Redis Cache Hit Rate | redis nodes |
| 11 | 0, 30 | EC2 Disk I/O (bytes) | services: read and write bytes (Sum) |
| 12 | 12, 30 | Application ERROR log lines | services, from the log metrics namespace |
| 13 | 0, 36 (24 wide) | Latest ERROR log lines | a `log` widget; Logs Insights query over every service log group, filtered on ERROR, newest 50 |
| 14 | 0, 42 (24 wide) | App HTTP latency (avg ms) per service/uri | a `SEARCH` expression on the app namespace, metric `http.server.requests.avg`, filtered by environment |

### Outputs

`alarms_topic_arn`, `dashboard_name`, `dashboard_url` (the console URL built from region and dashboard name).

### Tests

Setup: mock region, caller identity, and `aws_iam_policy_document` with `json = "{}"`; two services, two databases, two Redis nodes.

| Run | Asserts |
|---|---|
| `one_alarm_set_per_service_db_and_node` | 2 of each looped alarm; the order CPU alarm name matches the convention |
| `every_alarm_notifies_the_topic` | a sample of alarms each has exactly 1 alarm action |
| `dashboard_scales_with_services` | with one service, the first widget has exactly 1 metric line |
| `no_email_means_no_subscription` | null email gives 0 subscriptions |
| `disk_threshold_out_of_range_is_rejected` | disk 99 fails on `var.thresholds` |

### Concepts

- `optional(type, default)` inside an object type.
- `dynamic` blocks inside a looped resource.
- `for` expressions with `concat` to build nested lists. To join the per-service row groups, use `concat([for ... : [row, row]]...)`, not `flatten(...)`: `flatten` removes **every** level, so each metric row would collapse into loose strings.
- Keeping large structures in a local so tests can inspect them.

---

## Stage 9 — `stack`

**Goal:** wire the eight modules together. This is where the design pays off.

**Files:** `versions.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `tests/stack.tftest.hcl`.

### Variables

| Name | Type | Default | Validation |
|---|---|---|---|
| `project` | string | your prefix | — |
| `environment` | string | — | must be `dev` or `prod` |
| `services` | map(object) | order and payment, see below | unique listener priorities; unique Redis indexes |
| `vpc_cidr` | string | `10.0.0.0/16` | — |
| `azs` | list(string) | — | — |
| `public_subnet_cidrs` | list(string) | `10.0.1.0/24`, `10.0.2.0/24` | — |
| `private_subnet_cidrs` | list(string) | `10.0.11.0/24`, `10.0.12.0/24` | — |
| `single_nat_gateway` | bool | `false` | — |
| `instance_type` | string | `t3.micro` | — |
| `ami_id` | string | `null` | — |
| `asg` | object | — | min ≤ desired ≤ max |
| `image_tag` | string | `latest` | — |
| `postgres` | object | — | — |
| `redis` | object | — | — |
| `s3_force_destroy` | bool | `false` | — |
| `secret_recovery_window_in_days` | number | `7` | — |
| `log_retention_days` | number | `7` | — |
| `alarm_email` | string | `null` | — |
| `alarm_thresholds` | any | `{}` | — |

Default `services`:

| Key | `container_port` | `listener_rule_priority` | `db_name` | `redis_database_index` |
|---|---|---|---|---|
| `order` | 8080 | 100 | `orders` | 0 |
| `payment` | 8081 | 200 | `payments` | 1 |

Object shapes:

- `asg`: `min_size`, `max_size`, `desired_capacity`, `target_cpu` optional (60).
- `postgres`: `engine_version` optional (`16`), `instance_class` optional (`db.t3.micro`), `allocated_storage` optional (20), and required `multi_az`, `backup_retention_period`, `skip_final_snapshot`, `deletion_protection`.
- `redis`: `engine_version` optional (`7.1`), `node_type` optional (`cache.t3.micro`), required `num_cache_clusters`.

The uniqueness validations compare `length(distinct([...]))` with `length(var.services)`.

### `main.tf`

Data source: `aws_region.current`. Locals: `name_prefix` (`<project>-<environment>`), `metrics_namespace` (`Microservices/App`), `container_ports` (list of every service's port).

| # | Module | Iteration | Inputs worth noting |
|---|---|---|---|
| 1 | `network` | — | `app_port_range` from the min and max of `container_ports`; `redis_port = 6379` |
| 2 | `security` | — | only `name_prefix` |
| 3 | `storage` | — | `tiered_prefixes` = each service key plus `s`; `force_destroy` |
| 4 | `alb` | — | public subnets and ALB security group from `network` |
| 5 | `redis` | — | private subnets, Redis security group, KMS ARN, the `redis` object fields, recovery window |
| 6 | `postgres` | `for_each = var.services` | `service = each.key`, `admin_username = <key>_admin`, `app_username = <key>_user`, subnets, RDS security group, both KMS values, the `postgres` object fields, log retention, recovery window |
| 7 | `service` | `for_each = var.services` | see below |
| 8 | `observability` | — | see below |

`service` inputs:

- Routing: port and priority from the map; `health_check_path = /api/<key>s/actuator/health`; `path_patterns = ["/api/<key>s", "/api/<key>s/*"]`; listener ARN from `alb`.
- Placement: VPC, private subnets, a list holding the EC2 security group, instance type, AMI.
- The four `asg` numbers, and the image tag.
- `database` object built from `module.postgres[each.key]` outputs.
- `redis` object built from `module.redis` outputs plus the map's `redis_database_index`.
- KMS ARN, bucket ARN, metrics namespace, log retention.

`observability` inputs:

- `services`: a `for` expression over `module.service` picking the four fields.
- `databases`: a `for` expression over `module.postgres` picking `identifier`.
- `redis_cluster_ids`, `alb_arn_suffix`, `alarm_email`, `thresholds`, `metrics_namespace`, `environment`.

Module sources are relative paths: `../modules/<name>`.

### Outputs

| Name | Value |
|---|---|
| `name_prefix`, `region`, `vpc_id`, `alb_dns_name` | single values |
| `asg_names`, `ecr_repository_urls`, `image_tag_parameters`, `app_log_group_names` | maps keyed by service |
| `db_endpoints` | map keyed by service |
| `redis_primary_endpoint`, `redis_reader_endpoint` | single values |
| `s3_bucket_name`, `alarms_sns_topic_arn`, `cloudwatch_dashboard_url`, `ssm_caller_username` | single values |

### Tests

Setup: mock `aws` with data for region, caller identity and policy document; mock `random`; variables for environment `dev`, two AZs, and the dev-sized `asg`, `postgres` and `redis` objects.

| Run | Asserts |
|---|---|
| `one_db_and_one_asg_per_service` | 2 postgres and 2 service module instances; `output.asg_names` equals the expected map |
| `third_service_is_one_map_entry` | with a third `inventory` entry: 3 of each |
| `staging_is_rejected` | fails on `var.environment` |
| `duplicate_listener_priority_is_rejected` | fails on `var.services` |

### Checkpoint for stages 1–9

Run every suite from the repo root (Git Bash).

```bash
for d in modules/* stack; do (cd $d && terraform init -input=false >/dev/null && terraform test); done
```

### Concepts

- `for_each` on a module, and `module.name[key].output`.
- A `for` expression over a looped module to build another module's input.
- Ordering comes from references; there is no cross-module `depends_on`.

---

## Stage 10 — `bootstrap`

**Goal:** the S3 bucket that holds remote state. It runs once and keeps local state.

**Files:** `main.tf`, `variables.tf`, `outputs.tf`.

### Variables

| Name | Type | Default |
|---|---|---|
| `project` | string | your prefix |
| `region` | string | `ap-southeast-1` |
| `use_localstack` | bool | `false` |

### `main.tf`

- A `terraform` block (version and the aws provider) and a `provider "aws"` block in the same file.
- Provider: region; `s3_use_path_style`, `skip_credentials_validation`, `skip_requesting_account_id` all set from `use_localstack`; `default_tags` with `Project`, `ManagedBy = Terraform`, `Purpose = terraform-state`.
- `data "aws_caller_identity" "current"`.

| # | Resource | Key arguments |
|---|---|---|
| 1 | `aws_s3_bucket.tfstate` | name `<project>-tfstate-<account_id>`; `lifecycle { prevent_destroy = true }` |
| 2 | `aws_s3_bucket_versioning.tfstate` | `Enabled` |
| 3 | `aws_s3_bucket_server_side_encryption_configuration.tfstate` | `AES256` |
| 4 | `aws_s3_bucket_public_access_block.tfstate` | all four flags true |
| 5 | `aws_s3_bucket_policy.tfstate` | the same `DenyInsecureTransport` statement as the storage module |
| 6 | `aws_s3_bucket_lifecycle_configuration.tfstate` | one rule, empty filter, noncurrent versions expire after 90 days |

### Outputs

`state_bucket`, `account_id`.

### Commands

```bash
cd bootstrap
terraform init
terraform apply
terraform output account_id
```

### Concepts

- A bucket cannot store the state of its own creation, so this project keeps local state.
- Versioning lets you roll back a broken state; `prevent_destroy` guards against an accidental destroy.

---

## Stage 11 — `envs/dev`

**Goal:** a thin root that calls `stack` with cheap settings.

**Files:** `versions.tf`, `providers.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `terraform.tfvars`, `backend.hcl`.

### `versions.tf`

- `required_version >= 1.10.0` (S3-native locking needs it).
- Providers aws and random.
- An empty `backend "s3" {}` block.

### `providers.tf`

- Region from the variable.
- The three LocalStack flags from `use_localstack`.
- `default_tags`: `Project`, `Environment`, `ManagedBy = Terraform`.

### `variables.tf`

| Name | Type | Default |
|---|---|---|
| `region` | string | `ap-southeast-1` |
| `alarm_email` | string | `null` |
| `image_tag` | string | `latest` |
| `use_localstack` | bool | `false` |
| `ami_id` | string | `null` |

### `main.tf`

Locals `project` and `environment`, then one `module "stack"` with source `../../stack`.

| Setting | dev value |
|---|---|
| `azs` | `<region>a`, `<region>b` |
| `single_nat_gateway` | `true` |
| `instance_type` | `t3.micro` |
| `asg` | min 1, max 2, desired 1 |
| `postgres` | `multi_az` false, backup 1 day, skip final snapshot, no deletion protection |
| `redis` | 1 node |
| `s3_force_destroy` | `true` |
| `secret_recovery_window_in_days` | `0` |
| `log_retention_days` | `7` |
| `ami_id`, `image_tag`, `alarm_email` | from the variables |

### `outputs.tf`

Pass through from `module.stack`: `region`, `alb_dns_name`, `asg_names`, `ecr_repository_urls`, `image_tag_parameters`, `db_endpoints`, `redis_primary_endpoint`, `s3_bucket_name`, `app_log_group_names`, `cloudwatch_dashboard_url`, `ssm_caller_username`.

### `terraform.tfvars`

Three values: `region`, `image_tag`, `alarm_email`.

### `backend.hcl`

| Key | Value |
|---|---|
| `bucket` | `<project>-tfstate-<account_id>` (from stage 10) |
| `key` | `dev/terraform.tfstate` |
| `region` | `ap-southeast-1` |
| `encrypt` | `true` |
| `use_lockfile` | `true` |

### Commands

Use the space form of the flags; the installed `terraform.exe` misparses the `=` form.

```bash
cd envs/dev
terraform init -backend-config backend.hcl
terraform plan -var-file terraform.tfvars
```

Review the plan first. Applying creates billable resources.

```bash
terraform apply -var-file terraform.tfvars
```

Confirm the state is in S3 (replace the account ID):

```bash
aws s3 ls s3://<project>-tfstate-<account_id> --recursive
```

Tear dev down when finished:

```bash
terraform destroy -var-file terraform.tfvars
```

### Concepts

- Partial backend configuration: the block is empty in code and filled at `init`.
- `use_lockfile` writes a `.tflock` object next to the state; no DynamoDB table is needed.
- To see the lock, run the same `plan` in two terminals; the second fails with `Error acquiring the state lock`.

---

## Stage 12 — `envs/prod`

**Goal:** the same root with highly available, protected settings.

Copy every file from `envs/dev`, then change only three things: the `environment` local, the `module "stack"` settings, and the `key` in `backend.hcl` (`prod/terraform.tfstate`).

| Setting | prod value |
|---|---|
| `single_nat_gateway` | `false` |
| `instance_type` | `t3.small` |
| `asg` | min 2, max 6, desired 2 |
| `postgres` | `multi_az` true, backup 7 days, take final snapshot, deletion protection on |
| `redis` | 2 nodes |
| `s3_force_destroy` | `false` |
| `secret_recovery_window_in_days` | `7` |
| `log_retention_days` | `30` |

### Commands

Planning is enough to finish this stage. Applying prod is optional and the most expensive step.

```bash
cd envs/prod
terraform init -backend-config backend.hcl
terraform plan -var-file terraform.tfvars
```

A prod `destroy` stops on purpose: RDS has deletion protection and the bucket has `force_destroy = false`.

---

## Stage 13 — Tooling

### `.tflint.hcl`

- `config { call_module_type = "local" }`.
- Plugin `terraform`, enabled, preset `recommended`.
- Plugin `aws`, enabled, pinned version, source `github.com/terraform-linters/tflint-ruleset-aws`.

### `.github/workflows/terraform.yml`

Triggers on `push` and `pull_request`. Five jobs:

| Job | Does |
|---|---|
| `fmt` | `terraform fmt -check -recursive` |
| `test` | matrix over the eight modules and `stack`: init, validate, test |
| `validate-envs` | matrix over dev and prod: init with `-backend=false`, validate |
| `tflint` | `tflint --init`, then recursive lint with the root config |
| `checkov` | the checkov action on the whole repo, framework `terraform` |

No AWS credentials are needed for any job.

### LocalStack (optional)

- `localstack/docker-compose.yml`: one `localstack` service, image `localstack/localstack-pro`, ports `4566` and `4510-4559` bound to localhost, environment `LOCALSTACK_AUTH_TOKEN`, `PERSISTENCE=1`, `EC2_VM_MANAGER=docker`, volumes for `./volume` and the Docker socket.
- In each env, `localstack.tfvars`: `use_localstack = true` and an `ami_id`.
- In each env, `backend.localstack.hcl`: bucket with account `000000000000`, the env key, plus `endpoints = { s3 = "http://localhost:4566" }`, `use_path_style`, test access keys, and the four `skip_*` flags.
- ALB, ASG, RDS, ElastiCache and ECR need LocalStack Pro.

### Commands

```bash
terraform fmt -check -recursive
tflint --init
tflint --recursive
```

---

## Appendix A — Definition of done

| Check | Passes when |
|---|---|
| Formatting | `terraform fmt -check -recursive` prints nothing |
| Tests | all nine suites pass with no credentials |
| Dev plan | plans cleanly against the remote backend |
| Prod plan | differs from dev only in the settings listed in stage 12 |
| Parity | your block counts match Appendix B |
| Lint | `tflint --recursive` reports nothing |

## Appendix B — Resource-block count per folder

Counts are `resource` blocks as written, not instances created.

| Folder | Resource blocks | Data sources |
|---|---|---|
| `modules/network` | 24 (12 in `main.tf`, 12 in `security_groups.tf`) | 0 |
| `modules/security` | 5 | 0 |
| `modules/storage` | 6 | 1 |
| `modules/alb` | 2 | 0 |
| `modules/redis` | 8 | 0 |
| `modules/postgres` | 9 | 0 |
| `modules/service` | 16 (6 main, 5 iam, 5 compute) | 3 |
| `modules/observability` | 19 (4 sns, 14 alarms file, 1 dashboard) | 3 |
| `stack` | 0 (8 module calls) | 1 |
| `bootstrap` | 6 | 1 |
| `envs/dev`, `envs/prod` | 0 (1 module call each) | 0 |

Count yours with:

```bash
grep -rc '^resource "' --include=*.tf modules stack bootstrap envs
```

## Appendix C — Concepts by stage

| Concept | First used in |
|---|---|
| `for_each` with a stable key | 1 |
| Variable `validation`, `expect_failures` | 1 |
| `count` as an on/off switch | 1, 2 |
| `one()` on a splat | 2 |
| `dynamic` blocks | 3 |
| `mock_data` for data sources | 3 |
| Derived settings in locals | 5 |
| Outputs that enforce ordering | 5, 6 |
| Object variables, `templatefile`, `ignore_changes` | 7 |
| `optional()` with defaults, `concat(...)` with the spread operator (and why not `flatten`) | 8 |
| `for_each` on modules | 9 |
| `prevent_destroy`, local vs remote state | 10 |
| Partial backend config, S3 lock file | 11 |

## Appendix D — Naming conventions

`<prefix>` is `<project>-<environment>`; `<svc>` is the service key.

| Item | Pattern |
|---|---|
| Most resources | `<prefix>-<thing>` |
| Per-service resources | `<prefix>-<svc>-<thing>` |
| Container | `<svc>-service` |
| ECR repository | `<prefix>-<svc>-service` |
| SSM parameters | `/<prefix>/<svc>-image-tag`, `/<prefix>/<svc>-db-url`, `/<prefix>/redis-port` |
| CloudWatch agent parameter | `AmazonCloudWatch-<prefix>-<svc>-service-agent-config` |
| Secrets | `<prefix>/<svc>-db/app-credentials`, `<prefix>/redis/auth-token` |
| App log group | `/microservice/<prefix>/<svc>-service` |
| RDS log group | `/aws/rds/instance/<prefix>-<svc>-db/postgresql` |
| Microservices bucket | `<prefix>-microservices-<account_id>` |
| State bucket | `<project>-tfstate-<account_id>` |
| Log metrics namespace | `Microservices/Logs/<prefix>` |
| App metrics namespace | `Microservices/App` |

## Appendix E — Test gotchas

- A mocked provider returns random strings for computed values. Anything AWS validates as an ARN needs a realistic value in the test `variables`.
- A mocked `aws_iam_policy_document` returns random text; give it `json = "{}"` or the SNS topic policy fails.
- A mocked region or account ID is random unless you set `mock_data` defaults; set them whenever you assert on a name or ARN that includes them.
- `at_rest_encryption_enabled` on the replication group is a string in provider v5; wrap it in `tobool`.
- `expect_failures` takes the variable reference, for example `[var.vpc_cidr]`.
- A test can assert on `local.*` and `output.*`, not only on resources.
- Modules that use the random provider need `mock_provider "random" {}` too.

## Appendix F — Improvements to make while rebuilding

These are weaknesses in the reference. Fixing them as you type is cheaper than fixing them later.

| # | Change | Stage |
|---|---|---|
| 1 | Commit `.terraform.lock.hcl` instead of ignoring it | 0 |
| 2 | Make `apply_immediately` a variable in `postgres` and `redis`; false in prod | 5, 6 |
| 3 | One EC2 ingress rule per service port, instead of a min-to-max range | 1, 9 |
| 4 | Put `path_patterns`, `health_check_path` and the S3 prefix in the `services` map, instead of adding an `s` to the key | 9 |
| 5 | Give `alarm_thresholds` the real object type instead of `any` | 9 |
| 6 | Pass the Redis port from one place, not a literal in `stack` plus a default in the module | 9 |
| 7 | Add a unique suffix to the RDS final snapshot name | 6 |

Do these after the base stack works:

| # | Change |
|---|---|
| 8 | HTTPS listener with a certificate, and redirect port 80 to 443 |
| 9 | Create the app DB role once, outside the instances, so app servers never hold the admin secret |
| 10 | One shared ECR repository, so dev and prod run the same image |
| 11 | Split the single `stack` state into layers (network, data, services) |

## Appendix G — Not covered

- **Deploy scripts.** `build-and-push.sh`, `deploy.sh`, `rollback.sh`, `smoke-tests.sh`, `load-test.sh` and `lib.sh` belonged to the month-1 `jaka_aws_networking/` project, which is no longer in this repo. They read that project's outputs and the `/dev/<svc>-image-tag` names, so they would need adapting to the map outputs and `/<prefix>/<svc>-image-tag` names here. The EC2 path has no deploy script in this repo; THANG-2 and THANG-3 deploy with `cluster-up.sh` and `eks-up.sh` instead.
- **Subnet tags for EKS.** THANG-3 (stage 9) adds `public_subnet_tags` and `private_subnet_tags` variables to `modules/network`. Adding them now (`map(string)`, default `{}`, merged into the subnet tags) is harmless for EC2.
- **`README.md`.** Write it last, in your own words; explaining the design from memory is the best test that the rebuild worked.
