# WEEK 13 — CI with GitHub Actions: real tests + image build + push to ECR

> Month 4, week 1 of 4. Series: **TUAN-13** → [TUAN-14](TUAN-14-CHI-TIET.md) → [TUAN-15](TUAN-15-CHI-TIET.md) → [TUAN-16](TUAN-16-CHI-TIET.md).

## TL;DR

By the end of this week, every `git push` to the `springboot_aws_compute_databases` repo will:

1. Start **only the CI workflow of the service that changed**: one workflow per service (`order-ci`, `payment-ci`, `inventory-ci`, `notification-ci`), all sharing one reusable workflow.
2. Run `./gradlew test` **for real**. The tests start their own Postgres + Kafka + Redis with **Testcontainers** (no more `-x test`).
3. Build the Docker image with the fixed Dockerfile (non-root, correct port, fixed jar name).
4. Push to **ECR** with an immutable tag `sha-<commit>`, logging in through **OIDC**. No access key is stored in GitHub.

Deploying to EKS is week 14's job. This week is CI only: **push → test → image sitting in ECR**.

---

## Why each task is needed (seen from your own repo)

Before writing any YAML, look at the 5 problems that already exist in the repo:

| # | Current problem in the repo | What happens if it goes into CI as-is | This week's task |
|---|---|---|---|
| 1 | The only test is `contextLoads`, and it **fails** if the machine has no Postgres/Redis/Kafka. The Dockerfile dodges it with `-x test` | CI goes "green" without ever running a single test | Testcontainers: tests start their own infrastructure in Docker |
| 2 | `payment-service/payment-service/gradlew` is mode `100644` in git (the other 3 services are `100755`) | GitHub's Linux runner runs `./gradlew` → `Permission denied`, CI fails at the first step | `git update-index --chmod=+x` |
| 3 | The Dockerfile copies the jar by the name `order-service-0.0.1-SNAPSHOT.jar` (version hardcoded), and `EXPOSE 8080` is wrong for order (8081) / payment (8082) | Changing the version breaks the build; the wrong EXPOSE misleads you while debugging | Fix the jar name to `app.jar`; fix EXPOSE; add a non-root user |
| 4 | ECR is created inside `envs/dev-eks`, an **ephemeral** environment (built for learning, then destroyed) | Destroying the cluster also destroys the image registry; CI has nowhere to push while the cluster is down | Move ECR to a new long-lived environment, `envs/shared` |
| 5 | GitHub Actions has no way to log in to AWS | The bad way: create an IAM user + access key and paste it into GitHub Secrets (the key lives forever; if it leaks, you're done) | **GitHub OIDC**: GitHub proves its identity to AWS; no key exists anywhere |

> **Trade-off, stated up front rather than after:** switching ECR to `IMMUTABLE` tags means CI **cannot overwrite** an existing tag. Re-running the workflow on the same commit would fail at the push step, so the workflow gets an extra "does the image already exist?" step so a re-run stays green. In exchange you get an absolute guarantee: tag `sha-abc123` today and in 6 months is **the same image**.

---

# STAGE A — Make `./gradlew test` run anywhere (Testcontainers)

## A1. What Testcontainers is, and why it fits your case

Right now `OrderServiceApplicationTests` only has `contextLoads()`. Spring tries to start the whole context, and the context needs Postgres (JPA), Redis (cache) and Kafka (producer). Any machine missing one of the three turns the test red. GitHub's runner obviously doesn't have them.

**Testcontainers** solves exactly this: while the tests run, the library itself does `docker run` for a real Postgres/Kafka/Redis (real containers, not mocks), waits until they're ready, points Spring at them, and cleans everything up when the tests finish. The only requirement is that the machine running the tests has Docker. Your laptop does, and GitHub's `ubuntu-latest` runner has it preinstalled.

Spring Boot 4.1 integrates it through the `@ServiceConnection` annotation: you declare the container as a bean and Boot derives `spring.datasource.url`, `spring.kafka.bootstrap-servers` and `spring.data.redis.host/port` by itself. **You don't hand-write a single config line.**

## A2. Add the dependency (each service's build.gradle)

**Check first:** all 4 `build.gradle` files **already contain** two lines, `spring-boot-testcontainers` and `testcontainers-kafka`, from the Kafka work in month 3 (commit `fbfc964` "init" for order/payment, commit `3939acf` for inventory/notification). They've been sitting there unused because no test declared a container yet. You **only need to add one line** for Postgres, right under the two existing ones:

```groovy
dependencies {
    // ... existing dependencies stay as they are ...

    testImplementation 'org.springframework.boot:spring-boot-testcontainers'   // already there
    testImplementation 'org.testcontainers:testcontainers-kafka'               // already there
    testImplementation 'org.testcontainers:testcontainers-postgresql'          // NEW
}
```

What each line does:

- `spring-boot-testcontainers`: the bridge on the Spring side. It provides `@ServiceConnection` and translates "a Postgres container" into "datasource config". Without it you'd have to copy the container's random port, username and password into Spring yourself with `@DynamicPropertySource`.
- `testcontainers-postgresql`: the `PostgreSQLContainer` class (in Testcontainers 2.x it lives in package `org.testcontainers.postgresql` and is **no longer generic** `<SELF>` like the 1.x version you'll see in many online examples).
- `testcontainers-kafka`: the `KafkaContainer` class, which can run the `apache/kafka-native` image (KRaft, no ZooKeeper).
- Redis has no dedicated module. Use `GenericContainer` (part of the core, pulled in by the two modules above) plus `@ServiceConnection(name = "redis")` to tell Boot "this is Redis".
- Versions are managed by the Spring Boot BOM (Boot 4.1 brings Testcontainers 2.0.x), so **don't write a version number** here.

Add the `testcontainers-postgresql` line to all 4 services. Leave the two existing lines alone and don't add them again: Gradle won't complain about duplicates, but the file gets harder to read.

## A3. The `TestcontainersConfiguration.java` file

Create a new file in the **test** source set (not main):

`order-service/order-service/src/test/java/com/jaka/order_service/TestcontainersConfiguration.java`

```java
package com.jaka.order_service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        var redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"));
        redis.withExposedPorts(6379);
        return redis;
    }
}
```

Explanation of each part:

- `@TestConfiguration(proxyBeanMethods = false)`: this config is **for tests only** and is not picked up by component scanning in the real app. `proxyBeanMethods = false` turns off the CGLIB proxy. The proxy is only needed when one `@Bean` method calls another `@Bean` method; these three don't, so turning it off makes startup slightly faster.
- `@ServiceConnection` (on postgres/kafka): Boot looks at the container's **type** to know what it is. `PostgreSQLContainer` → it sets `spring.datasource.*`; `KafkaContainer` → it sets `spring.kafka.bootstrap-servers`. The port is a random port mapped by Docker; you never need to know which one.
- `@ServiceConnection(name = "redis")`: Boot can't guess what a `GenericContainer` is, so you declare `name = "redis"` (the standard image name) and Boot sets `spring.data.redis.host/port`.
- `withExposedPorts(6379)`: tells Testcontainers to map port 6379 inside the container to a random port on the host. The Postgres/Kafka classes already declare their ports, so they don't need this line.
- Why `redis` is built in a local variable instead of one chained `return new GenericContainer<>(...).withExposedPorts(6379);`: with the chained form, the VS Code Java checker reports *"Resource leak: '<unassigned Closeable value>' is never closed"*. It can't tell that `withExposedPorts` returns the same object, so it thinks the first object was dropped. It's a false alarm (Spring closes the bean when the context shuts down, and the `testcontainers/ryuk` container cleans up even if the JVM is killed), but the variable form keeps the editor quiet.
- `postgres:16` matches the RDS major version you use on AWS. `apache/kafka-native:4.3.1` is the official Apache Kafka image; the native build starts in about 1 second.

**Per service:**

| Service | File's package | Beans to keep |
|---|---|---|
| order | `com.jaka.order_service` | postgres + kafka + redis |
| payment | `com.jaka.payment_service` | postgres + kafka + redis |
| inventory | `com.jaka.inventory_service` | postgres + kafka (no redis) |
| notification | `com.jaka.notification_service` | postgres + kafka (no redis) |

## A4. Fix the existing test class + write one meaningful test

Update `OrderServiceApplicationTests`:

```java
package com.jaka.order_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

You add exactly one line, `@Import(TestcontainersConfiguration.class)`, meaning "before running this test, start those 3 containers".

Then write a test that checks the logic you built in month 3 (the outbox pattern): creating an order must leave an `OrderCreated` outbox record waiting to be published.

`src/test/java/com/jaka/order_service/OrderCreateFlowTests.java`:

```java
package com.jaka.order_service;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.jaka.order_service.dto.CreateOrderRequest;
import com.jaka.order_service.repository.OutboxRepository;
import com.jaka.order_service.service.OrderService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderCreateFlowTests {

    @Autowired
    OrderService orderService;

    @Autowired
    OutboxRepository outboxRepository;

    @Test
    void createOrder_writesOutboxEvent() {
        var order = orderService.create(
                new CreateOrderRequest("alice", new BigDecimal("10.00"), "USD", "TEST-001", 1));

        assertThat(outboxRepository.findAll()).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("OrderCreated");
            assertThat(event.getAggregateId()).isEqualTo(order.getId().toString());
        });
    }
}
```

What makes this test valuable: it confirms the **core contract** of the month-3 saga ("creating the order and writing the outbox happen in the same transaction") without waiting for any Kafka consumer. The background `OutboxRelay` may already have published the event before the assert runs. That's fine, because we only check that the record exists, not that `publishedAt` is null.

> `CreateOrderRequest` is a record with 5 fields in this order: `(customerName, totalAmount, currency, productSku, quantity)`. `OrderService.create(...)` returns the saved `Order`.

### Three AssertJ / Java pitfalls to avoid

- **`assertThat(x)` on its own checks nothing.** It only wraps `x`. The check happens when you call something after it: `.isTrue()`, `.isEqualTo(...)`, `.hasSize(...)`, `.anySatisfy(...)`. A test that writes `assertThat(list.stream().anyMatch(...));` without `.isTrue()` is always green.
- **Don't compare `Long` values with `==`.** `==` compares object identity. It happens to work for `1L` (Java caches boxed values from -128 to 127) and silently fails for `900001L`. Use `equals()` or `assertThat(a).isEqualTo(b)`.
- **Create `BigDecimal` from a string**: `new BigDecimal("10.00")`, not `new BigDecimal(10.00)`. The `double` constructor copies binary floating-point error (`new BigDecimal(0.1)` is `0.1000000000000000055...`). To compare amounts use `isEqualByComparingTo`, because `BigDecimal.equals` treats `10.00` and `10.0` as different.

Prefer `assertThat(list).anySatisfy(e -> { ... })` over `assertThat(list.stream().anyMatch(...)).isTrue()`: when it fails, `anySatisfy` prints which element failed on which field, while `isTrue()` only says "expected true but was false". `anySatisfy` takes a `ThrowingConsumer<T>` (an AssertJ interface extending `java.util.function.Consumer<T>`): a function that receives one element, returns nothing, and signals "doesn't match" by throwing an `AssertionError`.

## A4b. Tests for the other 3 services (payment, inventory, notification)

Each service needs **two levels** of testing:

| Level | What it is | Required? |
|---|---|---|
| 1 | Put `@Import(TestcontainersConfiguration.class)` on the existing `*ApplicationTests` class (same as `OrderServiceApplicationTests`) | **Required**. Without it, `contextLoads` looks for a DB on `localhost` and CI goes red |
| 2 | One test class for the service's main business flow, like `OrderCreateFlowTests` | Recommended. This is what actually stops bugs before deployment |

### Common notes for all 3 (read before writing)

- **The test classes share one DB.** Spring caches the context between test classes with the same configuration, so every class that does `@Import(TestcontainersConfiguration.class)` runs against **the same** Postgres container. Data written by an earlier test is still there when a later one runs. Two consequences:
  - Use a **separate `orderId` per test** (for example `900001L`, `900002L`). Don't use `1L`..`8L`: `PaymentDataSeeder` already seeded payments for those order IDs, so `charge()` would hit the idempotency check and return early.
  - Don't assert absolute counts like `findAll().size() == 1`. Count by the specific orderId, or read the value before and compare the difference afterwards.
- **The seeders still run in tests** (`CommandLineRunner` runs when the context starts). You can rely on seed data, for example inventory's products `TEST-001` (available 100) and `SOLD-OUT-001` (available 0).

### payment-service: `PaymentProcessFlowTests`

Method under test: `PaymentService.charge(InventoryReserved req)`. The record has 6 fields in this order: `(orderId, productSku, quantity, customerName, totalAmount, currency)`.

| Test | Call | Assert |
|---|---|---|
| Successful charge | `charge(...)` with amount `10.00`, name `"alice"` | A payment for that orderId with status `COMPLETED`; the outbox has a `"PaymentCompleted"` event whose aggregateId is the orderId |
| Declined | amount `5000` (above the 1000 limit) or name `"fail"` | Payment status `FAILED`; the outbox has `"PaymentFailed"` |
| Idempotent | Call `charge(...)` **twice** with the same orderId | Exactly **one** payment exists for that orderId |

Hints:
- Read payments from `PaymentRepository`, not `paymentService.getAllPayments()`: that method is `@Cacheable`, so it can return a stale list from Redis.
- `PaymentRepository` has `existsByOrderId(Long)` and `findByPaymentReference(String)`, but no count-by-orderId method. Filter `findAll()` (`assertThat(...).filteredOn(p -> orderId.equals(p.getOrderId())).hasSize(1)`), or add `long countByOrderId(Long orderId);` to the repository (Spring Data derives the query from the method name).

### inventory-service: `InventoryReserveFlowTests`

Written in [InventoryReserveFlowTests.java](inventory-service/inventory-service/src/test/java/com/jaka/inventory_service/InventoryReserveFlowTests.java). Method under test: `InventoryService.reserve(OrderCreated order)`. The record has 7 fields in this order: `(orderId, orderNumber, customerName, totalAmount, currency, productSku, quantity)`.

| Test | Call | Assert |
|---|---|---|
| Stock reserved | `reserve(...)` with SKU `TEST-001`, quantity 2 | `available` of `TEST-001` **drops by 2** and `reserved` **rises by 2** compared with before the call; a `Reservation` for the orderId with status `RESERVED`; the outbox has `"InventoryReserved"` |
| Sold out | `reserve(...)` with SKU `SOLD-OUT-001` | **No** reservation for the orderId; the outbox has `"InventoryRejected"` |
| Stock returned when payment fails | `reserve(...)` then `release(new PaymentFailed(...))` for the same orderId | `available` returns to its original value; reservation status `RELEASED`; the outbox has `"InventoryReleased"` |

Points worth reading in that file:
- Stock is compared by **difference** (`before` / `after`), not absolute numbers, because the tests share the DB.
- `Reservation` uses the orderId as its primary key, so `reservationRepository.findById(orderId)` is enough. `hasValueSatisfying(...)` is the `Optional` counterpart of `anySatisfy`.

### notification-service: `NotificationHandleFlowTests`

Written in [NotificationHandleFlowTests.java](notification-service/notification-service/src/test/java/com/jaka/notification_service/NotificationHandleFlowTests.java). Method under test: `NotificationService.handle(EventEnvelope<JsonNode> event)`. This service **sends real mail** through `JavaMailSender`. There is no SMTP server in tests (Mailpit isn't running), so the mail sender is **replaced with a mock**:

- A field `@MockitoBean JavaMailSender mailSender;` in the test class (import `org.springframework.test.context.bean.override.mockito.MockitoBean`). `@MockitoBean` replaces the real bean in the context with a Mockito mock: `send()` does nothing but records that it was called, so the test can `verify` it. Mocks are reset after each test, so call counts don't leak between tests.
- The class is annotated `@SpringBootTest(properties = "management.health.mail.enabled=false")`. Reason: Actuator auto-registers a `mail` health indicator because `spring-boot-starter-mail` is on the classpath. That indicator looks for real `JavaMailSenderImpl` beans; once the mock replaces the bean, it finds none and the context fails with `'beans' must not be empty`. Turning off mail health only in this test class fixes it; the real app keeps its mail health check.

Building an `EventEnvelope<JsonNode>`:
- The payload is a `JsonNode`. Autowire `tools.jackson.databind.ObjectMapper` (the same bean `NotificationTemplates` uses) and call `json.valueToTree(new OrderCreated(...))` to turn the record into a `JsonNode`.
- The envelope has 6 fields: `(eventId, eventType, version, aggregateId, occurredAt, payload)`. For example `eventId` = `UUID.randomUUID().toString()`, `eventType` = `"OrderCreated"`, `version` = `1`, `occurredAt` = `Instant.now()`.

| Test | Call | Assert |
|---|---|---|
| Notification sent | `handle(envelope)` | `notificationRepository.existsByEventId(eventId)` is true; `verify(mailSender).send(any(SimpleMailMessage.class))` |
| Idempotent | `handle(envelope)` **twice** with the same `eventId` | One notification row; `verify(mailSender, times(1)).send(...)`: the mail is sent exactly once |
| Unknown event | `eventType` = `"Unknown"` | No notification saved; `verify(mailSender, never()).send(...)` |

`verify`, `times`, `never` and `any` are static imports from `org.mockito.Mockito` / `org.mockito.ArgumentMatchers` (Mockito ships with `spring-boot-starter-test`; no extra dependency).

> Note: a test class with `@MockitoBean` (or extra `properties`) has a **different** context configuration from the others, so Spring builds an extra context (and a new set of containers) for it. Tests are a bit slower. That's normal.

## A5. Run locally

```bash
cd order-service/order-service
./gradlew test
```

The first run is slow: it pulls 3 images plus a helper image, `testcontainers/ryuk`. Ryuk is a small "garbage collector" container that Testcontainers starts alongside the tests; when the test JVM's connection to it closes (normal finish, Ctrl+C, or a crash), Ryuk deletes every container labelled for that run and then removes itself. From the second run on it takes a few tens of seconds. Repeat for payment, inventory and notification, each from its own directory.

```bash
docker ps   # during the run: postgres, kafka, (redis), ryuk. After the run: nothing left
```

**If it's red:** read the report at `build/reports/tests/test/index.html`. Common causes: the Docker daemon isn't running (`systemctl start docker`), or a missing `@Import`.

## A6. Fix payment's gradlew mode (problem #2)

```bash
cd /home/taidt9/projects/springboot_aws_compute_databases
git update-index --chmod=+x payment-service/payment-service/gradlew
chmod +x payment-service/payment-service/gradlew
git ls-files -s */*/gradlew
```

The last command is the check: all 4 must show `100755`.

- `git update-index --chmod=+x` fixes the **permission recorded in git** (what GitHub's runner gets on checkout).
- `chmod +x` fixes the file on your machine so `./gradlew` works locally right away.

Why this never showed up on Windows: Windows has no execute bit. There, `.\gradlew` actually runs `gradlew.bat`, and Git for Windows uses `core.fileMode = false`, so it never reports the missing bit. Only a Linux machine (your Fedora laptop, or the `ubuntu-latest` runner) reads the `100644` and refuses to run the file. The mode change is a git change, so it goes into your next commit.

---

# STAGE B — A production-grade Dockerfile

## B1. Fix the jar name (problem #3a)

In the `build.gradle` of **all 4 services**, add:

```groovy
tasks.named('bootJar') {
    archiveFileName = 'app.jar'
}
```

By default Gradle names the jar `<name>-<version>.jar` (for example `order-service-0.0.1-SNAPSHOT.jar`), so the Dockerfile has to hardcode it and breaks when the version changes. With a fixed `app.jar`, the 4 services' Dockerfiles are **identical** except for EXPOSE.

## B2. Fix the Dockerfile (keep the existing structure, change only 4 things)

Your current Dockerfile already has a good structure: multi-stage, with a **dependency cache layer** (lines 4–6). Keep that structure, including `WORKDIR /build`, and change only the 4 things that have a reason.

After the fix (`order-service/order-service/Dockerfile`):

```dockerfile
# --- stage 1: build ---
FROM gradle:9.5.1-jdk17 AS build
WORKDIR /build
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle clean bootJar --no-daemon                     # (1) build -x test -> bootJar

# --- stage 2: run ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app             # (2) non-root user
USER app
COPY --from=build /build/build/libs/app.jar app.jar      # (3) fixed jar name
EXPOSE 8081                                              # (4) the real port
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Unchanged, and why to keep it:**

- `WORKDIR /build`: the directory name in the build stage doesn't matter; renaming it gains nothing.
- Lines 4–6 (copy `build.gradle` first, run `gradle dependencies`, only then copy `src`): this is Docker's **layer-cache trick**. Docker caches the result of each instruction and only re-runs from the first instruction whose input changed. Because `build.gradle` rarely changes, the dependency download (the slowest step) comes from cache; editing code in `src` only re-runs from line 7. Without this trick, editing one line of code re-downloads every dependency.

**The 4 changes:**

1. **`gradle clean build -x test` becomes `gradle clean bootJar`.** Tests no longer run inside the Docker build, because they already ran in the earlier CI step (with Testcontainers, Gradle cache, and a report on failure). `bootJar` only packages the runnable jar; `build` would also run `check`, the plain `jar` task, and so on, none of which is needed here.
2. **Add the `app` user + `USER app`.** See the security note below.
3. **`order-service-0.0.1-SNAPSHOT.jar` becomes `app.jar`.** Thanks to B1, the name is fixed, so a version change doesn't break the Dockerfile.
4. **Correct `EXPOSE`.** EXPOSE is only documentation (it opens no port), but wrong documentation misleads you while debugging.

| Service | `COPY --from=build` line | Current `EXPOSE` | Change to |
|---|---|---|---|
| order | `order-service-0.0.1-SNAPSHOT.jar` → `app.jar` | `8080` (wrong) | `8081` |
| payment | `payment-service-0.0.1-SNAPSHOT.jar` → `app.jar` | `8080` (wrong) | `8082` |
| inventory | `inventory-service-0.0.1-SNAPSHOT.jar` → `app.jar` | `8083` (correct) | keep |
| notification | `notification-service-0.0.1-SNAPSHOT.jar` → `app.jar` | `8084` (correct) | keep |

### Security note: why `USER app`

- The jar doesn't need root. It runs as root only because the `eclipse-temurin` image declares no `USER`, so Docker defaults to uid 0. Port 8081–8084 is above 1024, so a normal user can bind it.
- The build stage still runs as root, which doesn't matter: that stage is thrown away and only `app.jar` is copied out of it.
- A container is **not a VM**. Every container on a node shares the EC2 node's **kernel**, so root in the container is **uid 0 on the node's kernel**, the same identity as the EC2's root. Namespaces, cgroups and dropped capabilities are what stop it from acting like the node's root.
- If an attacker exploits the app (for example an RCE in a library) **and** escapes the container while running as uid 0, they become root on the node: they can reach every other pod on that node and the node's IAM role via instance metadata. As a normal user (`app`), an escape lands them as an unprivileged user. This is least privilege, the same idea as IAM.
- `-S` creates a system user with no shell and no password.

Verify it yourself:

```bash
cd ~/projects/springboot_aws_compute_databases/order-service/order-service
docker build -t order-service:test .
docker run --rm --entrypoint id order-service:test
docker run --rm --entrypoint id eclipse-temurin:17-jre-alpine
```

The first prints `uid=...(app)`; the second (the base image, no `USER`) prints `uid=0(root)`. Kubernetes can additionally **enforce** this at pod level with `securityContext` (`runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: ["ALL"]`); `runAsNonRoot` only works once the image itself runs as a non-root user.

## B3. `.dockerignore`

Create `order-service/order-service/.dockerignore` (and the same for the other 3 services):

```
build/
.gradle/
.git/
*.md
```

How `docker build .` works: the `docker` command is only a **client**. The real work is done by the **Docker daemon** (`dockerd`), a background process the client talks to over `/var/run/docker.sock`. The `.` is the **build context**: the client packs that whole directory and sends it to the daemon before running any Dockerfile line, and `COPY` reads from that package, not from your disk.

Without `.dockerignore`, the package includes the old `build/` directory (hundreds of MB of jars and classes), `.gradle/` and `.git/`, even though the Dockerfile never uses them. The build gets slower and the cache breaks more easily. The first line of `docker build` output shows the size (`transferring context: ...`), so compare before and after.

## B4. Test build

```bash
cd order-service/order-service
docker build -t order-service:test .
docker image ls order-service:test
```

---

# STAGE C — Move ECR to `envs/shared` (repo `jaka_infrastructure`)

## C1. Why a `shared` environment

Look at `jaka_infrastructure/envs/dev-eks/`: it calls `module "ecr"` inside the cluster environment. That ties the ECR lifecycle **tightly** to the cluster lifecycle, and you build and destroy this cluster constantly to save money. CI needs to push images **at any time**, including when the cluster doesn't exist.

The fix: a third Terraform environment, `envs/shared`, holding things that are **long-lived, cheap and shared**: ECR plus the OIDC identity for GitHub. Separate state, applied and destroyed independently.

```
envs/
├── dev/        # months 1-2: EC2 + RDS (ephemeral)
├── dev-eks/    # month 3: EKS (ephemeral)
└── shared/     # NEW — ECR + GitHub OIDC (long-lived)
```

Bonus: `modules/ecr` has no `force_delete`, so `terraform destroy` on dev-eks fails with `RepositoryNotEmptyException` whenever a repo still holds images (THANG-3 stage 18 lists this error). With ECR out of dev-eks, that error is gone.

## C2. Upgrade `modules/ecr` (new variables, stay backwards compatible)

`modules/ecr` currently hardcodes `image_tag_mutability = "MUTABLE"` and `tagPrefixList = ["v"]`. Two other roots call this module: `stack/main.tf` (used by `envs/dev`) and `envs/dev-eks/main.tf`. Both pass only `services` and `name_prefix`, so **every new variable must default to the old value**. A different default would make `terraform plan` in those roots show a change to resources you didn't mean to touch.

Add to `modules/ecr/variables.tf`:

```hcl
variable "image_tag_mutability" {
  description = "MUTABLE allows overwriting tags; IMMUTABLE forbids it (used by CI)"
  type        = string
  default     = "MUTABLE"

  validation {
    condition     = contains(["MUTABLE", "IMMUTABLE"], var.image_tag_mutability)
    error_message = "image_tag_mutability must be MUTABLE or IMMUTABLE."
  }
}

variable "tag_prefixes" {
  description = "Tag prefixes kept by the lifecycle policy (rule 2)"
  type        = list(string)
  default     = ["v"]
}
```

While you're in `variables.tf`, update the description of the existing `keep_tagged_images`: it says "v* tagged images", which is no longer true once the prefix is a variable. For example: `"How many tagged images matching tag_prefixes the lifecycle policy keeps"`.

In `modules/ecr/main.tf`, change one line in the repository resource. Everything else stays (`for_each = var.services`, the name, `image_scanning_configuration`, `encryption_configuration`):

```hcl
resource "aws_ecr_repository" "this" {
  for_each = var.services

  name                 = "${var.name_prefix}-${each.key}-service"
  image_tag_mutability = var.image_tag_mutability # previously hardcoded "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}
```

And in the lifecycle policy, replace the hardcoded `["v"]` with:

```hcl
tagPrefixList = var.tag_prefixes
```

Add an output to `modules/ecr/outputs.tf` (this week needs the ARNs to write the IAM policy):

```hcl
output "repository_arns" {
  description = "ARN of each repository, for IAM policies"
  value       = { for k, r in aws_ecr_repository.this : k => r.arn }
}
```

Add a `run` block to `modules/ecr/tests/ecr.tftest.hcl` to lock in the new behaviour:

```hcl
run "ci_settings_reach_resources" {
  command = plan

  variables {
    image_tag_mutability = "IMMUTABLE"
    tag_prefixes         = ["sha-"]
  }

  assert {
    condition     = aws_ecr_repository.this["order"].image_tag_mutability == "IMMUTABLE"
    error_message = "image_tag_mutability was not passed down to the resource"
  }
}
```

The `variables` block here only lists what this run changes. `name_prefix` and `services` come from the file-level `variables` block at the top of `ecr.tftest.hcl` (`"jaka-mod-test"`, `["order", "payment"]`), which every `run` inherits.

Run the tests and format the module (the infra CI's `fmt` job runs `terraform fmt -check -recursive`, so unformatted files fail CI):

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/modules/ecr
terraform fmt -recursive
terraform test
```

Expected result: `Success! 4 passed, 0 failed.`

## C3. Create `envs/shared`

Create `jaka_infrastructure/envs/shared/` with the following files. Each one is modelled on the matching file in `envs/dev-eks`. Open that file next to it and compare.

**`versions.tf`** (same as `envs/dev-eks/versions.tf`, minus the `random` provider that shared doesn't use):

```hcl
terraform {
  # use_lockfile (S3-native state locking) needs >= 1.10
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Partial config - the rest comes from backend.hcl:
  #  terraform init -backend-config backend.hcl
  backend "s3" {}
}
```

`required_version` must be `>= 1.10.0`, not `>= 1.9`: `backend.hcl` below uses `use_lockfile`, which Terraform 1.9 doesn't know.

**`backend.hcl`** (same state bucket as dev-eks, **different key**: one state per environment):

```hcl
bucket       = "jaka-mod-tfstate-<ACCOUNT_ID>"
key          = "shared/terraform.tfstate"
region       = "ap-southeast-1"
encrypt      = true
use_lockfile = true
```

Replace `<ACCOUNT_ID>` the same way you did in `envs/dev-eks/backend.hcl`. The value is the `account_id` output of `bootstrap/` (`terraform -chdir=bootstrap output -raw account_id`), and the full bucket name is its `state_bucket` output.

**`providers.tf`:**

```hcl
provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = local.project
      Environment = "shared"
      ManagedBy   = "Terraform"
    }
  }
}
```

`ManagedBy = "Terraform"` with a capital T matches `envs/dev-eks/providers.tf`, so tag-based filters find every environment.

**`variables.tf`:**

```hcl
variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-southeast-1"
}

variable "github_owner" {
  description = "GitHub user/org that owns the repo"
  type        = string
  default     = "ghtaidt9"
}

variable "app_repo" {
  description = "Name of the repo holding the 4 services"
  type        = string
  default     = "springboot_aws_compute_databases"
}
```

**`main.tf`:**

```hcl
locals {
  project = "jaka-mod"
}

module "ecr" {
  source = "../../modules/ecr"

  name_prefix = local.project
  services    = ["order", "payment", "inventory", "notification"]

  image_tag_mutability = "IMMUTABLE"
  tag_prefixes         = ["sha-"]
  keep_tagged_images   = 15
}
```

`services` is the module's only collection input: `variable "services"` in `modules/ecr/variables.tf`, type `set(string)`. Terraform converts the list to a set by itself. `envs/dev-eks` passes the same input as `toset(keys(local.apps))`; shared has no `local.apps`, so the 4 names are written out.

**The repo names change.** The module names each repo `"${name_prefix}-${service}-service"`:

| Environment | `name_prefix` | Repo name for order |
|---|---|---|
| `envs/dev-eks` | `jaka-mod-dev-eks` (`local.name_prefix`) | `jaka-mod-dev-eks-order-service` |
| `envs/shared` | `jaka-mod` (`local.project`) | `jaka-mod-order-service` |

That's why the reusable workflow in stage D uses `REPO: jaka-mod-${{ inputs.service }}-service`.

The last 3 lines:

- `IMMUTABLE`: ECR rejects pushing an existing tag. Since the tag is the commit SHA, "the image for commit X" is absolutely fixed.
- `tag_prefixes = ["sha-"]`: lifecycle rule 2 now keeps `sha-*` tags (created by CI) instead of `v*`.
- `keep_tagged_images = 15`: keep the 15 most recent images per repo; older ones are deleted automatically (saves storage cost).

**`outputs.tf`:**

```hcl
output "ecr_repository_urls" {
  description = "Full URL of each ECR repo"
  value       = module.ecr.repository_urls
}

output "ecr_registry" {
  description = "Registry part (before the /) for docker login"
  value       = split("/", values(module.ecr.repository_urls)[0])[0]
}

output "gha_ci_role_arn" {
  description = "ARN of the role GitHub Actions assumes"
  value       = aws_iam_role.gha_ci.arn
}
```

**`terraform.tfvars`** (same content as `envs/dev-eks/terraform.tfvars` minus the Kubernetes version). C5 runs `terraform plan -var-file terraform.tfvars`, and that command fails if the file doesn't exist:

```hcl
region = "ap-southeast-1"
```

## C4. GitHub OIDC — `envs/shared/github-oidc.tf`

This is the "why no access key is needed" part. The mechanism:

```
GitHub Actions job                         AWS
───────────────────                        ───
1. Ask GitHub for an OIDC token (JWT)
   stating which repo and which branch
2. Send the token to AWS STS ────────────▶  3. STS verifies the signature against
                                              token.actions.githubusercontent.com
                                           4. Checks the trust policy conditions:
                                              right aud? sub = right repo/branch?
◀────────────────────────────────────────  5. Issues temporary credentials (~1h)
6. Uses the credentials to push to ECR
```

No secret is stored anywhere. The "secret" is GitHub signing the token, and AWS trusting that signature.

```hcl
# 1) Tell AWS: "I trust GitHub's token issuer"
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# 2) The role the workflow assumes
resource "aws_iam_role" "gha_ci" {
  name = "${local.project}-gha-ci"

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
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_owner}/${var.app_repo}:ref:refs/heads/master"
        }
      }
    }]
  })
}

# 3) What the role may do: push/read ECR, nothing else
resource "aws_iam_role_policy" "gha_ci_ecr" {
  name = "ecr-push"
  role = aws_iam_role.gha_ci.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:CompleteLayerUpload",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
          "ecr:DescribeImages",
        ]
        Resource = values(module.ecr.repository_arns)
      },
    ]
  })
}
```

The important fields:

- `aws_iam_openid_connect_provider`: an account can hold **only one** provider per URL. Before the first apply, check whether one already exists:

  ```bash
  aws iam list-open-id-connect-providers
  ```

  If the list contains an ARN ending in `token.actions.githubusercontent.com`, Terraform's create fails with `EntityAlreadyExists`. In that case, import it before `apply`:

  ```bash
  terraform import aws_iam_openid_connect_provider.github <that-ARN>
  ```

  `client_id_list = ["sts.amazonaws.com"]`: the token's `aud` (audience) must be in this list. GitHub writes and signs the token; the workflow step `aws-actions/configure-aws-credentials` (the action published by AWS) only *requests* the audience. Its `audience` input defaults to `sts.amazonaws.com` (see its `action.yml`), and `_build-service.yml` (stage D) doesn't override it, so the token GitHub issues carries `"aud": "sts.amazonaws.com"`.
- `sub` (subject): a string GitHub writes into the token, formatted `repo:<owner>/<repo>:ref:refs/heads/<branch>` when a job runs on a branch (push or `workflow_dispatch`). This is **the most important fence**: only your repo, running on master, can assume the role. Tokens from any other repo or branch are rejected because `sub` doesn't match.
- Why PRs aren't in the trust policy: in the stage-D workflow, every AWS step has `if: github.event_name != 'pull_request'`, so a PR job never asks for this role. Allowing `repo:<owner>/<repo>:pull_request` would only widen the door without anything using it.
- The defaults `github_owner = "ghtaidt9"` and `app_repo = "springboot_aws_compute_databases"` match the app repo's `origin` remote (`https://github.com/ghtaidt9/springboot_aws_compute_databases.git`).
- `ecr:GetAuthorizationToken` must have `Resource = "*"`: this API can't be scoped to a specific repo (an ECR limitation). All other actions are locked to exactly the 4 repos via `repository_arns` (the output added in C2).
- This role has **no** EKS/EC2/S3 permissions at all. CI only needs to push images. Deploy permissions are week 14's business, with a different role.

## C5. Clean up `envs/dev-eks` + apply

> **Prerequisite:** if dev-eks currently **exists on AWS**, destroy it before this step. Reason: once `module "ecr"` is gone from `envs/dev-eks`, its next `apply` tries to delete the `jaka-mod-dev-eks-*-service` repos, and that fails with `RepositoryNotEmptyException` while they hold images (the module has no `force_delete`). The names don't collide with shared's repos (`jaka-mod-*-service`, see C3). If dev-eks isn't running, just edit the code.

### 1. `envs/dev-eks`

1. Delete the `module "ecr"` block from `envs/dev-eks/main.tf`.
2. Delete the `ecr_repository_urls` output from `envs/dev-eks/outputs.tf`.

### 2. `scripts/eks-up.sh` reads that output, so point it at shared

Line 17 of `scripts/eks-up.sh` reads the output you just deleted:

```bash
ecr_urls=$(tf_out ecr_repository_urls)
```

`tf_out` is defined on line 12 and always reads `envs/dev-eks`. Without a change, the script stops right there (`set -euo pipefail`). Replace line 17 with a read from `envs/shared`:

```bash
ecr_urls=$(terraform -chdir=envs/shared output -json ecr_repository_urls)
```

Steps 2 and 9 of the script keep working: they use `pick "$ecr_urls" <svc>`, and shared's output is a map with the same keys (`order`, `payment`, ...). The EKS nodes can pull from the new repos too: the node group's IAM role gets the AWS-managed `AmazonEC2ContainerRegistryReadOnly` policy (set by `terraform-aws-modules/eks` v21), which isn't limited to particular repos.

> **One leftover:** `eks-up.sh` line 9 tags its images `eks-<unix-time>`. Those tags don't start with `sha-`, so no lifecycle rule matches them and they stay in ECR until you delete them. Week 14 (B3) removes the build-and-push step from `eks-up.sh` entirely; until then, list them with this command:
>
> ```bash
> aws ecr list-images --repository-name jaka-mod-order-service --filter tagStatus=TAGGED --query 'imageIds[?starts_with(imageTag, `eks-`)]'
> ```

### 3. The infra repo's CI

Open `.github/workflows/terraform.yml` in `jaka_infrastructure`. The `validate-envs` job's matrix is currently `env: [dev, dev-eks]`. Add `shared`:

```yaml
    strategy:
      matrix:
        # env: [dev, prod]
        env: [dev, dev-eks, shared]
```

That job runs `terraform init -backend=false` and `terraform validate` in each env. The same workflow also runs `terraform fmt -check -recursive` over the whole repo, so format before committing:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
terraform fmt -recursive
```

### 4. Apply

**Needs AWS credentials on your machine.** `init` reads/writes the state in S3, and `plan`/`apply` call AWS. Check first:

```bash
aws sts get-caller-identity
```

It must print your account. If you get `aws: command not found`, or Terraform says `No valid credential sources found`, this machine has no AWS login yet. Install the CLI (`sudo dnf install awscli2`) and run `aws configure` with an IAM user's access key (or `aws configure sso`). Until then you can still check the files offline with `terraform init -backend=false` and `terraform validate`.

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/envs/shared
terraform init -backend-config backend.hcl
terraform plan -var-file terraform.tfvars
terraform apply -var-file terraform.tfvars
terraform output
```

Note the `gha_ci_role_arn` output; you need it in stage D.

> **About cleanliness:** if the apply fails halfway, `shared`'s state is fully independent. Re-running `terraform apply` picks up where it left off; nothing touches `dev` or `dev-eks`, and a single `terraform destroy` in this directory removes everything.

---

# STAGE D — One CI workflow per service

## D1. The big picture

Your services repo holds 4 services. Production requires that **each service is tested, built, tagged and deployed on its own**: a change in notification must not rebuild, retest or redeploy order. Whether the 4 live in one repo (monorepo) or four (polyrepo) is a separate choice; both are used in production. You keep the monorepo, and give each service **its own workflow**:

```
.github/workflows/
├── order-ci.yml          on: changes under order-service/**         ─┐
├── payment-ci.yml        on: changes under payment-service/**        │  each one: 1 job
├── inventory-ci.yml      on: changes under inventory-service/**      ├─ that calls ▼
├── notification-ci.yml   on: changes under notification-service/**  ─┘
└── _build-service.yml    reusable workflow: test → build → push (one copy of the steps)
```

- **4 small caller workflows.** Each one decides *when* its service runs (its own folder changed), and nothing else. In the Actions tab you see 4 separate workflows, each with its own run history, like 4 separate repos.
- **1 reusable workflow (`_build-service.yml`).** The steps (test, build, push to ECR) are written once and called with a parameter `service: order`. Fixing a step fixes it for all 4. This is also how polyrepo setups share CI: each repo calls one central reusable workflow.
- The leading `_` is just a naming convention meaning "not run directly". GitHub requires reusable workflows to sit directly in `.github/workflows/`, not in a subfolder.

What triggers what:

| You change… | Workflows that run |
|---|---|
| `notification-service/**` | `notification-ci` only |
| `order-service/**` and `payment-service/**` in one push | `order-ci` and `payment-ci`, in parallel |
| `.github/workflows/_build-service.yml` | all 4 (it's listed in every caller's `paths`) |
| `README.md` only | none |

## D2. The reusable workflow — `.github/workflows/_build-service.yml`

```yaml
name: build-service

on:
  workflow_call:
    inputs:
      service:
        description: 'Short service name: order | payment | inventory | notification'
        required: true
        type: string

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write
    env:
      AWS_REGION: ap-southeast-1
      SVC_DIR: ${{ inputs.service }}-service/${{ inputs.service }}-service
      REPO: jaka-mod-${{ inputs.service }}-service
      TAG: sha-${{ github.sha }}
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 17

      - uses: gradle/actions/setup-gradle@v6

      - name: Run tests
        working-directory: ${{ env.SVC_DIR }}
        run: ./gradlew test --no-daemon

      - name: Upload test report
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: test-report-${{ inputs.service }}
          path: ${{ env.SVC_DIR }}/build/reports/tests/test

      - uses: docker/setup-buildx-action@v4

      # ----- PR: build only, no push -----
      - name: Build image (PR only)
        if: github.event_name == 'pull_request'
        uses: docker/build-push-action@v7
        with:
          context: ${{ env.SVC_DIR }}
          push: false
          tags: ${{ env.REPO }}:${{ env.TAG }}
          cache-from: type=gha,scope=${{ inputs.service }}
          cache-to: type=gha,mode=max,scope=${{ inputs.service }}
          provenance: false

      # ----- push to master / manual run: AWS login + push to ECR -----
      - name: Configure AWS credentials
        if: github.event_name != 'pull_request'
        uses: aws-actions/configure-aws-credentials@v6
        with:
          role-to-assume: ${{ vars.AWS_CI_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to ECR
        if: github.event_name != 'pull_request'
        id: ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Check image already exists
        if: github.event_name != 'pull_request'
        id: exists
        run: |
          if aws ecr describe-images --repository-name "$REPO" \
               --image-ids imageTag="$TAG" >/dev/null 2>&1; then
            echo "skip=true" >> "$GITHUB_OUTPUT"
            echo "Image $REPO:$TAG already exists, skipping push (immutable tag)."
          else
            echo "skip=false" >> "$GITHUB_OUTPUT"
          fi

      - name: Build and push image
        if: github.event_name != 'pull_request' && steps.exists.outputs.skip == 'false'
        uses: docker/build-push-action@v7
        with:
          context: ${{ env.SVC_DIR }}
          push: true
          tags: ${{ steps.ecr.outputs.registry }}/${{ env.REPO }}:${{ env.TAG }}
          cache-from: type=gha,scope=${{ inputs.service }}
          cache-to: type=gha,mode=max,scope=${{ inputs.service }}
          provenance: false
```

## D3. One caller per service — `.github/workflows/order-ci.yml`

```yaml
name: order-ci

on:
  push:
    branches: [master]
    paths:
      - 'order-service/**'
      - '.github/workflows/order-ci.yml'
      - '.github/workflows/_build-service.yml'
  pull_request:
    paths:
      - 'order-service/**'
      - '.github/workflows/order-ci.yml'
      - '.github/workflows/_build-service.yml'
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  build:
    uses: ./.github/workflows/_build-service.yml
    permissions:
      contents: read
      id-token: write
    with:
      service: order
```

Create the other 3 the same way. Per file, only these change:

| File | `name:` | Folder in `paths` | `service:` |
|---|---|---|---|
| `payment-ci.yml` | `payment-ci` | `payment-service/**` | `payment` |
| `inventory-ci.yml` | `inventory-ci` | `inventory-service/**` | `inventory` |
| `notification-ci.yml` | `notification-ci` | `notification-service/**` | `notification` |

(Each also lists **its own** file name in `paths`, e.g. `.github/workflows/payment-ci.yml`.)

## D4. Block-by-block explanation

**Caller (`order-ci.yml`):**

- **`on.push.branches: [master]` + `paths`**: run only when a push to master changes a file matching one of the patterns. GitHub does the filtering before starting anything, so an unrelated push costs no runner time at all.
- **The two workflow files in `paths`**: editing the reusable workflow affects every service, so it triggers all 4. Editing `order-ci.yml` triggers only order.
- **`pull_request` with the same `paths`**: every PR touching order is tested and test-built (no push; a PR isn't official code yet).
- **`workflow_dispatch`**: the "Run workflow" button, to force a rebuild of this one service. `paths` doesn't apply to it.
- **`permissions: contents: read`** at the top lowers the default token permissions to the minimum (least privilege, same idea as IAM).
- **`concurrency`**:
  - `group: ${{ github.workflow }}-${{ github.ref }}`: one queue per **service and branch** (`order-ci-refs/heads/master`). Order and payment never block each other.
  - `cancel-in-progress` only for PRs: a new commit on a PR cancels the old run (nobody needs it). On master it's off: every merge must produce an image, and cancelling a running job would lose that commit's image.
  - GitHub keeps only one *pending* run per group, so 3 merges touching order within seconds can drop the middle run. A `workflow_dispatch` rebuild covers that rare case.
- **`jobs.build.uses: ./.github/workflows/_build-service.yml`**: a job that calls a reusable workflow has no `steps`; its body is the other file. `with: service: order` is the parameter.
- **`jobs.build.permissions` with `id-token: write`**: a called workflow can only *keep or reduce* the permissions its caller job grants. Without `id-token: write` here, GitHub rejects the run **before the job starts**, because the called job asks for more than the caller allows (an error like "The nested job 'build' is requesting 'id-token: write', but is only allowed 'id-token: none'"). No step runs, not even the tests, and this happens on PRs too.

**Reusable (`_build-service.yml`):**

- **`on: workflow_call` + `inputs.service`** (`type: string`): turns the file into a function. `${{ inputs.service }}` replaces the old `${{ matrix.service }}` everywhere.
- **`github.event_name` inside it is the caller's event** (`push`, `pull_request` or `workflow_dispatch`). That's why the same `if: github.event_name != 'pull_request'` conditions work unchanged.
- **`vars.AWS_CI_ROLE_ARN`**: the `vars` context of a called workflow comes from the caller's repo, so the variable you set in D5 is visible here.
- **The OIDC `sub` claim doesn't change.** For a reusable workflow, GitHub builds `sub` from the **caller's** repo and ref: still `repo:ghtaidt9/springboot_aws_compute_databases:ref:refs/heads/master`. The trust policy from C4 works as is.
- **`SVC_DIR`, `REPO`, `TAG`**: same meaning as before. `REPO` is `jaka-mod-<service>-service`, matching C3's repo names. `TAG` is the full commit SHA, so you can map image → commit by eye.
- **`setup-gradle`** caches Gradle dependencies between runs.
- **`fail-fast` is gone**: it only existed for the matrix. Separate workflows never cancel each other.
- **`cache-from/to: type=gha,scope=<service>`**: Docker layer cache in the GitHub Actions cache, one scope per service so they don't overwrite each other.
- **`provenance: false`**: buildx attaches an attestation by default, which turns the image into an "image index" that some tools (and the ECR console) display oddly. Turn it back on when you need supply-chain attestation.
- **`Check image already exists`**: the answer to the IMMUTABLE trade-off at the top. Re-running on the same commit → the image exists → skip the push → the run stays green.

### The trade-off: no single "CI passed" check

A single all-services workflow can end with one always-running "gatekeeper" job, so branch protection requires just that one check. Separate path-filtered workflows have no such job, and GitHub's own docs warn about this case: if a workflow is **skipped** because of `paths`, its checks stay **Pending**, and a PR that *requires* them can't be merged. So:

- **Now (one person, no branch protection):** nothing to do. Every PR shows only the workflows of the services it touches.
- **If you later turn on branch protection:** don't mark `order-ci / build` etc. as required. Either require nothing from these workflows, or add one small always-running check workflow. GitHub's docs page "Handling skipped but required checks" describes the options.

## D5. First run — step by step

### TL;DR

Order matters: **AWS first, then GitHub settings, then code.** A workflow that runs before its role and variable exist fails at the AWS login.

```
0. AWS access on your machine  →  1. Check prerequisites  →  2. Apply envs/shared  →  3. Set AWS_CI_ROLE_ARN
→  4. Push the infra repo  →  5. Push the app repo + open a PR  →  6. Watch the PR run  →  7. Merge
→  8. Watch the master run  →  9. Verify ECR
```

### What each run does

| Event | Workflows that start | Steps that run | Touches AWS? |
|---|---|---|---|
| Open the PR (step 5) | all 4, because the PR **adds** every workflow file, and each one lists its own file + `_build-service.yml` in `paths` | test → build image (`push: false`) | no |
| Merge (step 7) | all 4 again (the merge commit adds the same files to master) | test → AWS login → exists check → build **and push** | yes |
| Later pushes | only the services whose folder changed | same as the merge | yes |

### No AWS yet? Do D5 in two passes

Everything GitHub-side works without AWS: PR runs skip every AWS step. Do pass 1 now, and pass 2 in one sitting once you have an AWS account and key. Steps marked **⏸ AWS** in their heading belong to pass 2.

| Step | Pass 1 (now, no AWS) | Pass 2 (when you have AWS) |
|---|---|---|
| 0. AWS access on your machine | skip | ✅ do it first |
| 1. Prerequisites | ✅ every row **except** `aws sts get-caller-identity` | ✅ that row |
| 2. Apply `envs/shared` | skip | ✅ |
| 3. Set `AWS_CI_ROLE_ARN` | skip (reads `terraform output`, which needs the state in S3) | ✅ |
| 4. Push the infra repo | ✅ | — |
| 5. Push the app repo + open the PR | ✅ | — |
| 6. Watch the PR run | ✅ all 4 go green on tests + image build; AWS steps show "skipped" | — |
| 7. Merge | **❌ don't merge yet** (see below) | ✅ after step 3 |
| 8. Watch the master run | skip | ✅ |
| 9. Verify ECR | skip | ✅ |
| D6 self-check | ✅ item 3 (a PR with a broken test) and item 4 (the questions) | ✅ items 1 and 2 (they push to master and re-run a push) |

**Why not merge in pass 1:** the merge commit starts the `master` runs, and those reach `Configure AWS credentials`. With no role and no `AWS_CI_ROLE_ARN`, all 4 runs fail there: red runs on `master`. Nothing is half-done (the test step and image build pass, and no image is stored), but leave the PR open instead. You can keep pushing commits to its branch; each push re-runs the PR checks.

**Pass 2 order:** 0 → the AWS row of 1 → 2 → 3 → 7 → 8 → 9 → D6 items 1–2.

### Step 0. AWS access on your machine ⏸ AWS

Steps 2, 3 and 9 run **on your laptop** and call AWS (Terraform, `aws ecr`). Your laptop needs its own AWS login. It is separate from CI's:

| Who | Identity | Credentials | Where they live |
|---|---|---|---|
| You (laptop) | an IAM **user**, e.g. `taidt9-cli` | long-lived access key | `~/.aws/credentials` on your machine only |
| CI (GitHub Actions) | the IAM **role** `jaka-mod-gha-ci` | temporary, from STS via OIDC | nowhere; issued per run |

You create the role (and everything else) **with** your user's credentials. CI never sees your key.

#### 0a. Install the AWS CLI

```bash
sudo dnf install awscli2
aws --version
```

#### 0b. Create an IAM user and access key (AWS Console, in the browser)

Skip this if you already have an access key for an IAM user (for example in `~/.aws/credentials` on another machine; you can reuse it).

1. Sign in at https://console.aws.amazon.com.
2. **IAM → Users → Create user**. Name: e.g. `taidt9-cli`. Leave "console access" unticked (CLI only).
3. Permissions: **Attach policies directly → `AdministratorAccess`**. In this learning account, Terraform creates VPCs, EKS, RDS and IAM roles, so it needs broad rights.
4. Open the user → **Security credentials → Create access key** → use case **Command Line Interface (CLI)**.
5. Copy the **Access key ID** and the **Secret access key**. The secret is shown **only once**.

Rules: never create access keys for the **root** user, and never commit a key to git.

#### 0c. Give the key to the CLI

```bash
aws configure
```

It asks 4 questions: the access key ID, the secret, default region `ap-southeast-1`, output format `json`. It writes `~/.aws/credentials` and `~/.aws/config`. Terraform reads the same files, so it needs no extra setup.

#### 0d. Check the login

```bash
aws sts get-caller-identity
```

Expected: `UserId`, `Account` (12 digits) and `Arn` (`arn:aws:iam::<account>:user/taidt9-cli`). If you got `No valid credential sources found` from Terraform earlier, this is the fix.

#### 0e. The Terraform state bucket

Every environment keeps its state in the bucket `jaka-mod-tfstate-<account>`, created by `bootstrap/` (THANG-1). `bootstrap/` keeps its own state **locally**, and this machine has no `bootstrap/terraform.tfstate`, so it was never applied from here. Check whether the bucket exists in your account:

```bash
aws s3 ls | grep jaka-mod-tfstate
```

- **Listed:** it exists (applied from another machine). Go to 0f.
- **Nothing listed:** create it once:

  ```bash
  cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/bootstrap
  terraform init
  terraform apply
  terraform output
  ```

  Keep the `terraform.tfstate` this creates in `bootstrap/` (git ignores it). The bucket has `prevent_destroy = true`, so Terraform refuses to delete it by accident.

#### 0f. Fill in `backend.hcl`

Replace `<ACCOUNT_ID>` in `jaka_infrastructure/envs/shared/backend.hcl` with your 12-digit account. That's the `Account` from 0d, the same as `terraform -chdir=bootstrap output -raw account_id`:

```hcl
bucket       = "jaka-mod-tfstate-123456789012"
```

Check that the name matches a bucket that exists:

```bash
grep bucket ~/projects/springboot_aws_compute_databases/jaka_infrastructure/envs/shared/backend.hcl
aws s3 ls | grep jaka-mod-tfstate
```

### Step 1. Prerequisites

| Check | Command | Expected |
|---|---|---|
| AWS login works on this machine (⏸ AWS: skip in pass 1) | `aws sts get-caller-identity` | your account + ARN (step 0) |
| GitHub CLI logged in | `gh auth status` | "Logged in to github.com account ghtaidt9" |
| Workflows lint clean | `docker run --rm -v "$PWD:/repo:ro,Z" -w /repo rhysd/actionlint:1.7.7 -no-color -oneline` (from the app repo root) | no output |
| Each workflow points at its own service | `grep -n 'service/\*\*\|service: ' .github/workflows/*-ci.yml` | every file's 2 `paths` and its `service:` name the same service |
| `gradlew` executable in git | `git ls-files -s '*/gradlew'` | all 4 show `100755` (A6) |

