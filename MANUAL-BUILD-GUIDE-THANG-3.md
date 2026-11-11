# Manual build guide — THANG-3: event-driven services and Saga, then EKS

A step-by-step spec for rebuilding month 3 by hand, in two parts.

- **Part A (stages 0–8), on kind.** The two services that exchanged one fire-and-forget event in THANG-2 become four services (order, inventory, payment, notification) that exchange ten events reliably: transactional outbox, idempotent consumers, retries with dead-letter topics, a correlation ID through every log line, a Saga with compensations, and a CQRS read model.
- **Part B (stages 9–18), on AWS.** The same chart and the same Gateway file run on EKS. RDS and ElastiCache replace the StatefulSets, ECR replaces `kind load`, External Secrets replaces `kubectl create secret`, and an NLB replaces port-forward.

Like the THANG-1 and THANG-2 guides, this describes **what** to write and **how to check it**, not the code.

**Series:** THANG-1 → [MANUAL-BUILD-GUIDE-THANG-1.md](MANUAL-BUILD-GUIDE-THANG-1.md) · THANG-2 → [MANUAL-BUILD-GUIDE-THANG-2.md](MANUAL-BUILD-GUIDE-THANG-2.md) · **THANG-3 (this file)**

**Answer keys** (the current working tree; open only after you have tried a stage):

| Part | Where |
|---|---|
| Service code | `order-service/`, `payment-service/`, `inventory-service/`, `notification-service/` |
| Event contract | `docs/event-catalog.md` |
| Kubernetes, chart, scripts, Terraform | `jaka_infrastructure/` |

---

## Contents

