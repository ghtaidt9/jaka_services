# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Output format
- Wrap every shell/command in code block ```bash
- Don't write command in the natural passages
- Long plan: break into heading, stage, session,...don't keep evrything one sentense/pararagh

## Repository layout

This repo has three independent projects with no shared build:

- `jaka_aws_networking/` — Terraform (its own git repo, `.git` here). Provisions AWS infra: VPC, ALB, EC2 Auto Scaling Groups, RDS (Postgres), CloudWatch. See `jaka_aws_networking/README.md` for the full architecture writeup and diagrams — read it before making infra changes.
- `order-service/order-service/` — Spring Boot microservice (note the doubled directory name).
- `payment-service/payment-service/` — Spring Boot microservice (same doubled-name pattern).

## Commands

### Spring Boot services (run from inside `order-service/order-service/` or `payment-service/payment-service/`)

```powershell
.\gradlew build
.\gradlew test
.\gradlew test --tests "OrderServiceApplicationTests"   # single test
.\gradlew bootRun
```

Each service needs its own Postgres + Redis before running locally:
```powershell
docker compose up -d   # from within the service's own directory — each has its own docker-compose.yml
```
`order-service` maps Postgres to `5433` / Redis to `6379`; `payment-service` maps Postgres to `5434` / Redis to `6380` — chosen so both stacks can run side by side on one machine.

### Terraform (`jaka_aws_networking/`)

```powershell
terraform init -upgrade
terraform plan -var-file terraform.tfvars
terraform apply -var-file terraform.tfvars
```
Use the **space form** (`-var-file terraform.tfvars`), not `-var-file=terraform.tfvars` — the installed `terraform.exe` (Winget, v1.15.8) misparses the `=` form as a stray positional argument and fails with "Too many command line arguments."

Creating/rotating the per-service DB logins (`order_user`/`payment_user`) requires the multi-stage tunnel workflow described in `jaka_aws_networking/README.md` / the project's Postgres-role plan doc — it can't be done in a single `apply` because the DB security group only accepts connections from the EC2 instances, not from a local machine.

## Architecture

### Microservices — database-per-service, config via env vars only
- `order-service` and `payment-service` are separate Spring Boot apps, each with its own Postgres database and its own Redis cache — no shared database, no shared schema.
- Each service's `src/main/resources/application.yaml` sets `server.servlet.context-path` (`/api/orders`, `/api/payments`) and points at `localhost` DB/Redis ports for local dev. In AWS, the actual DB URL/credentials are injected at container-start time via env vars (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`) sourced from SSM Parameter Store — see `jaka_aws_networking/templates/service-bootstrap.sh.tpl`. There's no `application-prod.yaml`; prod config is entirely env-var overrides.
- JPA uses `ddl-auto: update` — schema is created/evolved by Hibernate on boot, not migration scripts. This is why the app's Postgres role needs `CREATE` on the `public` schema, not just DML privileges (see `postgresql_grant.*_schema_usage` in `jaka_aws_networking/rds.tf`).
- Each service seeds sample data on startup via a `CommandLineRunner` (`OrderDataSeeder`/`PaymentDataSeeder`) that no-ops if the table already has rows.

### AWS infra (`jaka_aws_networking/`)
- Two-AZ VPC; EC2 instances for both services sit in private subnets with **no public IP**, reachable only via SSM Session Manager (no bastion, no SSH path from the internet). The ALB does path-based routing (`/api/orders*` → order target group, `/api/payments*` → payment target group).
- Each service has its own RDS Postgres instance (multi-AZ). The RDS admin account (`order_admin`/`payment_admin`, created by the `aws_db_instance` resource) is distinct from the app's login (`order_user`/`payment_user`), which is provisioned separately via the `cyrilgdn/postgresql` Terraform provider (two aliased `postgresql` provider blocks in `provider.tf`, one per DB) with least-privilege grants in `rds.tf`. The app containers authenticate as the `*_user` account, never the admin account.
- All app-server secrets (DB URLs, DB passwords for both the admin and the app user) are published to SSM Parameter Store by Terraform and read by the EC2 user-data script at boot — see `locals.tf` for the parameter-name conventions and `asg.tf` for how the launch templates wire them into the container's env vars.
- CloudWatch Agent config is templated per-service and pushed through SSM Parameter Store as well (`cloudwatch-agent.tf`, `templates/cloudwatch-agent-config.json.tpl`).