If `gh auth status` says you're not logged in:

```bash
gh auth login
```

Choose GitHub.com → HTTPS → "Login with a web browser".

### Step 2. Apply `envs/shared` (C5 step 4) ⏸ AWS

The role and the 4 repos must exist before CI can use them:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure/envs/shared
terraform init -backend-config backend.hcl
terraform apply -var-file terraform.tfvars
terraform output
```

`terraform output` must show `ecr_repository_urls` (4 entries), `ecr_registry` and `gha_ci_role_arn`.

### Step 3. Set the GitHub variable ⏸ AWS

The workflow reads the role ARN from a **repository variable** (not a secret: an ARN isn't secret, and a variable stays visible, which helps debugging). Read it straight from Terraform, so you never type the account ID:

```bash
cd ~/projects/springboot_aws_compute_databases
gh variable set AWS_CI_ROLE_ARN \
  --repo ghtaidt9/springboot_aws_compute_databases \
  --body "$(terraform -chdir=jaka_infrastructure/envs/shared output -raw gha_ci_role_arn)"
gh variable list --repo ghtaidt9/springboot_aws_compute_databases
```

The list must show `AWS_CI_ROLE_ARN` = `arn:aws:iam::<account>:role/jaka-mod-gha-ci`. The name must match `vars.AWS_CI_ROLE_ARN` in `_build-service.yml` exactly; a misspelt variable is an empty string, and the AWS login fails with a missing `role-to-assume`.

### Step 4. Push the infra repo

Not needed for this week's CI, but weeks 14–15 read the infra repo's `master` from GitHub. Current state:
- local branch `eks-platform` (never pushed) holds the EKS work plus this week's uncommitted changes (`modules/ecr`, `envs/shared/`, `envs/dev-eks`, `scripts/eks-up.sh`, `.github/workflows/terraform.yml`);
- local `master` is at `8e82182` (unpushed);
- GitHub's `master` is at `023b335` ("k8s, mailpit, kafka").

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
terraform fmt -check -recursive
git status --short
git add -A
git commit -m "Move ECR to envs/shared, add GitHub OIDC CI role"
git switch master
git merge eks-platform
git push origin master
```