- [TL;DR](#tldr)
- [The big picture (read this first)](#the-big-picture-read-this-first)
- [Why this order](#why-this-order)
- [Know before you start](#know-before-you-start)
- [Stage map](#stage-map)
- [The check loops](#the-check-loops)
- **Part A — event-driven, on kind**
  - [Stage 0 — Starting point and the contract](#stage-0--starting-point-and-the-contract)
  - [Stage 1 — The messaging kit, in order-service](#stage-1--the-messaging-kit-in-order-service)
  - [Stage 2 — Topics](#stage-2--topics)
  - [Stage 3 — inventory-service](#stage-3--inventory-service)
  - [Stage 4 — payment-service charges](#stage-4--payment-service-charges)
  - [Stage 5 — The Saga](#stage-5--the-saga)
  - [Stage 6 — CQRS read model](#stage-6--cqrs-read-model)
  - [Stage 7 — notification-service](#stage-7--notification-service)
  - [Stage 8 — Prove it: `saga-demo.sh` and the failure drills](#stage-8--prove-it-saga-demosh-and-the-failure-drills)
- **Part B — the same system on EKS**
  - [Stage 9 — Shared modules: provider pins and subnet tags](#stage-9--shared-modules-provider-pins-and-subnet-tags)
  - [Stage 10 — `modules/ecr`](#stage-10--modulesecr)
  - [Stage 11 — `modules/eks`](#stage-11--moduleseks)
  - [Stage 12 — `envs/dev-eks`](#stage-12--envsdev-eks)
  - [Stage 13 — IAM for Pods](#stage-13--iam-for-pods)
  - [Stage 14 — `k8s/eks/`](#stage-14--k8seks)
  - [Stage 15 — Values `<svc>-eks.yml`](#stage-15--values-svc-eksyml)
  - [Stage 16 — `scripts/eks-up.sh`](#stage-16--scriptseks-upsh)
  - [Stage 17 — CI](#stage-17--ci)
  - [Stage 18 — Runbook on AWS](#stage-18--runbook-on-aws)
- [Appendix A — Definition of done](#appendix-a--definition-of-done)
- [Appendix B — Event contract](#appendix-b--event-contract)
- [Appendix C — Tables per database](#appendix-c--tables-per-database)
- [Appendix D — Concepts by stage](#appendix-d--concepts-by-stage)
- [Appendix E — Gotchas](#appendix-e--gotchas)
- [Appendix F — Improvements to make while rebuilding](#appendix-f--improvements-to-make-while-rebuilding)
- [Appendix G — Not covered](#appendix-g--not-covered)

---

## TL;DR

- Part A is Java in your service worktree (`../jaka-services` from THANG-2), plus a few K8s files and two scripts in `jaka_manual/`. Part B is Terraform, K8s templates and one script in `jaka_manual/`.
- The heart of Part A is one **kit** of ten small files (outbox, relay, dedupe table, error handler, correlation ID). You type it once in order-service and copy it into inventory and payment; notification needs the consumer half.
- Everything up to stage 17 is checked without AWS: Gradle, kind, `terraform test` with mock providers, `terraform validate`, `helm template`.
- Stage 18 needs an AWS account: about 0.4–0.5 USD per hour while it runs. Apply, prove, destroy in one sitting.

## The big picture (read this first)

### Part A: one order through four services

```
POST /api/orders/ ──► order-service: save order PENDING + OrderCreated (outbox)
                          │
                    order-events
                          ▼
                    inventory-service: reserve
                     ├─ enough stock → InventoryReserved ─► inventory-events ─► payment-service: charge
                     │                                                          ├─ ok   → PaymentCompleted ─► payment-events
                     │                                                          └─ fail → PaymentFailed    ─► payment-events
                     └─ no stock     → InventoryRejected ─► inventory-events

order-service (the Saga) listens to inventory-events + payment-events:
   InventoryReserved → AWAITING_PAYMENT
   InventoryRejected → CANCELLED ─► OrderCancelled
   PaymentFailed     → CANCELLED ─► OrderCancelled            inventory: PaymentFailed / OrderCancelled → release ─► InventoryReleased
   PaymentCompleted  → CONFIRMED ─► OrderConfirmed            inventory: PaymentCompleted → commit
                       or, if already CANCELLED ─► RefundRequested ─► payment: refund ─► PaymentRefunded
   timeout job: AWAITING_PAYMENT for 5 min → CANCELLED (PAYMENT_TIMEOUT) ─► OrderCancelled

notification-service listens to all three topics and emails on OrderCreated, InventoryRejected, PaymentCompleted, PaymentFailed.
order-summary-projector (inside order-service) builds a read model from inventory-events + payment-events.
```

### Every problem of THANG-2's first event, and its fix

| THANG-2 stage 12 problem | Fix | Where |
|---|---|---|
| Kafka down → event lost after the order commits | write the event into an `outbox` table **in the same DB transaction**; a relay sends it later and retries | `OutboxWriter`, `OutboxRelay` |
| DB rolls back after the send → event for a missing order | same: nothing is sent unless the transaction committed | same |
| A message can arrive twice | remember each `eventId` in `processed_events`, in the same transaction as the work | every listener |
| One bad message blocks a partition forever | retry with backoff, then park it in `<topic>-dlt` | `KafkaConfig` |
| One topic now carries several event types | an envelope with `eventType`; payload read as `JsonNode`; `switch` on the type | `EventEnvelope`, listeners |
| Following one order through four services' logs | a correlation ID: HTTP header → log context → outbox column → Kafka header → log context | `Correlation`, filter, interceptor |
| No transaction across three databases | a Saga: local steps plus compensations | stage 5 |
| "What is the state of order 42?" needs three services | a read model built from events | stage 6 |

### The kit in one picture

```
incoming HTTP request or Kafka record
   │  CorrelationIdFilter / CorrelationIdInterceptor: put the correlation ID into MDC (the log context)
   ▼
@Transactional handler ── one database transaction ───────────────────────────────┐
   │  business rows (orders, reservations, payments)                                 │
   │  outbox row  (event JSON, correlation_id, published_at = NULL)                  │
   │  processed_events row (eventId of the incoming event)                            │
   └──────────────────────────────────────────────────────────────── commit ─────────┘
OutboxRelay, every 1 s:  SELECT … WHERE published_at IS NULL … FOR UPDATE SKIP LOCKED
   → ProducerRecord(topic, key = aggregateId, value = JSON, headers correlationId + eventType)
   → send().join()  → published_at = now()        (failure → rollback → tried again next second)
Consumer side: String → EventEnvelope<JsonNode> (message converter) → listener
   → exception → retry after 1 s, 2 s, 4 s, 8 s → <topic>-dlt
```

### Part B: what changes from kind to EKS

| Layer | kind (Part A) | EKS (Part B) | Written in |
|---|---|---|---|
| Cluster | 3 Docker containers | EKS control plane + managed node group (2–4 × `t3.medium`) | stage 11 |
| Pod network | kindnet | VPC CNI: every Pod gets a real VPC IP | stage 11 |
| Images | `kind load` | ECR, `docker push` | stages 10, 16 |
| Databases | 4 Postgres StatefulSets | 4 RDS instances (`modules/postgres` from THANG-1) | stage 12 |
| Redis | 2 Redis StatefulSets | 1 ElastiCache with TLS + AUTH (`modules/redis`) | stage 12 |
| Passwords | `.env` → `kubectl create secret` | Secrets Manager → External Secrets Operator → the same k8s Secrets | stages 12–14 |
| App DB user | Job reading the admin Secret | same Job, as a template with the RDS host | stage 14 |
| Disks for Kafka | local-path `standard` | EBS `gp3` through the EBS CSI driver | stages 11, 14 |
| Entry | port-forward to Envoy | NLB created by the AWS Load Balancer Controller in front of Envoy | stages 13, 14 |
| AWS permissions per app | none needed | one IAM role per ServiceAccount (Pod Identity) | stage 13 |
| App deployment | `values/<svc>-dev.yml` | **same chart**, `values/<svc>-eks.yml` | stage 15 |
| Routing | `k8s/gateway.yml` | **same file** | — |

Why an NLB and not the THANG-1 ALB: Envoy already reads the URL path (HTTPRoute). The AWS side only has to pass TCP through, which is what a cheaper layer-4 NLB does.

### Part B: who calls whom

```
envs/dev-eks/                         ← its own root and state key; does NOT call stack/
├── module "network"     modules/network   VPC, /20 private subnets, subnet tags for the LB Controller
├── module "eks"         modules/eks       → terraform-aws-modules/eks v21: control plane, node group, 5 add-ons, EBS CSI role
├── module "security"    modules/security  KMS key for every secret
├── module "storage"     modules/storage   S3 (kept for parity; nothing uses it on EKS yet)
├── module "ecr"         modules/ecr       4 repositories
├── module "redis"       modules/redis     1 ElastiCache + AUTH token secret
├── module "postgres" ×4 modules/postgres  for_each over 4 apps; admin secret managed by RDS, app password generated
├── data-access.tf       Postgres 5432 and Redis 6379 open to the EKS node security group
├── pod-identity.tf      one IAM role per app ServiceAccount
└── addons-iam.tf        IAM roles for the LB Controller and External Secrets
```

`modules/service`, `modules/alb` and `modules/observability` are **not** called: Deployments replace the ASG, NLB + Envoy replace the ALB, and month 4 replaces CloudWatch with Prometheus.

Values that exist only after `apply` (ECR URLs, RDS hosts, secret ARNs, Redis host) are never typed by hand. They flow: `outputs.tf` → `terraform output -json` → `jq` in `eks-up.sh` → `helm --set` or `envsubst` into a template.

## Why this order

- **The contract before the code (stage 0).** Four services share ten events with no shared library. Writing the catalog first is what keeps their copies in agreement.
- **The kit before any new service.** Every service uses it; getting it right once in order-service saves three debugging sessions.
- **Topics before consumers.** A DLT must exist with the same partition count as its source topic before the first failure.
- **inventory, then payment, then the Saga.** Each step consumes what the previous one produces.
- **CQRS and notification after the Saga.** They only read events; nothing depends on them.
- **Part B after Part A.** EKS changes the platform, not the app. If the Saga does not pass on kind, it will not pass on EKS.
- **Inside Part B, Terraform before Kubernetes.** IAM roles must exist before the controllers that use them; CRDs before the objects that use them; Secrets before the Pods that read them.

## Know before you start

| Tradeoff | Consequence |
|---|---|
| No shared library between services | Each service keeps its own copy of the event records. `docs/event-catalog.md` is the only place that says what they must contain |
| `ddl-auto: update` | New tables appear on boot. New `NOT NULL` columns on a table with rows do **not**: stage 1 drops the `orders` table once |
| At-least-once delivery | Every consumer must tolerate duplicates. That is what `processed_events` and the guarded state changes are for |
| Ten events, five consumer groups | A wrong `groupId` or a missing `case` does not fail at startup; it shows up as an order stuck in one status |
| Kafka's advertised addresses are cluster-internal | Services cannot talk to the in-cluster Kafka from your laptop; test by deploying to kind |
| EKS costs per hour | No free tier for the control plane, NAT, RDS and the NLB. Destroy in the same sitting, in the order stage 18 gives |
| `terraform-aws-modules/eks` v21 needs AWS provider 6 | The THANG-1 modules pin `~> 5.50`; stage 9 relaxes the ones EKS shares |
| Your prefix differs from the reference (`jaka-mod`) | Anything that spells the prefix (secret names in ExternalSecrets) must come from Terraform, not be typed |

## Stage map

| Stage | Where | You write | Depends on |
|---|---|---|---|
| 0 | worktree, `jaka_manual` | branch, package rename, `docs/event-catalog.md`, `.env` | THANG-2 |
| 1 | order-service | the kit, order fields, `OrderCreated` through the outbox | 0 |
| 2 | `jaka_manual` | 5 more `KafkaTopic`s | 1 |
| 3 | inventory-service (new) + K8s files | the service, its DB, Job, values, route | 1, 2 |
| 4 | payment-service | kit, `charge` | 3 |
| 5 | order, inventory, payment | Saga listener, timeout job, release, refund | 4 |
| 6 | order-service | read model and projector | 5 |
| 7 | notification-service (new) + K8s files | the service, Mailpit, DB, Job, values, route | 1, 2 |
| 8 | `jaka_manual/scripts` | `saga-demo.sh`, script updates | 5–7 |
| 9 | `modules/network`, module pins | subnet tag variables, `>= 5.50` | THANG-1 |
| 10 | `modules/ecr`, `modules/service`, `stack` | ECR module, refactor, `moved.tf` | 9 |
| 11 | `modules/eks` | wrapper + test | 9 |
| 12 | `envs/dev-eks` | root, data layer, outputs | 10, 11 |
| 13 | `envs/dev-eks` | Pod roles, add-on roles | 12 |
| 14 | `k8s/eks/` | StorageClass, SecretStore, ExternalSecret and Job templates, EnvoyProxy | 13 |
| 15 | `charts/spring-service/values` | 4 × `<svc>-eks.yml` | 14 |
| 16 | `scripts/eks-up.sh` | the EKS bring-up script | 12–15 |
| 17 | `.github/workflows/terraform.yml` | new matrix entries | 10–12 |
| 18 | AWS | apply, run, prove, destroy | all |

## The check loops

**Java**, from the service folder:

```bash
./gradlew build -x test
```

**Deploy one service to kind** after a code change, from `jaka_manual/` with `.env` loaded (`svc` = `order`, `payment`, `inventory` or `notification`):

```bash
svc=order; tag=dev-$(date +%s)
docker build -t "$svc-service:$tag" "$APP_ROOT/$svc-service/$svc-service"
kind load docker-image "$svc-service:$tag" --name jaka
helm upgrade --install "$svc-service" charts/spring-service -n dev -f "charts/spring-service/values/$svc-dev.yml" --set image.tag="$tag"
kubectl rollout status "deploy/$svc-service" -n dev
kubectl logs -n dev "deploy/$svc-service" --tail=50
```

Or rerun `./scripts/cluster-up.sh`, which rebuilds all of them.

**Look inside** (keep the THANG-2 Gateway port-forward on 8090 running):

```bash
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "select event_type, published_at is not null as sent from outbox order by id desc limit 5;"
kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --include 'order-events|inventory-events|payment-events' --from-beginning --property print.headers=true --timeout-ms 10000
```

**Terraform** (Part B), in each module or root:

```bash
terraform fmt
terraform init -input=false -backend=false
terraform validate
terraform test
```

---

# Part A — event-driven, on kind

## Stage 0 — Starting point and the contract

**Goal:** a branch for the new code, the event contract on paper, and the passwords for two more databases.

### Branch

```bash
cd ../../jaka-services
git switch -c thang3-services
```

### Package name (recommended)

At commit `fbfc964` order-service still uses `com.example.order_service`. Rename the package to `com.jaka.order_service` with your IDE's rename refactoring, and set `group = 'com.jaka'` in its `build.gradle`. All four services then match, and the answer key diffs cleanly.

### Delete the THANG-2 throwaways

| Service | Delete | Replaced by |
|---|---|---|
| order | `publisher/OrderEventPublisher.java`, `dto/OrderCreatedEvent.java` | outbox (stage 1) |
| payment | `consumer/OrderEventConsumer.java`, `dto/OrderCreatedEvent.java` | new consumers (stages 4, 5) |
| payment | in `application.yaml`: `ErrorHandlingDeserializer` and the three `spring.json.*` / `spring.deserializer.*` properties | String deserializer + message converter (stage 4) |

### `docs/event-catalog.md`

Create `docs/` at the worktree root. The file holds four tables, all given in [Appendix B](#appendix-b--event-contract): the envelope fields, the ten events (topic, producer, consumers, meaning), the five consumer groups (listens to, handles, ignores), and the payload fields of each event. Copy them; they are the contract every later stage types against. When a stage adds a field, change this file first.

### `.env` in `jaka_manual/`

Add `INVENTORY_DB_PASS`, `INVENTORY_DB_ADMIN_PASS`, `NOTIFICATION_DB_PASS`, `NOTIFICATION_DB_ADMIN_PASS`.

### Check

`git status` in the worktree shows the deletions and the new doc; `./gradlew build -x test` in order-service fails on references to the deleted publisher (stage 1 removes them).

---

## Stage 1 — The messaging kit, in order-service

**Goal:** order-service writes `OrderCreated` to an outbox table in the same transaction as the order, and a relay publishes it.

### The kit (package root `com.jaka.order_service`)

Type all ten now. Rows 7–9 are the consumer half; order-service starts consuming in stage 5, inventory and payment need them at once.

| # | File | Spec |
|---|---|---|
| 1 | `common/Correlation.java` | `final` class, private constructor, three `public static final String`: `HTTP_HEADER = "X-Correlation-Id"`, `KAFKA_HEADER = "correlationId"`, `MDC_KEY = "correlationId"` |
| 2 | `dto/EventEnvelope.java` | generic record `EventEnvelope<T>(String eventId, String eventType, int version, String aggregateId, Instant occurredAt, T payload)` |
| 3 | `entity/OutboxEvent.java` | see below |
| 4 | `repository/OutboxRepository.java` | `JpaRepository<OutboxEvent, Long>` plus `List<OutboxEvent> lockUnpublishedEvents(@Param("limit") int limit)` with a native `@Query`: `SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED` |
| 5 | `service/OutboxWriter.java` | see below |
| 6 | `relay/OutboxRelay.java` | see below |
| 7 | `entity/ProcessedEvent.java` + `repository/ProcessedEventRepository.java` | table `processed_events`; `@Id String eventId` (`length = 36`); `Instant processedAt` (not null); Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor`. Repository `JpaRepository<ProcessedEvent, String>` |
| 8 | `kafka/KafkaConfig.java` | see below |
| 9 | `kafka/CorrelationIdInterceptor.java` | see below |
| 10 | `web/CorrelationIdFilter.java` | see below |

**`OutboxEvent`** — `@Entity`, table `outbox`, Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`:

| Field | Type | Column |
|---|---|---|
| `id` | `Long` | `@Id`, `IDENTITY` |
| `eventId` | `String` | not null, unique |
| `aggregateId` | `String` | not null |
| `eventType` | `String` | not null |
| `payload` | `String` | not null, `columnDefinition = "text"` |
| `createdAt` | `Instant` | not null |
| `publishedAt` | `Instant` | nullable: null = not sent yet |
| `correlationId` | `String` | nullable |

Plus `static of(eventId, eventType, aggregateId, payload, cid)` that builds one with `createdAt = Instant.now()`.

**`OutboxWriter`** — `@Component`, `@RequiredArgsConstructor`; fields `OutboxRepository` and `ObjectMapper` (`tools.jackson.databind.ObjectMapper`: Boot 4 uses Jackson 3, whose `writeValueAsString` throws an unchecked exception, so no `try`). One method `write(String eventType, String aggregateId, Object payload)`:

1. build an `EventEnvelope` with a random UUID, the type, version `1`, the aggregate ID, `Instant.now()`, the payload;
2. `cid` = `MDC.get(Correlation.MDC_KEY)`, or a new UUID if null;
3. save `OutboxEvent.of(...)` with the envelope serialized to JSON.

It has no `@Transactional` of its own: it must run inside the caller's transaction, which is the whole point.

**`OutboxRelay`** — `@Component`, `@Slf4j`, `@RequiredArgsConstructor`; fields `OutboxRepository`, `KafkaTemplate<String, String>`. One method `relay()` with `@Scheduled(fixedDelay = 1000)` and Spring's `@Transactional`. For each row of `lockUnpublishedEvents(100)`:

1. `new ProducerRecord<>("order-events", aggregateId, payload)` — the key is the order ID, so every event of one order lands in the same partition, in order;
2. headers: `correlationId` (if not null) and `eventType`, both as UTF-8 bytes;
3. remember the current MDC value, put the row's correlation ID into MDC (so the log line carries it), and restore the old value in `finally`;
4. `kafka.send(record).join()` — wait for the broker. A failure throws, the transaction rolls back, `published_at` stays null, the next run retries;
5. log `Published {type} {eventId} for aggregate {id}`;
6. `setPublishedAt(Instant.now())` (saved at commit; no explicit `save`).

`FOR UPDATE SKIP LOCKED` lets the relays of two replicas run at the same time: rows one has locked are skipped by the other, so nothing is sent twice by two Pods and nobody waits.

**`KafkaConfig`** — `@Configuration`, two beans. Spring Boot attaches both to every `@KafkaListener` on its own:

| Bean | Value | Why |
|---|---|---|
| `RecordMessageConverter kafkaMessageConverter()` | `new JacksonJsonMessageConverter()` | converts the record's String value into whatever the listener parameter is (`EventEnvelope<JsonNode>`) |
| `DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template)` | recoverer `new DeadLetterPublishingRecoverer(template)`; backoff `new ExponentialBackOff(1000L, 2.0)` with `setMaxElapsedTime(10_000L)`; handler `new DefaultErrorHandler(recoverer, backOff)` | waits 1 s, 2 s, 4 s, 8 s between attempts (the backoff stops once 10 s of waiting is used up; the last wait overshoots, so about 15 s in all), then publishes the record to `<topic>-dlt` (Spring Kafka 4's default name; old tutorials say `.DLT`), same partition. Conversion errors (bad JSON) are not retried at all |

Per service, the handler also gets `addNotRetryableExceptions(...)` for errors that retrying cannot fix. order-service adds one in stage 6.

**`CorrelationIdInterceptor`** — `@Component`, implements `RecordInterceptor<Object, Object>`; a `private static final ThreadLocal<String> PREVIOUS`.

- `intercept(record, consumer)`: save the current MDC value in `PREVIOUS`; read `record.headers().lastHeader("correlationId")`; if present, put its UTF-8 value into MDC; return the record.
- `afterRecord(record, consumer)`: take `PREVIOUS`, remove it, and restore it into MDC (or remove the MDC key if it was null).

Listener threads are reused, so leaving one record's ID in MDC would stamp it on the next record's logs.

**`CorrelationIdFilter`** — `@Component`, extends `OncePerRequestFilter`. A `Pattern` `[A-Za-z0-9-]{1,64}`. In `doFilterInternal`: take the `X-Correlation-Id` header if it matches the pattern, otherwise a new UUID; put it into MDC; set it as a response header; call the chain in `try`, remove the MDC key in `finally`. The pattern stops a client from injecting line breaks that would forge log lines.

### Order changes

| File | Change |
|---|---|
| `enums/OrderStatus.java` | move the enum to package `enums`; keep `CREATED, PAID, SHIPPED, DELIVERED, CANCELLED` (the seeder uses them) and add `PENDING, AWAITING_PAYMENT, CONFIRMED` |
| `entity/Order.java` | add `productSku` (not null), `int quantity` (not null), `LocalDateTime updatedAt` (not null), `cancelReason` (length 50, nullable), `@Version Long version`. Add `static Order pending(CreateOrderRequest req)`: `orderNumber` = `"ORD-"` + the first 8 characters of a UUID in upper case; status `PENDING`; fields from the request; `createdAt` and `updatedAt` now |
| `dto/CreateOrderRequest.java` | record `(String customerName, BigDecimal totalAmount, String currency, String productSku, int quantity)` |
| `dto/OrderCreated.java` | record `(Long orderId, String orderNumber, String customerName, BigDecimal totalAmount, String currency, String productSku, int quantity)` + `static from(Order)` |
| `service/OrderService.java` | `create`: keep `@Transactional` and `@CacheEvict`; `orderRepository.save(Order.pending(req))`, then `writer.write("OrderCreated", order.getId().toString(), OrderCreated.from(order))` |
| `config/OrderDataSeeder.java` | every seeded order gets `productSku("TEST-001")`, `quantity(1)`, `updatedAt(now)` |
| `OrderServiceApplication.java` | add `@EnableScheduling` (the relay is `@Scheduled`) |

`application.yaml` `spring.kafka`:

| Key | Value | Change from THANG-2 |
|---|---|---|
| `producer.value-serializer` | `org.apache.kafka.common.serialization.StringSerializer` | was the JSON serializer: the outbox already holds JSON text |
| `producer.properties.spring.json.add.type.headers` | — | delete |
| `consumer.auto-offset-reset` | `earliest` | new |
| `consumer.key-deserializer` / `value-deserializer` | `StringDeserializer` both | new |

No `consumer.group-id` in order-service: its two listeners name their own groups. Add `logging.pattern.level: "%5p [cid=%X{correlationId:-}]"`: every log line now shows the correlation ID.

### The one-time database reset

`orders` already has rows, and Hibernate cannot add `NOT NULL` columns to it. Drop it (the admin is a superuser in the Postgres image), clear Redis (it caches old `Order` JSON), then redeploy; the seeder refills it.

```bash
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c 'DROP TABLE IF EXISTS orders'
kubectl exec -n dev order-redis-0 -- redis-cli FLUSHALL
```

### Check

Deploy order-service with the check loop, then:

```bash
curl -si -X POST localhost:8090/api/orders/ -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":1}' | head -5
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb \
  -c "select event_type, correlation_id, published_at is not null as sent from outbox order by id desc limit 3;"
```

- Response `201` with header `X-Correlation-Id: demo-1`.
- The outbox row has `correlation_id = demo-1` and `sent = t` within a second.
- The Kafka console consumer (check loops) shows `correlationId:demo-1,eventType:OrderCreated` and the envelope JSON.
- Drill: `kubectl scale deploy strimzi-cluster-operator -n kafka --replicas=0`, then `kubectl delete pod -n kafka jaka-dual-role-0`. With the operator stopped, nothing recreates the broker, so Kafka stays down. POST an order: the API still answers `201` and the row shows `sent = f`. Scale the operator back to 1; it recreates the broker, and within about two minutes the row shows `t` (the relay may first have to wait out a send that times out).

---

## Stage 2 — Topics

**Goal:** the event topics for inventory and payment, and a dead-letter topic for each of the three.

**File:** `k8s/kafka/topics.yml`, add five `KafkaTopic`s next to `order-events` (same shape as THANG-2 stage 11):

| Name | Partitions | `retention.ms` |
|---|---|---|
| `inventory-events` | 3 | `604800000` (7 days) |
| `payment-events` | 3 | `604800000` |
| `order-events-dlt` | 3 | `2592000000` (30 days, time to investigate) |
| `inventory-events-dlt` | 3 | `2592000000` |
| `payment-events-dlt` | 3 | `2592000000` |

A DLT must exist **before** the first failure with **the same partition count** as its source: the recoverer writes to the same partition number. If Kafka auto-creates the DLT with 1 partition, a failed record from partition 2 has nowhere to go and the consumer gets stuck.

### Clear THANG-2's flat messages

`order-events` still holds the THANG-2 `OrderCreatedEvent` messages, which have no envelope: they carry an `eventId` but no `eventType` and no `payload`. A new consumer group (inventory's, in stage 3) starts at `earliest`, reads them, hits a `NullPointerException` on `switch (null)`, and sends each one to the DLT after the retries. Recreate the topic once (deleting the `KafkaTopic` makes the topic operator delete the real topic):

```bash
kubectl delete kafkatopic order-events -n kafka
kubectl wait --for=delete kafkatopic/order-events -n kafka --timeout=2m
kubectl apply -f k8s/kafka/topics.yml
kubectl get kafkatopic -n kafka
```

### Check

Six topics, `READY True`.

---

## Stage 3 — inventory-service

**Goal:** a new service that reserves stock for each `OrderCreated`, and that cannot oversell even when two orders race for the last item.

### Create the project

Spring Initializr (or copy order's Gradle files): Gradle Groovy, Java 17, Spring Boot 4.1.0, group `com.jaka`, artifact `inventory-service`, package `com.jaka.inventory_service`. Put it at `$APP_ROOT/inventory-service/inventory-service/` (the doubled folder like the others).

`build.gradle` dependencies: `spring-boot-starter-actuator`, `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-kafka`, runtime `org.postgresql:postgresql`, Lombok (compileOnly + annotationProcessor), and the test starters (`spring-boot-starter-kafka-test`, `spring-boot-testcontainers`, `testcontainers-kafka`, `spring-boot-starter-actuator-test`, `spring-boot-starter-webmvc-test`, `junit-platform-launcher`). No Redis.

| File | Content |
|---|---|
| `Dockerfile` | copy order's; change the jar name to `inventory-service-0.0.1-SNAPSHOT.jar` |
| `docker-compose.yml` | one `postgres:16-alpine`, db `inventorydb`, user `inventory_user` / `inventory_pass`, port `5435:5432`, a volume, a `pg_isready` healthcheck |
| `application.yaml` | name `inventory-service`; datasource `jdbc:postgresql://localhost:5435/inventorydb` with those credentials; `ddl-auto: update`, `open-in-view: false`; the Kafka block from stage 1 **plus** `consumer.group-id: inventory-service`; management as in order; `server.port: 8083`, `context-path: /api/inventory`; the `cid` log pattern |
| `InventoryServiceApplication.java` | `@SpringBootApplication`, `@EnableScheduling` |

### The kit

Copy all ten kit files from order-service, change the package, and set the relay's topic to `inventory-events`. In `KafkaConfig` add `addNotRetryableExceptions(ProductNotFoundException.class)` and `(ReservationNotFoundException.class)`.

### Domain

| File | Spec |
|---|---|
| `entity/Product.java` | table `products`; `@Id String sku` (length 50); `name` (200, not null); `int available` — not null, with `check = @CheckConstraint(name = "products_available_check", constraint = "available >= 0")` on `@Column`; `int reserved` — `@Builder.Default` 0, not null, check `reserved >= 0` (`products_reserved_check`); `@Version Long version`. Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` |
| `entity/ReservationStatus.java` | `RESERVED, RELEASED, COMMITTED` |
| `entity/Reservation.java` | table `reservations`; `@Id Long orderId` (one reservation per order; a soft link to order-service, no foreign key across services); `sku` (50, not null; a plain column, not `@ManyToOne`, to keep JSON flat); `int quantity`; `status` (`EnumType.STRING`, 20) |
| `exception/ProductNotFoundException.java` | `RuntimeException`, message `SKU not found: <sku>` |
| `exception/ReservationNotFoundException.java` | `RuntimeException`, message `Reservation not found for orderId: <id>` |
| `repository/ProductRepository.java`, `ReservationRepository.java` | plain `JpaRepository<Product, String>`, `<Reservation, Long>` |
| `config/InventoryDataSeeder.java` | `CommandLineRunner`, no-op if any product exists; seeds `TEST-001` (100), `TSHIRT-RED-M` (50), `TSHIRT-RED-L` (30), `MUG-WHITE` (5), `SOLD-OUT-001` (0) |
| `dto/` | `EventEnvelope`, and the records `OrderCreated`, `PaymentCompleted`, `PaymentFailed`, `InventoryReserved`, `InventoryRejected`, `InventoryReleased` exactly as in [Appendix B](#appendix-b--event-contract) (`OrderCancelled` comes in stage 5) |

### `service/InventoryService.java`

`@Service`, `@RequiredArgsConstructor`; repositories and `OutboxWriter`. Read methods `getAllProducts()`, `getProduct(sku)` (an `Optional`), `getAllReservations()`. Every write method is `@Transactional`:

| Method | Rule |
|---|---|
| `reserve(OrderCreated order)` | product by SKU, or throw `ProductNotFoundException`. If `available < quantity`: write `InventoryRejected` with reason `"Insufficient stock"` and return (no reservation row). Else: `available -= q`, `reserved += q`, save `Reservation(orderId, sku, q, RESERVED)`, write `InventoryReserved` |
| `commit(PaymentCompleted p)` | reservation by order ID, or throw `ReservationNotFoundException`. If not `RESERVED`, return (already handled). Else `reserved -= q` (the goods leave; `available` was already lowered by `reserve`), status `COMMITTED`. No event |
| `release(PaymentFailed p)` | reservation or throw. If not `RESERVED`, return. Else `reserved -= q`, `available += q`, status `RELEASED`, write `InventoryReleased` with the payment's reason |

Stage 5 adds `releaseForCancelledOrder`.

**Why two orders cannot oversell:** both read `MUG-WHITE` at `version` 7, both lower `available`; the first commit makes it version 8; the second `UPDATE … WHERE version = 7` matches no row and throws an optimistic-lock exception. The error handler retries that record, which now reads `available = 0` and rejects. The DB check constraint is the last line of defense if a bug skips the Java check.

### Listener: `consumer/OrderEventConsumer.java`

This is **the idempotent listener pattern**; every consumer in this build has the same shape.

| Part | Value |
|---|---|
| annotations | `@Component`, `@Slf4j`, `@RequiredArgsConstructor` |
| fields | `ProcessedEventRepository`, `InventoryService`, `ObjectMapper` |
| method | `on(EventEnvelope<JsonNode> event)` with `@KafkaListener(topics = "order-events", groupId = "inventory-service")` and Spring's `@Transactional` |
| step 1 | if `processed.existsById(event.eventId())`: log and return |
| step 2 | `switch (event.eventType())`: `"OrderCreated"` → `reserve(json.treeToValue(event.payload(), OrderCreated.class))`; `default` → log at debug and do nothing |
| step 3 | `processed.save(new ProcessedEvent(event.eventId(), Instant.now()))` |

Steps 1–3, the business rows and the outbox row are one transaction: either all commit or none do. Kafka's offset is committed after the method returns, so a crash in between means redelivery, and step 1 makes that a no-op. The `default` branch must not throw: `order-events` also carries `OrderConfirmed`, `RefundRequested` and more, and throwing would send each of them to the DLT.

`consumer/PaymentEventConsumer.java`: same pattern on `payment-events`, group `inventory-service`: `PaymentCompleted` → `commit`, `PaymentFailed` → `release`, default ignore (for example `PaymentRefunded`).

### Controller

`controller/InventoryController.java`: `GET /products`, `GET /products/{sku}` (`ResponseEntity.of(...)` gives 404 when empty), `GET /reservations`. With the context path these are `/api/inventory/products`, …

### Ship it to kind

| File in `jaka_manual/` | Content |
|---|---|
| `k8s/inventory-service/inventory-db.yml` | THANG-2 stage 3 Postgres file with `inventory` names: Service and StatefulSet `inventory-db`, `POSTGRES_DB=inventorydb`, `POSTGRES_USER=inventory_admin`, Secret `inventory-db-admin`. Use the **admin** in the readiness probe (the reference uses `inventory_user`; see Appendix F) |
| `k8s/inventory-service/create-app-role-job.yml` | THANG-2 stage 4 Job with `inventory` names |
| `charts/spring-service/values/inventory-dev.yml` | `image.repository: inventory-service`; `port: 8083`; `contextPath: /api/inventory`; env `SPRING_DATASOURCE_URL: jdbc:postgresql://inventory-db:5432/inventorydb`, `SPRING_DATASOURCE_USERNAME: inventory_user`, `KAFKA_BOOTSTRAP: jaka-kafka-bootstrap.kafka:9092`; `secretName: inventory-service-secret` |
| `charts/spring-service/values/inventory-prod.yml` | the dev file + the prod block from THANG-2 stage 7 |
| `k8s/gateway.yml` | add rule `PathPrefix /api/inventory` → `inventory-service:8083` |
| `scripts/cluster-up.sh` | `services=(order payment inventory)`; add `inventory` to the image cleanup regex |

Run `./scripts/cluster-up.sh` (it creates the Secrets from `.env`, the DB, the Job and the release).

### Check

```bash
curl -s localhost:8090/api/inventory/products/TEST-001
curl -s -X POST localhost:8090/api/orders/ -H 'Content-Type: application/json' \
  -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":2}'
curl -s localhost:8090/api/inventory/products/TEST-001
curl -s localhost:8090/api/inventory/reservations | jq '.[-1]'
```

`available` drops by 2, `reserved` rises by 2, the last reservation is `RESERVED`. An order for `SOLD-OUT-001` produces `InventoryRejected` on `inventory-events` and no reservation.

---

## Stage 4 — payment-service charges

**Goal:** payment-service charges each reserved order once, approves or declines it, and publishes the result.

### Changes

| File | Change |
|---|---|
| `build.gradle` | already has Kafka from THANG-2 |
| `application.yaml` | `spring.kafka`: keep `group-id: payment-service`, `auto-offset-reset: earliest`; `value-deserializer` → `StringDeserializer`; add the `producer` block from stage 1; add the `cid` log pattern |
| `PaymentServiceApplication.java` | `@EnableScheduling` |
| kit | copy the ten files, package `com.jaka.payment_service`, relay topic `payment-events`, no extra non-retryable exceptions |
| `repository/PaymentRepository.java` | add `boolean existsByOrderId(Long orderId)` and `Optional<Payment> findByPaymentReference(String ref)` |
| `dto/` | `InventoryReserved`, `PaymentCompleted`, `PaymentFailed` (stage 5 adds `RefundRequested`, `PaymentRefunded`), per [Appendix B](#appendix-b--event-contract) |

`entity/Payment.java` and `PaymentStatus` (`PENDING, COMPLETED, FAILED, REFUNDED`) stay as they are.

### `PaymentService.charge(InventoryReserved req)`

`@Transactional`, `@CacheEvict(value = "payments", allEntries = true)`:

1. If `existsByOrderId(orderId)`, return. A second dedupe layer, by business key: one order is charged at most once even if two different events asked for it.
2. Fake gateway: approved when `totalAmount <= 1000` **and** the customer is not named `fail` (ignore case).
3. Save a `Payment`: reference `"PAY-"` + 8 upper-case UUID characters, the order ID, amount, currency, method `CREDIT_CARD`, status `COMPLETED` or `FAILED`, `createdAt` now.
4. Write `PaymentCompleted(orderId, reference, amount, currency, customerName)` or `PaymentFailed(orderId, amount, currency, customerName, "CARD_DECLINED")`.

### Listener

`consumer/InventoryEventConsumer.java`: the idempotent listener pattern on `inventory-events`, group `payment-service`; `InventoryReserved` → `charge`; everything else ignored.

### Check

```bash
curl -s -X POST localhost:8090/api/orders/ -H 'Content-Type: application/json' \
  -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":1}'
curl -s localhost:8090/api/payments/ | jq '.[-1]'
curl -s localhost:8090/api/inventory/reservations | jq '.[-1]'
```

The last payment is `COMPLETED`; the reservation is `COMMITTED`. With `"totalAmount":1500` the payment is `FAILED` and the reservation `RELEASED`, with stock back.

The order itself still says `PENDING`: nobody tells order-service yet. That is stage 5.

Watch out: the payment seeder created payments for order IDs 1–8. If order IDs ever restart at 1 without the order seeder (an empty `orders` table), `charge` treats orders 1–8 as already charged and silently skips them.

---

## Stage 5 — The Saga

**Goal:** every order ends `CONFIRMED` or `CANCELLED`, and every partial step is undone when a later step fails, even when a service is down for minutes.

**Why a Saga:** the order, the stock and the payment live in three databases. There is no transaction across them (and 2PC would lock all three while waiting for the slowest). A Saga is a chain of local transactions, each publishing an event; when a step fails, earlier steps are undone by **compensations**: new local transactions that reverse the business effect. This build uses **choreography**: no central coordinator, each service reacts to events.

### order-service: state transitions on `Order`

Add four methods. Each only acts from the allowed states, so a duplicate or late event cannot move an order backwards (a "semantic lock": `PENDING` and `AWAITING_PAYMENT` mean "in progress").

| Method | From | To |
|---|---|---|
| `awaitPayment()` | `PENDING` | `AWAITING_PAYMENT` |
| `confirm()` | `AWAITING_PAYMENT` | `CONFIRMED` |
| `cancel(String reason)` | `PENDING` or `AWAITING_PAYMENT` | `CANCELLED`, sets `cancelReason` |
| private `touch()` | — | `updatedAt = now`; called by the three above |

### order-service: new DTOs

`OrderConfirmed`, `OrderCancelled` (with `static from(Order)` taking `cancelReason` as the reason), `RefundRequested`, and copies of `InventoryRejected`, `InventoryReleased`, `PaymentCompleted`, `PaymentFailed`. Fields in [Appendix B](#appendix-b--event-contract).

### order-service: `consumer/OrderSagaListener.java`

| Part | Value |
|---|---|
| fields | `OrderRepository`, `ProcessedEventRepository`, `OutboxWriter`, `ObjectMapper` |
| annotations on `on(EventEnvelope<JsonNode> event)` | `@KafkaListener(topics = {"inventory-events", "payment-events"}, groupId = "order-service")`, `@Transactional`, `@CacheEvict(value = "orders", allEntries = true)` (statuses change here, and `GET /api/orders/` is cached for 10 minutes) |
| dedupe | return if `eventId` already processed |
| load | the order by `Long.valueOf(event.aggregateId())`, `orElseThrow()`; remember `before = status` |

The `switch` on `eventType`:

| Event | Action |
|---|---|
| `InventoryReserved` | `awaitPayment()` |
| `InventoryRejected` | `cancel(reason from the payload)` |
| `PaymentFailed` | `cancel(reason from the payload)` |
| `PaymentCompleted` | if the order is already `CANCELLED` (the timeout won the race): log a warning and write `RefundRequested(orderId, paymentReference, amount, currency, customerName, cancelReason)`. Otherwise `confirm()` |
| anything else | nothing |

Then, **only if the status changed**: `CONFIRMED` → write `OrderConfirmed(orderId, orderNumber, customerName, totalAmount, currency)`; `CANCELLED` → write `OrderCancelled.from(order)`. Last, save the `ProcessedEvent`.

### order-service: `scheduler/OrderTimeoutJob.java`

| Part | Value |
|---|---|
| repository method | `OrderRepository.findByStatusAndUpdatedAtBefore(OrderStatus status, LocalDateTime before)` |
| method | `expireStuckOrders()` with `@Scheduled(fixedDelay = 60_000)`, `@Transactional`, `@CacheEvict(value = "orders", allEntries = true)` |
| rule | every order `AWAITING_PAYMENT` with `updatedAt` older than 5 minutes: `cancel("PAYMENT_TIMEOUT")` and write `OrderCancelled` |

`awaitPayment()` touched `updatedAt`, so "5 minutes" counts from the moment stock was reserved.

### inventory-service: compensation for `OrderCancelled`

`InventoryService.releaseForCancelledOrder(OrderCancelled order)`, `@Transactional`. It must be idempotent and must not fail for orders that never had a reservation:

| Reservation | Action |
|---|---|
| none | return (cancelled because stock was rejected) |
| `RESERVED` | `reserved -= q`, then the common part |
| `COMMITTED` | (a late `PaymentCompleted` reached inventory first; order-service refunds it) nothing on `reserved`, then the common part |
| `RELEASED` | return (already released by `PaymentFailed`, or a redelivery) |
| common part | `available += q`, status `RELEASED`, write `InventoryReleased` with the order's reason |

In `OrderEventConsumer` add `case "OrderCancelled"` → `releaseForCancelledOrder(...)`. Add the `OrderCancelled` record to inventory's `dto/`.

### payment-service: compensation for `RefundRequested`

| Item | Spec |
|---|---|
| `dto/RefundRequested`, `dto/PaymentRefunded` | per Appendix B |
| `PaymentService.refund(RefundRequested req)` | `@Transactional`, `@CacheEvict("payments", allEntries)`; find by reference or throw `IllegalStateException` (retried, then `order-events-dlt`); if status is not `COMPLETED`, return (already refunded, or never charged); set `REFUNDED`; write `PaymentRefunded` |
| `consumer/OrderEventConsumer.java` | the idempotent pattern on `order-events`, group `payment-service`; only `RefundRequested` → `refund`; everything else ignored |

### Every branch, end to end

| Case | Order | Reservation | Payment | Stock (available) |
|---|---|---|---|---|
| amount ≤ 1000, stock ok | `CONFIRMED` | `COMMITTED` | `COMPLETED` | −q |
| SKU `SOLD-OUT-001` | `CANCELLED` (`Insufficient stock`) | none | none | unchanged |
| amount > 1000 | `CANCELLED` (`CARD_DECLINED`) | `RELEASED` | `FAILED` | unchanged |
| payment-service down > 5 min, then back | `CANCELLED` (`PAYMENT_TIMEOUT`) | `RELEASED` | `COMPLETED` then `REFUNDED` | unchanged |

In the `CARD_DECLINED` case inventory receives two releases (`PaymentFailed`, then `OrderCancelled`); the second finds `RELEASED` and does nothing. That is the idempotency rule paying off.

### Check

Deploy the three services, then check the first three rows of the table by hand (stage 8 automates all four).

---

## Stage 6 — CQRS read model

**Goal:** `GET /api/orders/{id}/summary` answers "where is my order?" with stock and payment status, from order-service's own database, without calling the other services.

**Why:** the truth is split: order status in `orderdb`, reservation in `inventorydb`, payment in `paymentdb`. Calling three services per page view couples them. Instead order-service keeps a **read model**, a table shaped for that one question, fed by the events it already receives.

| File | Spec |
|---|---|
| `enums/SummaryInventoryStatus.java` | `PENDING, RESERVED, REJECTED, RELEASED` |
| `enums/SummaryPaymentStatus.java` | `PENDING, COMPLETED, FAILED, SKIPPED` |
| `entity/OrderSummary.java` | below |
| `entity/ProjectedEvent.java` + repository | like `ProcessedEvent`, table `projected_events`, field `projectedAt` |
| `exception/OrderSummaryNotFoundException.java` | `RuntimeException`, message `No order_summary for order <id>` |
| `kafka/KafkaConfig.java` | `addNotRetryableExceptions(OrderSummaryNotFoundException.class)` |
| `repository/OrderSummaryRepository.java` | `JpaRepository<OrderSummary, Long>` |
| `OrderSummaryResponse` record | below |
| `consumer/OrderSummaryProjector.java` | below |
| `controller/OrderSummaryController.java` | `GET /{id}/summary`: found → 200 with the response; not found → 404 |
| `service/OrderService.create` | after saving the order, `orderSummaryRepository.save(OrderSummary.from(order))` in the same transaction |
| `application.yaml` | `app.projector.enabled: true` |

**`OrderSummary`**, table `order_summary`, `@Getter`, `@NoArgsConstructor`:

| Field | Note |
|---|---|
| `@Id Long orderId` | no generator: the order's ID |
| `@Version Long version` | two events for one order processed at once (different partitions are possible here, since the projector listens to two topics) → one fails and is retried, instead of overwriting the other |
| `orderNumber`, `customerName`, `productSku`, `Integer quantity`, `BigDecimal totalAmount`, `currency` | copied once |
| `inventoryStatus`, `paymentStatus` | enums, `EnumType.STRING`, length 20, not null |
| `paymentReference`, `failureReason` | nullable |
| `Instant createdAt`, `updatedAt` | |

`static from(Order)` sets both statuses to `PENDING`. Transition methods move **only forward**, so an old or repeated event cannot undo a newer state:

| Method | Guard | Effect |
|---|---|---|
| `onInventoryReserved()` | inventory `PENDING` | `RESERVED` |
| `onInventoryRejected(reason)` | inventory `PENDING` | `REJECTED`; payment `SKIPPED` (never charged); `failureReason` |
| `onInventoryReleased(reason)` | inventory not `RELEASED` | `RELEASED` |
| `onPaymentCompleted(ref)` | payment `PENDING` | `COMPLETED`, `paymentReference` |
| `onPaymentFailed(reason)` | payment `PENDING` | `FAILED`, `failureReason` |

Each calls a private `touch()` (`updatedAt` now).

**`OrderSummaryResponse`**: record `(orderId, orderNumber, customerName, productSku, quantity, totalAmount, currency, String inventoryStatus, String paymentStatus, paymentReference, failureReason, String overallStatus, Instant updatedAt)` with `static from(OrderSummary)`. `overallStatus` is computed when read, never stored: inventory `REJECTED` → `OUT_OF_STOCK`; payment `FAILED` → `PAYMENT_FAILED`; payment `COMPLETED` → `COMPLETED`; otherwise `PROCESSING`. (The reference keeps this record in `entity/`; `dto/` is the better home.)

**`OrderSummaryProjector`**: the idempotent pattern with three differences:

- `@KafkaListener(topics = {"inventory-events", "payment-events"}, groupId = "order-summary-projector", autoStartup = "${app.projector.enabled:true}")`: a **separate group**, so it reads every event independently of the Saga listener;
- its own dedupe table `projected_events`: sharing `processed_events` would make whichever listener runs second skip the event;
- the summary is loaded by order ID or `OrderSummaryNotFoundException` (straight to the DLT: retrying cannot create the row).

Cases: `InventoryReserved`, `InventoryRejected`, `InventoryReleased`, `PaymentCompleted`, `PaymentFailed` → the matching method; default → log at debug. Log `Projected {type} -> order {id} [{inventory} / {payment}]`.

### Backfill: orders that existed before the read model

The new group starts at `earliest` and replays every old event, but orders created before this stage have no summary row, so each of their events would go to the DLT. So the new image must start with the projector **off**, the backfill runs, and only then the projector starts.

`APP_PROJECTOR_ENABLED` is the env-var form of `app.projector.enabled`. Pass it through the chart (it lands in the ConfigMap), not with `kubectl set env`: Helm's three-way merge keeps env vars it never managed, so a `kubectl set env` value would survive every later `helm upgrade`.

1. Build and load the new order-service image (the first three lines of the check loop), then install it with the projector off:

```bash
helm upgrade --install order-service charts/spring-service -n dev -f charts/spring-service/values/order-dev.yml \
  --set image.tag="$tag" --set env.APP_PROJECTOR_ENABLED=false
kubectl rollout status deploy/order-service -n dev
```

2. Copy the existing orders into the read model, all `PENDING` (`ddl-auto` created the table at startup):

```bash
kubectl exec -n dev order-db-0 -- psql -U order_admin -d orderdb -c "
INSERT INTO order_summary (order_id, version, order_number, customer_name, product_sku, quantity,
                           total_amount, currency, inventory_status, payment_status, created_at, updated_at)
SELECT id, 0, order_number, customer_name, product_sku, quantity, total_amount, currency,
       'PENDING', 'PENDING', created_at, now()
FROM orders ON CONFLICT (order_id) DO NOTHING;"
```

3. Install again without the override. The key leaves the ConfigMap, the `checksum/config` annotation changes, the Pods restart, and the projector replays the old events onto the rows:

```bash
helm upgrade --install order-service charts/spring-service -n dev -f charts/spring-service/values/order-dev.yml --set image.tag="$tag"
```

`relation "order_summary" does not exist` in step 2 means step 1 did not finish (the old image is still running).

### Check

```bash
curl -s localhost:8090/api/orders/<id>/summary | jq
```

A confirmed order shows `RESERVED / COMPLETED / COMPLETED`; a `SOLD-OUT-001` order `REJECTED / SKIPPED / OUT_OF_STOCK`.

**Rebuild from events** (proves the read model is disposable). Note a few summaries first, then:

1. Turn the projector off: the step-1 `helm upgrade` with `--set env.APP_PROJECTOR_ENABLED=false` (same tag), and wait for the rollout.
2. Empty the read model and its dedupe table: `TRUNCATE order_summary; TRUNCATE projected_events;` through `psql` as above.
3. Run the backfill `INSERT` again. Required: the projector only updates rows, it never inserts them, so without this every replayed event fails with `OrderSummaryNotFoundException`.
4. Move the group back to the start:

```bash
kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group order-summary-projector --reset-offsets --to-earliest --topic inventory-events --topic payment-events --execute
```

5. Turn the projector on: the step-3 `helm upgrade`. Every summary comes back identical.

The reset fails with `Assignments can only be reset if the group ... is inactive` while a projector is still running (step 1 not finished). Never reset the groups of inventory or payment: they would redo business work.

---

## Stage 7 — notification-service

**Goal:** a fourth service that emails the customer about four events, keeps a history, and never emails twice for one event.

### Create the project

As in stage 3, artifact `notification-service`, package `com.jaka.notification_service`, plus `spring-boot-starter-mail`. No Redis, no scheduling, no producer: notification only consumes, so it needs **no** outbox, relay or `processed_events`.

| File | Content |
|---|---|
| `Dockerfile` | jar `notification-service-0.0.1-SNAPSHOT.jar` |
| `docker-compose.yml` | Postgres `notificationdb`, `notification_user` / `notification_pass`, port `5436:5432` |
| `application.yaml` | datasource on 5436; `spring.mail.host: ${MAIL_HOST:localhost}`, `spring.mail.port: ${MAIL_PORT:1025}`; Kafka **consumer only**, `group-id: notification-service`; `server.port: 8084`, `context-path: /api/notifications`; the `cid` log pattern |
| kit | only `Correlation`, `EventEnvelope`, `KafkaConfig`, `CorrelationIdInterceptor` (the reference puts the last two in `config/`) |

`KafkaConfig` here also sets a `RetryListener` on the handler: `failedDelivery` logs a warning `Attempt {n} failed for {topic}-{partition}@{offset}: {message}`; `recovered` logs an error `Gave up on …, sent to DLT`. A dead SMTP server is the likeliest failure here, and these lines say which email was lost.

### Domain

| File | Spec |
|---|---|
| `entity/NotificationChannel.java` | `EMAIL, SMS, LOG` |
| `entity/NotificationStatus.java` | `SENT, FAILED` |
| `entity/Notification.java` | table `notifications`; `id` IDENTITY; `eventId` (36, not null, **unique**: this column is the dedupe key); `eventType` (50); `Long orderId`; `channel` (enum, 20); `recipient` (120); `message` (500); `status` (enum, 20); `LocalDateTime sentAt` — all not null |
| `repository/NotificationRepository.java` | `boolean existsByEventId(String eventId)` |
| `dto/` | `EventEnvelope`, `OrderCreated`, `InventoryRejected`, `PaymentCompleted`, `PaymentFailed` |

### `service/NotificationTemplates.java`

`@Component`; a nested record `Message(Long orderId, String customerName, String subject, String body)`; `Optional<Message> render(EventEnvelope<JsonNode> event)` with a `switch` expression:

| Event | Subject |
|---|---|
| `OrderCreated` | `Đơn <orderNumber> đã được tạo` |
| `InventoryRejected` | `Đơn #<orderId>: sản phẩm <sku> đã hết hàng` |
| `PaymentCompleted` | `Thanh toán đơn #<orderId> thành công` |
| `PaymentFailed` | `Thanh toán đơn #<orderId> thất bại` |
| anything else | `Optional.empty()` |

Bodies greet the customer by name and repeat the key fields (quantity × SKU and total; amount and payment reference; the decline reason and "stock was returned").

### `service/NotificationService.handle(EventEnvelope<JsonNode> event)`

`@Transactional`:

1. if `existsByEventId`, return;
2. `render`; if empty, return;
3. recipient = customer name lower-cased, whitespace replaced by `.`, plus `@example.com`;
4. save a `Notification` (`EMAIL`, subject as `message`, `SENT`, now) and `flush()`: a duplicate `eventId` fails here, on the unique index, **before** the email goes out;
5. send a `SimpleMailMessage` from `shop@jaka.local` with `JavaMailSender`; log `Sent email to …`.

If sending throws, the transaction rolls back, the record is retried, and after the last retry (about 15 s) it lands in the DLT with header `kafka_dlt-original-consumer-group: notification-service`.

`consumer/EventConsumer.java`: one `@KafkaListener(topics = {"order-events", "payment-events", "inventory-events"}, groupId = "notification-service")` that calls `handle`. `controller/NotificationController.java`: `GET` the history list.

### Ship it to kind

| File in `jaka_manual/` | Content |
|---|---|
| `k8s/notification-service/notification-db.yml`, `create-app-role-job.yml` | as for inventory, with `notification` names |
| `k8s/notification-service/mailpit.yml` | Deployment `mailpit` (1 replica, image `axllent/mailpit` — pin a version, Appendix F; requests `cpu: 50m`, `memory: 64Mi`, limits `cpu: 250m`, `memory: 256Mi`; named ports `smtp` 1025 and `ui` 8025) and Service `mailpit` with ports `smtp` 1025 → `smtp`, `ui` 8025 → `ui` |
| `values/notification-dev.yml` | `image.repository: notification-service`; `port: 8084`; `contextPath: /api/notifications`; env URL `jdbc:postgresql://notification-db:5432/notificationdb`, user `notification_user`, `KAFKA_BOOTSTRAP`, `MAIL_HOST: mailpit`, `MAIL_PORT: "1025"` (quoted); `secretName: notification-service-secret` |
| `values/notification-prod.yml` | dev + prod block |
| `k8s/gateway.yml` | `PathPrefix /api/notifications` → `notification-service:8084` |
| `scripts/cluster-up.sh` | `services=(order payment inventory notification)`; preload `axllent/mailpit:latest`; cleanup regex with all four |

**Before the first deploy of notification** (that is, before you run `cluster-up.sh` with the new array), move its group to the end of each topic, or every test order from stages 1–6 gets an email. The group does not exist yet; the tool creates it with these offsets:

```bash
for t in order-events inventory-events payment-events; do
  kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group notification-service --topic "$t" --reset-offsets --to-latest --execute
done
```

### Check

```bash
kubectl port-forward -n dev svc/mailpit 8025:8025
curl -s -X POST localhost:8090/api/orders/ -H 'Content-Type: application/json' \
  -d '{"customerName":"Tai","totalAmount":20,"currency":"USD","productSku":"TEST-001","quantity":1}'
```

- http://localhost:8025 shows two mails to `tai@example.com`: created, payment succeeded.
- `customerName: "fail"` → a "failed" mail; `SOLD-OUT-001` → an "out of stock" mail.
- Scale notification to 0, POST an order: stock and payment still happen. Scale back: the mail arrives late. Fan-out consumers are independent.

---

## Stage 8 — Prove it: `saga-demo.sh` and the failure drills

**Goal:** one script that proves the four Saga branches end to end, plus drills for duplicates, poison messages, races and the correlation ID.

**File:** `scripts/saga-demo.sh`, executable. It runs on kind through the port-forward, and on EKS (stage 18) through the NLB.

### Header and helpers

| Item | Spec |
|---|---|
| safety | `#!/usr/bin/env bash`, `set -euo pipefail` |
| `base` | `${BASE_URL:-http://localhost:8090}` |
| `sku` | `TEST-001`; `failures=0` |
| `order_status id` | `curl -sf "$base/api/orders/"` (trailing slash: without it Spring answers 302), `jq` select the order by id, print `"<status> <cancelReason or empty>"` |
| `stock` | `GET /api/inventory/products/$sku` → `"<available>/<reserved>"` |
| `reservation id` | `GET /api/inventory/reservations` → status of that order, or `none` |
| `payment id` | `GET /api/payments/` → status for that order, or `none` |
| `create_order name amount sku` | POST JSON (`currency USD`, `quantity 1`), print the new `id`; exit with a message if empty |
| `wait_status id expected seconds` | poll every 3 s until the status starts with `expected`; on timeout print the last status and return 1 |
| `check label actual expected` | print `✅` or `❌ (expected …)` aligned; count failures |
| pre-flight | `curl -sf $base/api/orders/actuator/health` or exit with "gateway not reachable — start the port-forward" |

### Scenarios

| # | Do | Expect |
|---|---|---|
| 1 | remember `stock`; order Alice, 100, `TEST-001` | `CONFIRMED` within 60 s; reservation `COMMITTED`; payment `COMPLETED`; stock `a/r` → `(a-1)/r` |
| 2 | order Bob, 100, `SOLD-OUT-001` | `CANCELLED Insufficient stock`; reservation `none`; payment `none` |
| 3 | remember stock; order Carol, 1500 | `CANCELLED CARD_DECLINED`; after `sleep 3` reservation `RELEASED`; payment `FAILED`; stock unchanged |
| 4 (only with `--timeout`) | remember payment's replicas; scale it to 0; `kubectl wait --for=delete pod -l app=payment-service` (rollout status returns while Pods still terminate and consume); remember stock; order Dave, 100; wait up to 480 s | `CANCELLED PAYMENT_TIMEOUT`; reservation `RELEASED`; stock unchanged. Then scale payment back, wait for rollout, poll up to 120 s: payment `REFUNDED`; order still `CANCELLED PAYMENT_TIMEOUT` |

End: `All checks passed`, or the count and `exit 1`.

Scenario 4 shows the whole compensation chain: `InventoryReserved` waits in Kafka while payment is down → timeout cancels → inventory releases → payment returns and charges the stale event → order-service sees `PaymentCompleted` on a cancelled order → `RefundRequested` → `PaymentRefunded`.

```bash
./scripts/saga-demo.sh
./scripts/saga-demo.sh --timeout
```

### Failure drills

Open a producer that takes `key:value` lines:

```bash
kubectl exec -it -n kafka jaka-dual-role-0 -- bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic order-events --property parse.key=true --property key.separator=:
```

The duplicate drill pastes this line 3 times (key `900`, then the envelope):

```
900:{"eventId":"dup-test-1","eventType":"OrderCreated","version":1,"aggregateId":"900","occurredAt":"2026-01-01T00:00:00Z","payload":{"orderId":900,"orderNumber":"ORD-DUP","customerName":"Dup","totalAmount":10,"currency":"USD","productSku":"TEST-001","quantity":1}}
```

| Drill | Do | Expect |
|---|---|---|
| Duplicate | paste the line above 3 times | `available` of `TEST-001` drops by exactly 1; one `processed_events` row for `dup-test-1` in inventorydb. Side effect: order 900 does not exist in orderdb, so the follow-up `InventoryReserved` and `PaymentCompleted` for 900 end up in `inventory-events-dlt` and `payment-events-dlt` from the `order-service` and `order-summary-projector` groups. Expected for this drill |
| Poison | send `901:{not json`, then a valid event | the bad record is not retried (a conversion error): it goes straight to `order-events-dlt`, once per group that reads `order-events` (inventory, payment, notification: 3 copies, told apart by header `kafka_dlt-original-consumer-group`; the reason is in `kafka_dlt-exception-message`); the valid event after it is processed |
| Unknown SKU | POST an order with SKU `NOPE` | `ProductNotFoundException` is not retried: straight to `order-events-dlt`; the order stays `PENDING` for good (the timeout job only watches `AWAITING_PAYMENT`); a real system would alert |
| Race | `MUG-WHITE` has 5: order 4 (small amount, so payment succeeds), then send two orders of 1 in parallel (`&` and `wait`) | one `InventoryReserved`, one `InventoryRejected`; `available` ends at 0, never −1 |
| Correlation ID | POST with `-H 'X-Correlation-Id: demo-cid-001'` | `kubectl logs deploy/<svc> --since=10m \| grep cid=demo-cid-001` finds lines in all four services; the console consumer with `print.headers=true` shows four events carrying it: `OrderCreated`, `InventoryReserved`, `PaymentCompleted`, `OrderConfirmed` |

Read a DLT:

```bash
kubectl exec -n kafka jaka-dual-role-0 -- bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic order-events-dlt --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000
```

If only `OrderCreated` carries `demo-cid-001`, a downstream service lacks the interceptor: its `OutboxWriter` found an empty MDC and started a new ID.

---

# Part B — the same system on EKS

Everything in this part is in `jaka_manual/` (your Terraform from THANG-1 plus the THANG-2/Part A files).

## Stage 9 — Shared modules: provider pins and subnet tags

**Goal:** the THANG-1 modules can be called from a root that uses AWS provider 6, and the subnets carry the tags the LB Controller looks for. `envs/dev` must keep working unchanged.

### Provider pins

`terraform-aws-modules/eks` v21 requires `hashicorp/aws >= 6.0`. The THANG-1 modules say `~> 5.50`, which means `>= 5.50, < 6.0`. One root calling both can never satisfy them, and `terraform init` fails with "no available releases match the given constraints".

The rule: **modules declare a minimum, roots pin.** Change `version = "~> 5.50"` to `">= 5.50"` in the `versions.tf` of the modules `envs/dev-eks` will call: `network`, `security`, `storage`, `redis`, `postgres` (and `ecr`, new in stage 10). Leave `alb`, `service`, `observability`, `stack` and `envs/dev` alone; `envs/dev` keeps pinning `~> 5.50`, so its lock file does not change.

### `modules/network` subnet tags

| File | Change |
|---|---|
| `variables.tf` | `public_subnet_tags` and `private_subnet_tags`, both `map(string)`, default `{}`, with a description |
| `main.tf` | `aws_subnet.public` tags = `merge({ Name = …, Tier = "public" }, var.public_subnet_tags)`; same for private |
| `tests/network.tftest.hcl` | a run that passes `{ "kubernetes.io/role/elb" = "1" }` and asserts every public subnet has that tag |

The LB Controller picks subnets by tag: `kubernetes.io/role/elb = 1` for internet-facing load balancers (public subnets), `kubernetes.io/role/internal-elb = 1` for internal ones (private).

### Check

```bash
cd modules/network && terraform init -input=false -backend=false && terraform test && cd ../..
cd envs/dev && terraform init -input=false -backend=false && terraform validate && cd ../..
```

---

## Stage 10 — `modules/ecr`

**Goal:** ECR repositories in their own module, so the EKS root can have them without the EC2 Auto Scaling machinery of `modules/service`.

```
BEFORE                                        AFTER
module.service["order"]                       module.ecr
  ├── aws_ecr_repository.this                   ├── aws_ecr_repository.this["order"], ["payment"]
  ├── aws_ecr_lifecycle_policy.this             └── aws_ecr_lifecycle_policy.this["order"], ["payment"]
  └── launch template uses the repo above     module.service["order"]  ← receives repository_url
```

### `modules/ecr/`

| File | Content |
|---|---|
| `versions.tf` | `required_version >= 1.9.0`; aws `>= 5.50` |
| `variables.tf` | `name_prefix` (string); `services` (`set(string)`, for example `["order", "payment"]`); `keep_tagged_images` (number, default 10) |
| `main.tf` | the two ECR resources cut from `modules/service`, with the changes below |
| `outputs.tf` | `repository_urls` = `{ for k, r in aws_ecr_repository.this : k => r.repository_url }` |
| `tests/ecr.tftest.hcl` | below |

| In the pasted code | Becomes | Why |
|---|---|---|
| (nothing) | `for_each = var.services` on both resources | one repo per service |
| `name = "${local.full_name}-service"` | `name = "${var.name_prefix}-${each.key}-service"` | `local.full_name` only exists in `modules/service`; the result must stay **the same name**, or AWS creates a new, empty repository |
| `repository = aws_ecr_repository.this.name` | `aws_ecr_repository.this[each.key].name` | `this` is now a map |
| `var.ecr_keep_tagged_images` | `var.keep_tagged_images` | |

Tests (`mock_provider "aws" {}`, `name_prefix = "jaka-test"`, `services = ["order", "payment"]`):

| Run | Asserts |
|---|---|
| `one_repo_per_service` (plan) | 2 repositories; `this["order"].name == "jaka-test-order-service"`; 2 lifecycle policies |
| `keep_tagged_images_in_policy` (plan, value 5) | `jsondecode(...policy).rules[1].selection.countNumber == 5` |
| `output_keyed_by_service` (**apply**: mocks give computed values only on apply) | `toset(keys(output.repository_urls)) == toset(["order", "payment"])` |

### `modules/service/`

| File | Change |
|---|---|
| `main.tf` | delete the two ECR resources (keep `local.ecr_registry`: user data uses it for `docker login`) |
| `variables.tf` | delete `ecr_keep_tagged_images`; add `repository_url` (string, "ECR repository URL from modules/ecr") |
| `compute.tf` | in the `templatefile` map, `image_repo = var.repository_url` |
| `outputs.tf` | delete `ecr_repository_url` |
| `tests/` | drop any assertion on the ECR resources; add `repository_url` to the test variables |

### `stack/`

| File | Change |
|---|---|
| `main.tf` | new `module "ecr"` (`source = "../modules/ecr"`, `name_prefix = local.name_prefix`, `services = toset(keys(var.services))`); in `module "service"` add `repository_url = module.ecr.repository_urls[each.key]` |
| `outputs.tf` | `ecr_repository_urls` = `module.ecr.repository_urls` |
| `moved.tf` (new; **missing in the reference**) | four `moved` blocks: `module.service["order"].aws_ecr_repository.this` → `module.ecr.aws_ecr_repository.this["order"]`; the same for `aws_ecr_lifecycle_policy`; the same two for `payment` |

Without `moved.tf`, Terraform sees two unrelated changes against an applied `envs/dev`: destroy the repositories at the old address, create new ones at the new address with the same names. The apply then fails: either the create collides with the name that still exists, or the destroy refuses a repository that still holds images (`RepositoryNotEmptyException`). With an empty repository it "succeeds" by deleting and recreating it. `moved` tells Terraform it is the same object at a new address, so the plan shows no change at all. `moved` cannot use `for_each`: one block per service per resource. A service added later needs none.

### Check

```bash
cd modules/ecr && terraform init -input=false -backend=false && terraform test && cd ../..
for d in modules/service stack envs/dev; do (cd $d && terraform init -input=false -backend=false >/dev/null && terraform validate); done
```

With AWS and an applied `envs/dev`, `terraform plan` must print `has moved to` and no `destroy` for ECR.

| Error | Cause |
|---|---|
| `Reference to undeclared resource "aws_ecr_repository" "this"` in `modules/service` | a leftover use in `compute.tf` or `outputs.tf` |
| `Unsupported argument "ecr_keep_tagged_images"` in `stack` | `stack/main.tf` still passes the deleted variable |
| `Reference to undeclared local value "full_name"` in `modules/ecr` | the `name` was not changed |

---

## Stage 11 — `modules/eks`

**Goal:** a thin wrapper around the community EKS module: a cluster, one managed node group, the add-ons this build needs, and the IAM role for the EBS CSI driver.

### Files

| File | Content |
|---|---|
| `versions.tf` | `required_version >= 1.9.0`; aws `>= 6.0` (tflint's preset requires this file even in a wrapper) |
| `variables.tf` | below |
| `main.tf` | one `module "eks"`, one data source, one role, one attachment |
| `outputs.tf` | `cluster_name`, `cluster_endpoint`, `node_security_group_id` from `module.eks` |
| `tests/eks.tftest.hcl` | below |

| Variable | Type | Default | Validation |
|---|---|---|---|
| `name_prefix` | string | — | — |
| `kubernetes_version` | string | — | regex `^1\.[0-9]+$` |
| `vpc_id` | string | — | — |
| `private_subnet_ids` | list(string) | — | length ≥ 2 |
| `node_instance_type` | string | `t3.medium` | — |
| `node_min` | number | — | ≥ 2 (survive losing one AZ) |
| `node_max` | number | — | ≥ `var.node_min` |

### `module "eks"` inputs

Source `terraform-aws-modules/eks/aws`, version `~> 21.0`. Input names changed between v20 and v21; read the registry page of the version you pin.

| Input | Value | Why |
|---|---|---|
| `name` | `var.name_prefix` | |
| `kubernetes_version` | the variable | pick one in standard support (`aws eks describe-cluster-versions`) |
| `vpc_id`, `subnet_ids` | the variables (private subnets) | nodes live in private subnets, like the THANG-1 EC2 instances |
| `enable_irsa` | `false` | Pod Identity replaces IRSA; no OIDC provider needed |
| `endpoint_public_access` | `true` | `kubectl` from your laptop |
| `enable_cluster_creator_admin_permissions` | `true` | the identity that runs `apply` becomes cluster admin |
| `addons` | `coredns = {}`, `kube-proxy = {}`, `vpc-cni = { before_compute = true }`, `eks-pod-identity-agent = { before_compute = true }`, `aws-ebs-csi-driver = { pod_identity_association = [{ role_arn = aws_iam_role.ebs_csi.arn, service_account = "ebs-csi-controller-sa" }] }` | `before_compute`: the network and identity agents must exist before nodes join |
| `eks_managed_node_groups` | `default = { instance_types = [var.node_instance_type], min_size = var.node_min, max_size = var.node_max, desired_size = var.node_min }` | a managed node group is an ASG that EKS manages |

### EBS CSI role (the three pieces of a Pod's IAM role)

| # | Block | Value |
|---|---|---|
| 1 | `data "aws_iam_policy_document" "ebs_csi_trust"` | one statement: actions `sts:AssumeRole`, `sts:TagSession`; principal `Service` `pods.eks.amazonaws.com` — **who** may take the role |
| 2 | `aws_iam_role.ebs_csi` | name `<name_prefix>-ebs-csi`, `assume_role_policy` = the document's `.json` |
| 3 | `aws_iam_role_policy_attachment.ebs_csi` | the AWS managed `arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy` — **what** it may do |

The third piece, **which Pod** gets it, is the `pod_identity_association` inside the add-on block. Without this role, the Kafka PVC stays `Pending` forever on EKS.

### Tests

The community module pulls in more providers. Run `terraform init` then `terraform providers`, and add one `mock_provider` per provider listed: `aws`, `tls`, `time`, `cloudinit`, `null`. The `aws` mock needs four `mock_data` blocks, or the module fails on random strings where it expects ARNs and JSON:

| `mock_data` | `defaults` |
|---|---|
| `aws_caller_identity` | `account_id = "123456789012"`, `arn = "arn:aws:iam::123456789012:user/test"`, `user_id = "AIDATEST"` |
| `aws_iam_session_context` | `issuer_arn = "arn:aws:iam::123456789012:user/test"` |
| `aws_iam_policy_document` | `json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"` |
| `aws_partition` | `partition = "aws"`, `dns_suffix = "amazonaws.com"` |

Variables: `name_prefix = "jaka-test-eks"`, `kubernetes_version = "1.35"`, `vpc_id = "vpc-123"`, two subnet IDs, `t3.medium`, min 2, max 4.

| Run | Asserts |
|---|---|
| `block_node_min_less_than_2` | `node_min = 1` fails on `var.node_min` |
| `block_1_subnet` | one subnet fails on `var.private_subnet_ids` |
| `valid` | plans |

### Check

```bash
cd modules/eks && terraform init -input=false -backend=false && terraform validate && terraform test
```

### What the community module creates for you

The cluster IAM role, the cluster and node security groups, the node IAM role (with the ECR read-only, CNI and worker-node policies, which is why nodes can pull from ECR without anything from you), the launch template and ASG behind the node group, the five add-ons, and an access entry for your identity.

---

## Stage 12 — `envs/dev-eks`

**Goal:** a root with its own VPC, the EKS cluster, and the THANG-1 data layer for four services.

**Why a separate root and not `stack/`:** `stack/` builds the EC2 path (service ASGs, ALB, CloudWatch alarms), none of which EKS uses. A separate root also gets its own state key, so destroying EKS can never touch `envs/dev`.

### Files

| File | Content |
|---|---|
| `versions.tf` | `required_version >= 1.10.0` (S3-native locking); aws `~> 6.0`; random `~> 3.6`; empty `backend "s3" {}` |
| `providers.tf` | `provider "aws"`: region from the variable; `default_tags` `Project = local.project`, `Environment = local.environment`, `ManagedBy = "Terraform"` |
| `variables.tf` | `region` (default `ap-southeast-1`); `kubernetes_version` (default `"1.35"`) |
| `terraform.tfvars` | both values |
| `backend.hcl` | `bucket = "<project>-tfstate-<account_id>"` (from THANG-1 `bootstrap`), `key = "dev-eks/terraform.tfstate"`, `region`, `encrypt = true`, `use_lockfile = true` |
| `main.tf` | locals and seven module calls, below |
| `data-access.tf` | two security group rules, below |
| `outputs.tf` | below |

### `main.tf` locals

| Local | Value |
|---|---|
| `project` | your THANG-1 project name |
| `environment` | `dev-eks` |
| `name_prefix` | `"${local.project}-${local.environment}"` |
| `apps` | `{ order = { db_name = "orderdb" }, payment = { db_name = "paymentdb" }, inventory = { db_name = "inventorydb" }, notification = { db_name = "notificationdb" } }` |
| `services` | `toset([for k in keys(local.apps) : "${k}-service"])`: the ServiceAccount names (= Helm release names) |

The DB names match kind's, so the apps and the role Job see the same names everywhere.

### `main.tf` modules

| Module | Inputs worth noting |
|---|---|
| `network` | `vpc_cidr = "10.0.0.0/16"`; AZs `<region>a`, `<region>b`; public `10.0.1.0/24`, `10.0.2.0/24`; private **`10.0.32.0/20`, `10.0.48.0/20`**; `single_nat_gateway = true`; `public_subnet_tags = { "kubernetes.io/role/elb" = "1" }`; `private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }` |
| `eks` | `name_prefix`; `kubernetes_version` from the variable; VPC and private subnets from `network`; `t3.medium`; min 2, max 4 |
| `security` | `create_ssm_caller_user = false` (no EC2 to open sessions on) |
| `storage` | `tiered_prefixes = [for k in keys(local.apps) : "${k}s"]`; `force_destroy = true` |
| `ecr` | `services = toset(keys(local.apps))` |
| `redis` | private subnets, `redis_sg_id`, KMS ARN; `num_cache_clusters = 1`; `secret_recovery_window_in_days = 0` |
| `postgres` | `for_each = local.apps`; `service = each.key`; `db_name = each.value.db_name`; `admin_username = "${each.key}_admin"`; `app_username = "${each.key}_user"`; private subnets, `rds_sg_id`, both KMS values; dev overrides `multi_az = false`, `skip_final_snapshot = true`, `deletion_protection = false`, `secret_recovery_window_in_days = 0` |

Why `/20` private subnets: the VPC CNI gives every Pod a real IP from the subnet. A `/24` (251 usable addresses) fills up quickly with Pods; a `/20` has 4091.

### `data-access.tf`

| Resource | `security_group_id` | `referenced_security_group_id` | Port |
|---|---|---|---|
| `aws_vpc_security_group_ingress_rule.rds_from_eks_nodes` | `module.network.rds_sg_id` | `module.eks.node_security_group_id` | tcp 5432 |
| `aws_vpc_security_group_ingress_rule.redis_from_eks_nodes` | `module.network.redis_sg_id` | same | tcp 6379 |

The THANG-1 `rds` and `redis` groups only allow the `ec2` group. Pods leave the node with the **node's** security group, so the node group must be allowed in.

### `outputs.tf`

| Output | Value | Used by `eks-up.sh` for |
|---|---|---|
| `region` | `var.region` | everything |
| `name_prefix` | `local.name_prefix` (**not in the reference**; see stage 14) | ExternalSecret keys |
| `vpc_id` | `module.network.vpc_id` | LB Controller |
| `cluster_name`, `cluster_endpoint` | from `module.eks` | kubeconfig |
| `kubeconfig_command` | the `aws eks update-kubeconfig` command as a string | you |
| `ecr_repository_urls` | `module.ecr.repository_urls` | push, `image.repository` |
| `db_endpoints` | `{ for k, db in module.postgres : k => db.address }` | JDBC URL, role Job |
| `db_admin_secret_arns` | the same `for` with `db.admin_secret_arn` | admin ExternalSecret |
| `db_app_secret_arns` | the same with `db.app_secret_arn` | (reference) |
| `redis_primary_endpoint` | `module.redis.primary_endpoint` | `SPRING_DATA_REDIS_HOST` |
| `redis_auth_secret_arn` | `module.redis.auth_secret_arn` | (reference) |

### Check

```bash
cd envs/dev-eks && terraform init -input=false -backend=false && terraform validate
```

---

## Stage 13 — IAM for Pods

**Goal:** each app, the LB Controller and External Secrets get their own IAM role, bound to their ServiceAccount.

**Why:** in THANG-1 permissions sat on the EC2 instance profile, so every process on the machine had all of them. On EKS one node runs Pods of several services; using the node's role would let order-service read payment's secrets. **Pod Identity** gives each ServiceAccount its own role.

```
Pod order-service (serviceAccountName: order-service, namespace dev)
  → the AWS SDK asks the eks-pod-identity-agent on its node for credentials
  → EKS looks up the association (cluster, dev, order-service) → role <prefix>-order-service
  → STS AssumeRole (allowed by the trust policy for pods.eks.amazonaws.com) → temporary credentials
```

A role for a Pod is always three pieces: **trust** (who may take it) + **permissions** (what it may do) + **association** (which Pod gets it).

### `pod-identity.tf`

| # | Block | Spec |
|---|---|---|
| 1 | `data "aws_caller_identity" "current"` | account ID for ARNs |
| 2 | `data "aws_iam_policy_document" "pod_trust"` | actions `sts:AssumeRole`, `sts:TagSession` (EKS tags the session with cluster, namespace, SA); principal `Service` `pods.eks.amazonaws.com`. Reused by every Pod role |
| 3 | `data "aws_iam_policy_document" "pod_access"` | `for_each = local.services`; actions `ssm:GetParameter`, `ssm:GetParametersByPath`; resource `arn:aws:ssm:<region>:<account>:parameter/<project>/<environment>/<sa>/*` (reference: `parameter/jaka-mod/dev-eks/${each.key}/*`) |
| 4 | `aws_iam_role.pod` | `for_each`; name `<name_prefix>-<sa>`; `assume_role_policy = …pod_trust.json` |
| 5 | `aws_iam_role_policy.pod` | `for_each`; `role` = the role's **name**; `policy` = `pod_access[each.key].json` |
| 6 | `aws_eks_pod_identity_association.pod` | `for_each`; `cluster_name = module.eks.cluster_name`; `namespace = "dev"`; `service_account = each.key`; `role_arn` = the role's **ARN** |

The permission is a placeholder: the apps get their config from env vars and Secrets and call no AWS API yet. What matters is that each service already has its own role. The day order-service publishes metrics to CloudWatch, `cloudwatch:PutMetricData` goes into order's policy only.

The chart creates the ServiceAccount named after the release (`order-service`), and `eks-up.sh` installs release `<svc>-service`. Renaming the release takes the role away from the Pod.

### `addons-iam.tf`

| # | Block | Spec |
|---|---|---|
| — | `locals.eso_secret_arns` | `concat` of: the 4 app secret ARNs (`values({ for k, db in module.postgres : k => db.app_secret_arn })`), the 4 admin secret ARNs (same with `admin_secret_arn`), `[module.redis.auth_secret_arn]` = 9 |
| 1 | `aws_iam_policy.lb_controller` | name `<name_prefix>-lb-controller`; `policy = file("${path.module}/policies/aws-load-balancer-controller.json")` |
| 2 | `aws_iam_role.lb_controller` | trust = `pod_trust` |
| 3 | `aws_iam_role_policy_attachment.lb_controller` | 1 → 2 |
| 4 | `aws_eks_pod_identity_association.lb_controller` | namespace `kube-system`, SA `aws-load-balancer-controller` |
| — | `data "aws_iam_policy_document" "eso"` | statement 1: `secretsmanager:GetSecretValue`, `secretsmanager:DescribeSecret` on `local.eso_secret_arns`; statement 2: `kms:Decrypt` on `module.security.kms_key_arn` (all 9 secrets are encrypted with it) |
| 5 | `aws_iam_role.eso` | name `<name_prefix>-external-secrets`; trust = `pod_trust` |
| 6 | `aws_iam_role_policy.eso` | inline: the document above |
| 7 | `aws_eks_pod_identity_association.eso` | namespace `external-secrets`, SA `external-secrets` |

The LB Controller policy is a **managed** policy (its own object, attached) because it is long and published by AWS. ESO's is **inline** (lives inside the role, deleted with it) because it is short and only makes sense for this one role.

**`policies/aws-load-balancer-controller.json`:** download the official `docs/install/iam_policy.json` from the `kubernetes-sigs/aws-load-balancer-controller` repository at the tag of the controller version your chart installs (`helm show chart eks/aws-load-balancer-controller --version 3.6.2` prints `appVersion`). Do not edit it.

**Secret ARN outputs.** In THANG-1, `modules/postgres` and `modules/redis` output the ARN read from the secret **version** so consumers wait for a value. The reference switched both to the secret itself. The ARN is the same string either way; keep the THANG-1 version-based outputs to keep the ordering.

### Check

```bash
cd envs/dev-eks && terraform validate
helm template order-service ../../charts/spring-service -f ../../charts/spring-service/values/order-dev.yml | grep -A2 'kind: ServiceAccount'
```

The ServiceAccount is named `order-service`, the same string as the association's `service_account`.

---

## Stage 14 — `k8s/eks/`

**Goal:** the Kubernetes objects that exist only on EKS. Everything else (`gateway.yml`, `k8s/kafka/`, `mailpit.yml`, the chart) is reused as is.

### `storageclass-gp3.yml`

| Field | Value | Why |
|---|---|---|
| `metadata.name` | `gp3` | |
| annotation `storageclass.kubernetes.io/is-default-class` | `"true"` | the Kafka PVC names no class, so it takes the default |
| `provisioner` | `ebs.csi.aws.com` | the EBS CSI add-on |
| `parameters` | `type: gp3`, `encrypted: "true"` | |
| `volumeBindingMode` | `WaitForFirstConsumer` | an EBS volume lives in one AZ; create it only after the Pod is scheduled, in that Pod's AZ |
| `allowVolumeExpansion` / `reclaimPolicy` | `true` / `Delete` | deleting the PVC deletes the EBS volume (matters at teardown) |

### `cluster-secret-store.yml`

`apiVersion: external-secrets.io/v1`, `kind: ClusterSecretStore`, name `aws-secrets-manager`; `spec.provider.aws.service: SecretsManager`, `region: <your region>`. No credentials in it: ESO uses its own Pod's identity (stage 13). Cluster-scoped, so one store serves every namespace.

### `external-secrets.yml.tpl`

An `envsubst` template rendered once per service. Rules for these templates: start with `---` on line 1 (the renders are concatenated); write `${VAR}` in block style, not inside `{ }` flow maps; always list the variables (`envsubst '${SVC} ${X}'`) so any other `$` stays untouched.

Two `ExternalSecret` objects (`apiVersion: external-secrets.io/v1`, namespace `dev`, `refreshInterval: 1h`, `secretStoreRef: { kind: ClusterSecretStore, name: aws-secrets-manager }`):

| Object | `target.name` | `data` entries |
|---|---|---|
| `${SVC}-service-secret` | `${SVC}-service-secret` (= `secretName` in the values) | `SPRING_DATASOURCE_PASSWORD` ← key `${NAME_PREFIX}/${SVC}-db/app-credentials`, property `password` (the secret is JSON); `SPRING_DATA_REDIS_PASSWORD` ← key `${NAME_PREFIX}/redis/auth-token`, no property (a plain string) |
| `${SVC}-db-admin` | `${SVC}-db-admin` | `password` ← key `${ADMIN_SECRET_ARN}`, property `password` |

The result is exactly the two Secrets per service that kind had, with the same names and keys, so the chart and the Job do not change. The admin secret is referenced by ARN because RDS names it itself (`rds!db-…`). The reference hardcodes `jaka-mod-dev-eks` where this table says `${NAME_PREFIX}`; with your own prefix, use the variable.

### `create-app-role.yml.tpl`

The THANG-2 stage 4 Job as a template, with `${SVC}` in every name, and these differences:

| Field | Value | Why |
|---|---|---|
| env `PGHOST` | `${DB_HOST}` | the RDS address from Terraform, instead of `-h <svc>-db` |
| env `PGSSLMODE` | `require` | `modules/postgres` sets `rds.force_ssl = 1` |
| `psql` | `-U ${SVC}_admin -d ${SVC}db` | |
| SQL | the same script, `${SVC}_user` as the role | |

### `envoyproxy-nlb.yml`

`apiVersion: gateway.envoyproxy.io/v1alpha1`, `kind: EnvoyProxy`, name `nlb`, namespace `envoy-gateway-system`; `spec.provider.type: Kubernetes`; `spec.provider.kubernetes.envoyService.annotations`:

| Annotation | Value | Means |
|---|---|---|
| `service.beta.kubernetes.io/aws-load-balancer-type` | `external` | the LB Controller handles this Service (not the old in-tree code) |
| `service.beta.kubernetes.io/aws-load-balancer-scheme` | `internet-facing` | public NLB in the subnets tagged `role/elb` |
| `service.beta.kubernetes.io/aws-load-balancer-nlb-target-type` | `ip` | targets are Pod IPs directly (possible thanks to the VPC CNI) |

### Check

```bash
SVC=order NAME_PREFIX=x ADMIN_SECRET_ARN=arn:x envsubst '${SVC} ${NAME_PREFIX} ${ADMIN_SECRET_ARN}' < k8s/eks/external-secrets.yml.tpl
SVC=order DB_HOST=db.example envsubst '${SVC} ${DB_HOST}' < k8s/eks/create-app-role.yml.tpl | kubectl apply --dry-run=client -f -
kubectl apply --dry-run=client -f k8s/eks/storageclass-gp3.yml
```

`--dry-run=client` checks the YAML shape. It still needs a reachable cluster to look up the kinds, so keep kind running; that is also why the ExternalSecret render is only printed, not piped into `kubectl`: kind has no ESO CRDs, and `kubectl` would answer `no matches for kind "ExternalSecret"`. The ESO and EnvoyProxy kinds are validated for real only on EKS.

---

## Stage 15 — Values `<svc>-eks.yml`

**Goal:** per-service values for EKS, holding only what is known before `apply`.

| Key | order | payment | inventory | notification |
|---|---|---|---|---|
| `port` / `contextPath` | 8081 `/api/orders` | 8082 `/api/payments` | 8083 `/api/inventory` | 8084 `/api/notifications` |
| `env.SPRING_DATASOURCE_USERNAME` | `order_user` | `payment_user` | `inventory_user` | `notification_user` |
| `env.SPRING_DATA_REDIS_SSL_ENABLED` | `"true"` | `"true"` | — | — |
| `env.SPRING_DATA_REDIS_PORT` | — | `"6379"` | — | — |
| `env.KAFKA_BOOTSTRAP` | `jaka-kafka-bootstrap.kafka:9092` | same | same | same |
| `env.MAIL_HOST` / `MAIL_PORT` | — | — | — | `mailpit` / `"1025"` |
| `secretName` | `<svc>-service-secret` | | | |
| `replicaCount` | `1` | | | |
| `autoscaling` | `enabled: true`, `minReplicas: 1`, `maxReplicas: 3`, `targetCPU: 50` | | | |

**Not** in these files, because they exist only after `apply`: `image.repository` (ECR URL), `SPRING_DATASOURCE_URL` (RDS host), `SPRING_DATA_REDIS_HOST` (ElastiCache host). `eks-up.sh` passes them with `--set`. `SPRING_DATA_REDIS_PASSWORD` comes from the Secret ESO creates. ElastiCache has in-transit encryption on, so Redis clients must use TLS (`SSL_ENABLED`). The reference also sets the Redis SSL flag for inventory and notification, which have no Redis; harmless, but leave it out.

### Check

```bash
for svc in order payment inventory notification; do
  helm template "$svc-service" charts/spring-service -f "charts/spring-service/values/$svc-eks.yml" \
    --set image.repository=x --set env.SPRING_DATASOURCE_URL=x | grep -c 'kind: HorizontalPodAutoscaler'
done
```

Four lines of `1`.

---

## Stage 16 — `scripts/eks-up.sh`

**Goal:** `cluster-up.sh` for EKS: everything after `terraform apply`, from one command, safe to rerun.

Same shape as `cluster-up.sh`, minus everything AWS now provides (databases, Redis, Secrets from `.env`, `kind load`).

**Header**

| Item | Spec |
|---|---|
| safety, folder | as `cluster-up.sh` |
| `services` | `(order payment inventory notification)` |
| `tag` | `${TAG:-eks-$(date +%s)}` |
| `tf_out name` / `tf_raw name` | `terraform -chdir=envs/dev-eks output -json "$1"` / `-raw` |
| read once | `region`, `name_prefix`, `cluster`, `vpc_id` (raw); `ecr_urls`, `db_hosts`, `admin_arns` (json); `redis_host` (raw) |
| `pick json key` | `jq -r --arg s "$2" '.[$s]' <<<"$1"`: one value from a map output |

**Steps**

| # | Step | Commands and why |
|---|---|---|
| 1 | kubeconfig | `aws eks update-kubeconfig --region … --name …`: kubectl and helm now talk to EKS |
| 2 | images | registry = order's ECR URL without the path (`${url%%/*}`); `aws ecr get-login-password \| docker login --username AWS --password-stdin`; per service `docker build --platform linux/amd64` (nodes are x86) and `docker push` |
| 3 | namespaces | `dev`, `kafka` with the dry-run pattern; **no** `kubectl create secret` |
| 4 | add-ons | metrics-server URL (no patch: EKS kubelets have proper certs); `helm repo add eks https://aws.github.io/eks-charts` and `external-secrets https://charts.external-secrets.io`; LB Controller **first** (`eks/aws-load-balancer-controller` `--version 3.6.2`, `-n kube-system`, `--set clusterName`, `vpcId`, `region`, `--wait`): its webhook must be up before any `LoadBalancer` Service exists; ESO (`external-secrets/external-secrets` `--version 2.14.0`, `-n external-secrets --create-namespace --wait --timeout 10m`); Envoy Gateway as on kind; Strimzi as on kind |
| 5 | cluster-wide objects | apply `storageclass-gp3.yml`, `cluster-secret-store.yml` |
| 6 | Secrets | for each service render `external-secrets.yml.tpl` with `SVC`, `NAME_PREFIX`, `ADMIN_SECRET_ARN=$(pick "$admin_arns" "$SVC")`; pipe the loop's output into one `kubectl apply -f -`; `kubectl wait externalsecret --all -n dev --for=condition=Ready --timeout=5m` |
| 7 | role Jobs | first `kubectl delete job "$SVC-db-create-app-role" --ignore-not-found` (the Pod template is immutable; the SQL is idempotent); render `create-app-role.yml.tpl` with `SVC` and `DB_HOST=$(pick "$db_hosts" "$SVC")`; apply; wait each for `Complete` |
| 8 | Kafka, Mailpit | the Kafka file with `-n kafka`, wait Ready (its PVC now becomes a gp3 EBS volume), topics; `k8s/notification-service/mailpit.yml` only (the other files in that folder are kind's DB and Job) |
| 9 | apps | per service `helm upgrade --install "$svc-service" charts/spring-service -n dev -f values/$svc-eks.yml --set image.repository=<ecr url> --set image.tag=$tag --set env.SPRING_DATASOURCE_URL="jdbc:postgresql://<db host>:5432/${svc}db?sslmode=require"`; for order and payment also `--set env.SPRING_DATA_REDIS_HOST="$redis_host"` (build an `extra` array, empty for the others) |
| 10 | Gateway → NLB | apply `envoyproxy-nlb.yml`; apply `gateway.yml` through `sed` that inserts, after the `controllerName` line, `  parametersRef: { group: gateway.envoyproxy.io, kind: EnvoyProxy, name: nlb, namespace: envoy-gateway-system }`. Same file as kind, no edit; and in the same apply, so Envoy's Service is born with the NLB annotations (patching afterwards would first create a different load balancer). Wait for `Accepted`, all Deployments `Available`, then `Programmed` (10 min: that is the NLB getting a DNS name) |
| end | output | read `.status.addresses[0].value` of `gateway/web`; print `http://<nlb>` and the line `BASE_URL=http://<nlb> ./scripts/saga-demo.sh --timeout` |

### Check

```bash
bash -n scripts/eks-up.sh
```

Every file the script reads has a stage: Terraform outputs (12), `k8s/eks/*` (14), values (15), `gateway.yml` and `k8s/kafka/*` (THANG-2), `mailpit.yml` (7).

---

## Stage 17 — CI

**File:** `.github/workflows/terraform.yml` from THANG-1.

| Job | Change |
|---|---|
| `test` matrix | add `modules/eks` and `modules/ecr` |
| `validate-envs` matrix | `[dev, dev-eks]` (and `prod` if you built it) |

`validate-envs` runs `init -backend=false`, so no AWS credentials are needed. The `test` job passes for a module with no `tests/` folder while testing nothing; see Appendix F.

---

## Stage 18 — Runbook on AWS

**Goal:** the four Saga branches pass through a public NLB on EKS, then everything is gone.

Cost while running: control plane 0.10 USD/h + 2 × `t3.medium` + NAT + 4 RDS + 1 ElastiCache + NLB ≈ 0.4–0.5 USD/h.

### Before

- `aws sts get-caller-identity` works.
- The THANG-1 state bucket exists; `backend.hcl` has your account ID.
- `aws`, `kubectl`, `helm`, `jq`, `envsubst` installed.

### 1. Infrastructure

```bash
cd envs/dev-eks
terraform init -backend-config backend.hcl
terraform apply -var-file terraform.tfvars
cd ../..
```

About 15–20 minutes (EKS about 10, RDS 5–10).

### 2. Platform and apps

```bash
./scripts/eks-up.sh
```

### 3. Check each layer

```bash
kubectl get nodes
kubectl get externalsecret -n dev
kubectl get jobs -n dev
kubectl get pvc -n kafka
kubectl get pods,hpa -n dev
kubectl get gateway web -n dev
```

| Command | Expected |
|---|---|
| nodes | 2 `Ready` |
| externalsecret | 8 rows, `SecretSynced`, `Ready True` |
| jobs | 4 `Complete 1/1` |
| pvc | `Bound`, class `gp3` |
| pods, hpa | 4 apps `Running`; HPA `TARGETS` like `cpu: 5%/50%` |
| gateway | `ADDRESS` = the NLB DNS name, `PROGRAMMED True` |

### 4. Prove it

```bash
BASE_URL=http://<nlb-dns> ./scripts/saga-demo.sh --timeout
```

### 5. Tear down, in this order

The NLB and the EBS volume were created by **controllers inside the cluster**, so Terraform does not know them. Delete them while the controllers still run, or they stay, cost money and block the VPC deletion.

```bash
kubectl delete gateway web -n dev
kubectl wait --for=delete svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web --timeout=5m
kubectl delete ns kafka
terraform -chdir=envs/dev-eks destroy -var-file terraform.tfvars
```

| Error during `destroy` | Fix |
|---|---|
| `RepositoryNotEmptyException` | the ECR repos still hold images: `aws ecr delete-repository --repository-name <prefix>-<svc>-service --force` for each, then `destroy` again. Lasting fix in Appendix F |
| `DependencyViolation` on a subnet or the VPC | an NLB (or its network interfaces) is left: find it with `aws elbv2 describe-load-balancers` (name starts with `k8s-`), delete it, `destroy` again |

---

## Appendix A — Definition of done

| Check | Passes when |
|---|---|
| Builds | `./gradlew build -x test` passes in all four services |
| kind, one command | `./scripts/cluster-down.sh && ./scripts/cluster-up.sh` brings up 4 apps, 4 DBs, 2 Redis, Mailpit, Kafka with 6 topics |
| Saga | `./scripts/saga-demo.sh --timeout` prints `All checks passed` |
| Drills | duplicate, poison, unknown SKU, race and correlation-ID drills behave as in stage 8 |
| Read model | summaries are correct and survive the rebuild-from-events drill |
| Contract | `docs/event-catalog.md` matches the records in every `dto/` |
| Terraform tests | `terraform test` passes in `modules/network`, `modules/ecr`, `modules/eks` (and all THANG-1 suites) |
| Roots | `terraform validate` passes in `envs/dev` and `envs/dev-eks` |
| Charts | the stage 15 `helm template` loop prints four `1`s |
| Script | `bash -n` passes for every script |
| AWS (optional) | stage 18 passes and `destroy` leaves nothing behind |

## Appendix B — Event contract

### Envelope (every message on every topic)

| Field | Type | Note |
|---|---|---|
| `eventId` | String (UUID) | dedupe key for every consumer |
| `eventType` | String | the `switch` key |
| `version` | int | always `1` so far |
| `aggregateId` | String | the order ID; also the Kafka key |
| `occurredAt` | Instant (ISO-8601) | |
| `payload` | object | one of the records below |

Kafka headers: `correlationId`, `eventType`.

### Events

| Event | Topic | Producer | Consumers (group) | Meaning |
|---|---|---|---|---|
| `OrderCreated` | `order-events` | order | inventory, notification | new order saved as `PENDING` |
| `OrderConfirmed` | `order-events` | order | — | Saga succeeded |
| `OrderCancelled` | `order-events` | order | inventory | out of stock, declined, or timeout; inventory releases if it still holds stock |
| `RefundRequested` | `order-events` | order | payment | a payment arrived after the order was cancelled |
| `InventoryReserved` | `inventory-events` | inventory | payment, order-service, order-summary-projector | stock held |
| `InventoryRejected` | `inventory-events` | inventory | order-service, order-summary-projector, notification | not enough stock |
| `InventoryReleased` | `inventory-events` | inventory | order-summary-projector | held stock returned |
| `PaymentCompleted` | `payment-events` | payment | inventory, order-service, order-summary-projector, notification | charged |
| `PaymentFailed` | `payment-events` | payment | inventory, order-service, order-summary-projector, notification | declined |
| `PaymentRefunded` | `payment-events` | payment | — | refund done; closes the Saga |

### Consumer groups

| Group | Listens to | Handles | Ignores |
|---|---|---|---|
| `order-service` | inventory-events, payment-events | Reserved, Rejected, Completed, Failed | Released, Refunded |
| `order-summary-projector` | inventory-events, payment-events | Reserved, Rejected, Released, Completed, Failed | Refunded |
| `inventory-service` | order-events, payment-events | OrderCreated, OrderCancelled, PaymentCompleted, PaymentFailed | OrderConfirmed, RefundRequested, PaymentRefunded |
| `payment-service` | inventory-events, order-events | InventoryReserved, RefundRequested | all others |
| `notification-service` | all three | OrderCreated, InventoryRejected, PaymentCompleted, PaymentFailed | all others |

### Payloads

| Record | Fields |
|---|---|
| `OrderCreated` | `Long orderId, String orderNumber, String customerName, BigDecimal totalAmount, String currency, String productSku, int quantity` |
| `OrderConfirmed` | `Long orderId, String orderNumber, String customerName, BigDecimal totalAmount, String currency` |
| `OrderCancelled` | `Long orderId, String orderNumber, String customerName, String productSku, int quantity, String reason` |
| `RefundRequested` | `Long orderId, String paymentReference, BigDecimal amount, String currency, String customerName, String reason` |
| `InventoryReserved` | `Long orderId, String productSku, int quantity, String customerName, BigDecimal totalAmount, String currency` |
| `InventoryRejected` | `Long orderId, String productSku, int quantity, String customerName, BigDecimal totalAmount, String reason` |
| `InventoryReleased` | `Long orderId, String productSku, int quantity, String customerName, String reason` |
| `PaymentCompleted` | `Long orderId, String paymentReference, BigDecimal amount, String currency, String customerName` |
| `PaymentFailed` | `Long orderId, BigDecimal amount, String currency, String customerName, String reason` |
| `PaymentRefunded` | `Long orderId, String paymentReference, BigDecimal amount, String currency, String customerName, String reason` |

Reason values: `Insufficient stock`, `CARD_DECLINED`, `PAYMENT_TIMEOUT`.

## Appendix C — Tables per database

All created by `ddl-auto: update`.

| Database | Tables |
|---|---|
| `orderdb` | `orders`, `outbox`, `processed_events`, `order_summary`, `projected_events` |
| `inventorydb` | `products`, `reservations`, `outbox`, `processed_events` |
| `paymentdb` | `payments`, `outbox`, `processed_events` |
| `notificationdb` | `notifications` |

| Service | Port | Context path | Kafka group(s) | Produces to |
|---|---|---|---|---|
| order | 8081 | `/api/orders` | `order-service`, `order-summary-projector` | `order-events` |
| payment | 8082 | `/api/payments` | `payment-service` | `payment-events` |
| inventory | 8083 | `/api/inventory` | `inventory-service` | `inventory-events` |
| notification | 8084 | `/api/notifications` | `notification-service` | — |

## Appendix D — Concepts by stage

| Concept | Stage |
|---|---|
| Contract-first events, no shared library | 0 |
| Dual write, transactional outbox, `FOR UPDATE SKIP LOCKED`, keys and ordering | 1 |
| Correlation ID, MDC, servlet filter, record interceptor | 1 |
| Message converter vs serializer, `JsonNode` dispatch | 1, 3 |
| DLT, partition alignment, retryable vs not | 2, 3 |
| Idempotent consumer, at-least-once delivery | 3 |
| Optimistic locking, DB check constraints | 3 |
| Business-key idempotency | 4 |
| Saga, choreography, compensation, semantic lock, timeout | 5 |
| CQRS, projector, forward-only updates, backfill, replay | 6 |
| Fan-out, independent consumer groups, offset reset | 7 |
| Module provider constraints (minimum in modules, pin in roots) | 9 |
| Module extraction, `moved` blocks | 10 |
| Wrapping a community module, mocking its providers | 11 |
| VPC CNI and subnet sizing, security group references | 12 |
| Pod Identity: trust, permission, association; managed vs inline policies | 13 |
| StorageClass binding modes, External Secrets, NLB annotations, `envsubst` templates | 14 |
| Values only known after apply | 15, 16 |
| Controller-created cloud resources and teardown order | 18 |

## Appendix E — Gotchas

| Symptom | Cause |
|---|---|
| Consumer logs `The given id must not be null` or an NPE in `switch` | a message without an envelope (old THANG-2 format, or a typo in a test message) |
| Every `OrderConfirmed` ends up in `order-events-dlt` | a listener deserializes every payload as one record type instead of `JsonNode` + `switch`, or throws in `default` |
| Outbox rows stay `published_at NULL`, no `Published` log lines | the relay is stuck in `send().join()`: Kafka unreachable (wrong `KAFKA_BOOTSTRAP`, broker down). Each attempt waits up to 2 minutes before failing |
| Correlation ID missing in one service's logs | interceptor or filter outside the package of the `@SpringBootApplication` class, so not scanned |
| `GET /api/orders/` shows stale statuses | a status change without `@CacheEvict("orders", allEntries = true)` |
| Order stuck in `PENDING` | inventory's group is not reading `order-events` (wrong group or topic name), or the event went to a DLT |
| `ObjectOptimisticLockingFailureException` in logs | expected under concurrency; the error handler retries it |
| Projector floods the DLT after first deploy | no backfill before enabling it |
| `terraform init`: no version of aws matches | a module the EKS root calls still pins `~> 5.50` |
| `terraform test` in `modules/eks`: random string is not a valid ARN | a `mock_data` default is missing |
| PVC `Pending` on EKS | no default StorageClass, or the EBS CSI role/association is missing |
| Gateway `Programmed False` on EKS | LB Controller not running, its role missing, or the subnets lack the `role/elb` tag |
| ExternalSecret `SecretSyncedError` | ESO role cannot read that ARN (not in `eso_secret_arns`), `kms:Decrypt` missing, or a wrong key name (prefix) |
| Job fails `no pg_hba.conf entry … no encryption` | `PGSSLMODE=require` missing |
| App fails to connect to Redis on EKS | `SPRING_DATA_REDIS_SSL_ENABLED` not `true`, or the password key missing from the Secret |

## Appendix F — Improvements to make while rebuilding

Weaknesses in the reference. Cheaper to fix as you type.

| # | Change | Stage |
|---|---|---|
| 1 | Add `stack/moved.tf` for the ECR move (the reference has none: on an applied `envs/dev` the next apply would try to destroy and recreate the repositories, and fail or wipe them) | 10 |
| 2 | Keep the version-based secret ARN outputs in `modules/postgres` and `modules/redis` | 13 |
| 3 | Pass the name prefix (and region) into the ExternalSecret and ClusterSecretStore templates instead of hardcoding `jaka-mod-dev-eks` and `ap-southeast-1` | 14 |
| 4 | Give inventory and notification Secrets without `SPRING_DATA_REDIS_PASSWORD`, and drop their Redis SSL flag: they have no Redis | 14, 15 |
| 5 | Use the admin user in the readiness probe of `inventory-db` and `notification-db` (the reference uses `<svc>_user`, which does not exist until the Job runs; Postgres logs a failed login every 5 s) | 3, 7 |
| 6 | Use Spring's `@Transactional` in `OrderSummaryProjector` (the reference imports `jakarta.transaction.Transactional`) | 6 |
| 7 | Move `OrderSummaryResponse` from `entity/` to `dto/` | 6 |
| 8 | Delete published outbox rows and old `processed_events` rows on a schedule; both tables grow forever today | 1 |
| 9 | Add `force_delete` (default `false`) to `modules/ecr`, `true` in `envs/dev-eks`, so `destroy` works with images inside | 10 |
| 10 | Output each DB name from `envs/dev-eks` instead of building `${svc}db` in `eks-up.sh` | 12, 16 |
| 11 | Write `tftest` suites for the modules EKS reuses (`postgres`, `redis`, `security`, `storage`); the reference has tests only for `network`, `eks`, `ecr`, so CI passes while testing nothing for the others | 9–12 |
| 12 | Pin Mailpit (`axllent/mailpit:<version>`) in the manifest and in `cluster-up.sh` | 7 |
| 13 | Send notification emails after the commit (for example from an outbox of its own), so a failed commit cannot cause a second email on retry | 7 |
| 14 | Remove the placeholder SSM permission in `pod_access` until an app really reads SSM, or name the path after real parameters | 13 |

## Appendix G — Not covered

- **Month 4:** CI/CD from `git push` to the cluster (GitHub Actions, ArgoCD), metrics and dashboards (Prometheus, Grafana), logs (Loki), tracing.
- **MSK** instead of Strimzi on EKS, **orchestrated Sagas** (a central coordinator), **change data capture** for the outbox (Debezium), a **schema registry**.
- **HTTPS** on the NLB or Gateway, **network policies**, **cluster autoscaling** of the node group (Karpenter).