- `terraform fmt -check` first: the infra repo's own CI (`terraform.yml`) fails on unformatted files.
- Look at `git status` before `git add -A`. `.gitignore` already excludes `.terraform/`, `*.tfstate` and `.env`, but check that nothing else private is listed.
- `master` is an ancestor of `eks-platform`, so the merge is a fast-forward (no merge commit).
- The push also starts the infra repo's `terraform` workflow (fmt, test, validate-envs including `shared`). Check it under that repo's Actions tab.

### Step 5. Push the app repo and open a PR

Current state: you're on `manual-build-guides`, which is 6 commits ahead of GitHub's `master` (`3939acf`) and has never been pushed. The PR will contain those 6 commits plus this week's commit.

```bash
cd ~/projects/springboot_aws_compute_databases
git status --short
```

What to commit this week:

| Path | Commit? |
|---|---|
| `.github/workflows/` (5 files) | yes |
| `*/Dockerfile`, `*/build.gradle`, `*/.dockerignore` (stage B) | yes |
| `payment-service/.../PaymentProcessFlowTests.java` (stage A) | yes |
| `jaka_infrastructure` (shows as `m`) | yes, after step 4. It's a pointer to an infra commit, not files; committing it records which infra commit goes with this app commit |
| `.vscode/settings.json` | your choice; it's editor config, not part of the week |
| `TUAN-*.md` | your choice |

```bash
git add .github/workflows \
  */*/Dockerfile */*/build.gradle */*/.dockerignore \
  payment-service/payment-service/src/test \
  jaka_infrastructure
git commit -m "CI per service: Testcontainers tests, image build, push to ECR via OIDC"
git push -u origin manual-build-guides
gh pr create --base master --head manual-build-guides \
  --title "Week 13: CI per service" \
  --body "Per-service GitHub Actions workflows: test with Testcontainers, build image, push to ECR via OIDC on master."
```

### Step 6. Watch the PR run

```bash
gh run list --limit 10
gh run watch
```

`gh run watch` asks which run to follow. In the browser: the PR's **Checks** tab, or the repo's **Actions** tab.

What you should see:

1. **4 workflows** start: `order-ci`, `payment-ci`, `inventory-ci`, `notification-ci`. `build-service` appears in the sidebar but has no runs of its own.
2. Each run has **one job**, named `build / build` (caller job `build` → called job `build`).
3. Steps that **run**: checkout, setup-java, setup-gradle, `Run tests` (Testcontainers pulls `postgres:16`, `apache/kafka-native:4.3.1`, `redis:7-alpine`, `testcontainers/ryuk`), `setup-buildx`, `Build image (PR only)`.
4. Steps **skipped** (grey, "This step was skipped"): `Configure AWS credentials`, `Login to ECR`, `Check image already exists`, `Build and push image`. That's correct for a PR.
5. Time: a first run takes several minutes per service (no Gradle or Docker cache yet). Later runs are faster.

### Step 7. Merge ⏸ AWS

Only when all 4 PR runs are green:

```bash
gh pr merge --merge
```

`--merge` creates a merge commit on `master`. That commit is the push event that starts the next run.

### Step 8. Watch the master run ⏸ AWS

```bash
gh run list --branch master --limit 10
gh run watch
```

The same 4 workflows start again, now with the AWS steps:

| Step | Expected log |
|---|---|
| `Configure AWS credentials` | green; the log mentions assuming `jaka-mod-gha-ci` via OIDC |
| `Login to ECR` | green; the log names your registry `<account>.dkr.ecr.ap-southeast-1.amazonaws.com` |
| `Check image already exists` | nothing printed (first push → `skip=false`) |
| `Build and push image` | layers pushed, then a `sha256:` digest |
| `Build image (PR only)` | skipped |

### Step 9. Verify in ECR ⏸ AWS

```bash
for svc in order payment inventory notification; do
  echo "== $svc"
  aws ecr describe-images --repository-name "jaka-mod-$svc-service" \
    --region ap-southeast-1 \
    --query 'sort_by(imageDetails,&imagePushedAt)[-1].imageTags'
done
git -C ~/projects/springboot_aws_compute_databases rev-parse origin/master
```

Every repo's newest tag must be `sha-` + the hash printed by the last command (fetch first if your local `origin/master` is behind: `git fetch origin`).

### If something fails

| Symptom | Cause | Fix |
|---|---|---|
| A workflow doesn't start at all | its `paths` don't match (typo in the folder name) | the `grep` from step 1 |
| `Unable to resolve action` | misspelt action name in `uses:` (actionlint doesn't check names) | compare with D2 |
| "The nested job 'build' is requesting 'id-token: write', but is only allowed 'id-token: none'" | the caller's `build` job lacks `id-token: write` | `permissions` in the `*-ci.yml` job (D4) |
| `./gradlew: Permission denied` | `gradlew` is `100644` in git | A6 |
| Tests fail only in CI | read the `test-report-<service>` artifact (run page → Artifacts) | same as a local failure; reproduce with `./gradlew test` |
| `Could not assume role with OIDC: Not authorized to perform sts:AssumeRoleWithWebIdentity` | trust policy mismatch: `aud` must be `sts.amazonaws.com`, `sub` must be `repo:ghtaidt9/springboot_aws_compute_databases:ref:refs/heads/master` | `github-oidc.tf`, then `terraform apply` |
| `Configure AWS credentials` fails with a "could not load credentials" kind of error, and the step's inputs in the log show an empty `role-to-assume` | `AWS_CI_ROLE_ARN` not set, or set under another name | step 3, `gh variable list` |
| `name unknown` / `repository … does not exist` on push | `envs/shared` not applied, or `REPO` name wrong | step 2; `REPO` is `jaka-mod-<service>-service` |
| `AccessDenied` on `ecr:InitiateLayerUpload` / `PutImage` | `gha_ci_ecr` policy missing or its `Resource` isn't the 4 repo ARNs | `github-oidc.tf` part 3 |
| `tag invalid … already exists … immutable` | the exists check didn't detect the image (e.g. a broken redirect in its `if`) | D2's check step, line by line |

### About cleanliness

None of these steps leaves half-finished state behind:
- A failed run changes nothing in AWS: an image is only stored when its push completes.
- Fixing a workflow and pushing again simply starts a new run; old runs stay as history.
- To retry the same commit, use **Re-run all jobs**: the exists check makes it safe.

## D6. End-of-week self-check

If you can do these 4 things, you've got week 13:

1. Edit one comment line in `notification-service` → push to master → only `notification-ci` runs; the other 3 workflows don't start.
2. Re-run that run (**Re-run all jobs**) → the push step says "already exists, skipping push" and stays green.
3. Deliberately break a test (`assertThat(...).isEqualTo("wrong")`) in order-service in a PR → `order-ci` goes red, and the `test-report-order` artifact downloads and is readable.
4. Answer without looking at the doc: why is `cancel-in-progress` only on for PRs? Why must the **caller** job grant `id-token: write`? Why must tags be IMMUTABLE? Why don't path-filtered workflows mix well with required checks?

---

## Next week

The image is in ECR, but nobody deploys it yet. [WEEK 14](TUAN-14-CHI-TIET.md): give GitHub Actions a **second** role used only for deploying, access EKS through an access entry, and have the deploy job run `helm upgrade --install` with automatic rollback.
