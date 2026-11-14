# WEEK 16 — Logs (Loki), Tracing (Tempo), Alerting + an overview dashboard

> Month 4, week 4 of 4. Series: [TUAN-13](TUAN-13-CHI-TIET.md) → [TUAN-14](TUAN-14-CHI-TIET.md) → [TUAN-15](TUAN-15-CHI-TIET.md) → **TUAN-16**.

## TL;DR

Complete the three pillars of observability and connect them:

```
                         ┌────────────── Grafana ──────────────┐
                         │  "Jaka Services" dashboard + alerts │
                         └──┬───────────────┬──────────────┬───┘
             METRICS        │     LOGS      │    TRACES    │
         Prometheus (W15)   │     Loki ◀────┤    Tempo ◀───┤
                            │       ▲       │      ▲       │
                            │     Alloy     │  OTLP/HTTP   │
                            │ (reads pod    │ (app sends   │
                            │  logs)        │  itself)     │
                  ┌─────────┴───────────────┴──────────────┴──────────┐
                  │              4 services in namespace dev          │
                  └───────────────────────────────────────────────────┘
         + Alertmanager ──(SMTP)──▶ Mailpit (already there since month 3) ──▶ mailbox UI
```

This week's tasks:

1. **Loki** stores logs and **Alloy** (Grafana's agent) reads every pod's logs and ships them: search logs for all 4 services in one place, filter by `correlationId`.
2. **Tempo** stores traces; the apps send them via `spring-boot-starter-opentelemetry`. See how long a request spends at each hop, order → Kafka → inventory.
3. Connect the three pillars in Grafana: jump from a log line to its trace, and from a trace back to the logs.
4. **PrometheusRule** with 4 alerts (service down / high error rate / high latency / outbox backlog) → Alertmanager → mail into Mailpit.
5. The "Jaka Services" dashboard for all 4 services: month 4's required output.

Everything this week lives in the `jaka_infrastructure` repo (except the tracing part inside the apps). The stack deliberately runs in a **minimal** form (single binary, no HA, emptyDir storage): the right size for a learning environment, and small enough that you can understand 100% of every config line.

---

## Why all three pillars (from your own system)

A real scenario from month 3: an order fails because the saga rolls back. With each level of tooling:

- **Metrics only (W15):** you see the error rate rise, but *which request*, and *why*? No idea.
- **Plus logs:** until now you had to `kubectl logs` each of 4 pods and match `cid=` by eye. Loki brings it all into one place: one query returns every log line for that exact request, across all 4 services.
- **Plus traces:** logs say "what happened"; a trace says "**how long, and where**": a 2-second request, is the 1.9s in the DB, in Kafka, or in code? The trace draws a timeline of every hop.

And **alerting**, so you don't have to stare at the dashboard: the system tells you when a threshold is crossed.

> **Trade-off, stated up front:** 100% trace sampling + reading every pod's logs adds real load (CPU/RAM/disk). In a learning environment, sample 100% so you *see everything*; in production people lower `probability` to 5–10%. I'll point out each place where that knob exists.

---

# STAGE A — Loki + Alloy (centralised logs)

## A1. The model

Loki is "Prometheus for logs": it stores log lines by **label** (namespace, pod, app...) and only indexes the labels, not the content, so it's much cheaper and lighter than Elasticsearch. You query it with **LogQL** (the same family of syntax as PromQL).

Loki doesn't fetch logs itself; it needs an agent. We use **Alloy** (Grafana's unified agent, which replaces the deprecated Promtail): it runs in the cluster, asks the Kubernetes API "which pods exist", reads their logs, attaches labels, and pushes them to Loki.

## A2. Loki — `k8s/observability/loki.yml` (infra repo)

Deployed as a **single binary** with plain manifests (no chart, so you can understand every line). The official `grafana/loki` chart (7.6.0) defaults to `deploymentMode: SimpleScalable` with `storage.type: s3`: separate read/write/backend pods plus object storage, which is oversized for this week's goal.

This exact config was started locally with `grafana/loki:3.6.20`: `/ready` returned 200 and a push to `/loki/api/v1/push` was accepted (HTTP 204).

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: loki-config
  namespace: monitoring
data:
  loki.yml: |
    auth_enabled: false

    server:
      http_listen_port: 3100

    common:
      path_prefix: /loki
      replication_factor: 1
      ring:
        kvstore:
          store: inmemory
      storage:
        filesystem:
          chunks_directory: /loki/chunks
          rules_directory: /loki/rules

    schema_config:
      configs:
        - from: "2024-04-01"
          store: tsdb
          object_store: filesystem
          schema: v13
          index:
            prefix: index_
            period: 24h
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: loki
  namespace: monitoring
spec:
  replicas: 1
  selector:
    matchLabels:
      app: loki
  template:
    metadata:
      labels:
        app: loki
    spec:
      containers:
        - name: loki
          image: grafana/loki:3.6.20
          args: ["-config.file=/etc/loki/loki.yml"]
          ports:
            - containerPort: 3100
          volumeMounts:
            - name: config
              mountPath: /etc/loki
            - name: data
              mountPath: /loki
      volumes:
        - name: config
          configMap:
            name: loki-config
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: loki
  namespace: monitoring
spec:
  selector:
    app: loki
  ports:
    - name: http
      port: 3100
      targetPort: 3100
```

The Loki config (the ConfigMap part):

- `auth_enabled: false`: multi-tenant Loki has its own auth layer; inside a closed cluster, turn it off.
- `common`: a block that "fills in defaults for every component": `replication_factor: 1` (one copy, no HA), `ring.kvstore: inmemory` (components find each other in memory, because they're one process), `filesystem` storage (writes to local disk instead of S3).
- `schema_config`: **required**; declares which storage format is used from which date. `tsdb` + `v13` is the current pair for Loki 3.x; `from` is any past date before you start writing.
- `emptyDir: {}`: a volume that lives with the pod: delete the pod and old logs are gone. Acceptable for a learning environment (logs only matter during a debugging session); production would use S3 + retention.

## A3. Alloy — install with the chart, configure the pipeline

Alloy is configured in its own language (similar to Terraform's HCL, which gives you a head start): **component** blocks connected into a pipeline through the `forward_to` argument.

Create `k8s/observability/alloy-values.yml`:

```yaml
controller:
  type: deployment
  replicas: 1

alloy:
  configMap:
    content: |
      // 1) Ask the k8s API: which pods exist in these 2 namespaces
      discovery.kubernetes "pods" {
        role = "pod"
        namespaces {
          names = ["dev", "kafka"]
        }
      }

      // 2) Turn k8s metadata into Loki labels
      discovery.relabel "pods" {
        targets = discovery.kubernetes.pods.targets

        rule {
          source_labels = ["__meta_kubernetes_namespace"]
          target_label  = "namespace"
        }
        rule {
          source_labels = ["__meta_kubernetes_pod_name"]
          target_label  = "pod"
        }
        rule {
          source_labels = ["__meta_kubernetes_pod_container_name"]
          target_label  = "container"
        }
        rule {
          source_labels = ["__meta_kubernetes_pod_label_app"]
          target_label  = "app"
        }
      }

      // 3) Read those pods' logs through the k8s API
      loki.source.kubernetes "pods" {
        targets    = discovery.relabel.pods.output
        forward_to = [loki.process.app.receiver]
      }

      // 4) Processing: merge multi-line Java stack traces into one record
      loki.process "app" {
        stage.match {
          selector = "{container=\"app\"}"
          stage.multiline {
            firstline = "^\\d{4}-\\d{2}-\\d{2}"
          }
        }
        forward_to = [loki.write.default.receiver]
      }

      // 5) Push to Loki
      loki.write "default" {
        endpoint {
          url = "http://loki.monitoring.svc:3100/loki/api/v1/push"
        }
      }
```

The pipeline:

- `controller.type: deployment, replicas: 1`: by default the chart runs Alloy as a DaemonSet (one agent per node, reading log files on the node). We run **one** Deployment using `loki.source.kubernetes`, which reads logs through the Kubernetes **API** (like `kubectl logs`) and needs no hostPath mounts; simpler for a small cluster. The chart's ClusterRole already allows `get/list/watch` on `pods` and `pods/log` (checked with `helm template` of chart 1.15.0), so no extra RBAC is needed.
- This config was checked with Alloy v1.21.3 (the chart's `appVersion`): `alloy fmt` accepts it, and `alloy run` builds every component. Locally, the only errors are "not running inside Kubernetes", which is expected.
- `discovery.kubernetes` with role `pod`: the "target" source: every pod in `dev` and `kafka` (Kafka's logs are included for the drill in stage D).
- `discovery.relabel`: `__meta_kubernetes_*` meta labels don't become Loki labels by themselves; each `rule` copies one meta label into a real label: `namespace`, `pod`, `container`, `app` (from the pod's `app` label: `templates/deployment.yml` sets it to the release name, e.g. `order-service`). Keep **only** a few low-cardinality labels; never label by log content (user IDs...), because Loki indexes by label combination, and a label explosion is a Loki explosion.
- `stage.match` + `selector {container="app"}`: apply the processing step only to containers named `app` (the container name in your chart; see `deployment.yml`). Kafka/controller logs have other formats and are left alone.
- `stage.multiline` + `firstline "^\d{4}-\d{2}-\d{2}"`: a line that does **not** start with a date `2026-...` (i.e. the `at com.jaka...` lines of a stack trace) is appended to the previous record → the whole stack trace is one log record. Without it, one exception breaks into 40 separate "logs". This works because your services override only `logging.pattern.level` (`"%5p [cid=%X{correlationId:-}]"`), not the whole console pattern, and Boot's default console pattern starts with the date.

Add to `scripts/observability-up.sh` (after the kps part):

```bash
# ----- Loki + Alloy + Tempo -----
kubectl apply -f "$ROOT/k8s/observability/loki.yml"
kubectl apply -f "$ROOT/k8s/observability/tempo.yml"

helm repo add grafana https://grafana.github.io/helm-charts --force-update
helm upgrade --install alloy grafana/alloy \
  --version 1.15.0 \
  -n monitoring \
  -f "$ROOT/k8s/observability/alloy-values.yml" \
  --wait --timeout 5m
```

## A4. LogQL — a taste of the power

(After connecting the datasource in stage C; try it first in Grafana Explore → Loki.)

```logql
# All logs in namespace dev
{namespace="dev"}

# Error logs of order-service
{namespace="dev", app="order-service"} |= "ERROR"

# WHAT YOU COULDN'T DO BEFORE: every log line of ONE request, across all 4 services
{namespace="dev"} |= "cid=4f9d2c81"

# Errors per minute per app (syntax closely related to PromQL)
sum by (app) (count_over_time({namespace="dev"} |= "ERROR" [1m]))
```

The third query is a direct answer to the month-3 pain: your `CorrelationIdFilter` + the `logging.pattern.level` value `"%5p [cid=%X{correlationId:-}]"` (in all 4 `application.yaml` files) write the correlationId into every log line, and Loki now turns it into a lookup key for the whole system.

---

# STAGE B — Tempo + tracing in the apps

## B1. The concepts in 60 seconds

- **Trace**: the whole journey of one request, identified by a `traceId`.
- **Span**: one hop of the journey (handling HTTP, a DB query, a Kafka send...), with start/end times + a parent span, forming a tree that's drawn as a waterfall timeline.
- **Context propagation**: the secret to crossing services: services calling each other must *pass along* the traceId (the W3C `traceparent` HTTP header, or a Kafka header). The libraries do it; that's what we're about to turn on.
- **OTLP**: OpenTelemetry's standard protocol for sending traces; the app sends OTLP/HTTP to Tempo on port 4318.

## B2. Tempo — `k8s/observability/tempo.yml` (infra repo)

Same minimal style as Loki. Use **Tempo 2.9.5**, the newest 2.x tag on Docker Hub. Tempo 3.x also exists (3.1.1 at the time of writing), but this guide's config was only tested against 2.9.5. Locally, with exactly the config below, `/ready` returned 200 and an OTLP/HTTP POST to `:4318/v1/traces` returned 200. If you move to 3.x, check its upgrade notes before reusing this config.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: tempo-config
  namespace: monitoring
data:
  tempo.yml: |
    server:
      http_listen_port: 3200

    distributor:
      receivers:
        otlp:
          protocols:
            http:
              endpoint: "0.0.0.0:4318"
            grpc:
              endpoint: "0.0.0.0:4317"

    storage:
      trace:
        backend: local
        local:
          path: /var/tempo/blocks
        wal:
          path: /var/tempo/wal

    compactor:
      compaction:
        block_retention: 24h
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tempo
  namespace: monitoring
spec:
  replicas: 1
  selector:
    matchLabels:
      app: tempo
  template:
    metadata:
      labels:
        app: tempo
    spec:
      containers:
        - name: tempo
          image: grafana/tempo:2.9.5
          args: ["-config.file=/etc/tempo/tempo.yml"]
          ports:
            - containerPort: 3200
            - containerPort: 4317
            - containerPort: 4318
          volumeMounts:
            - name: config
              mountPath: /etc/tempo
            - name: data
              mountPath: /var/tempo
      volumes:
        - name: config
          configMap:
            name: tempo-config
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: tempo
  namespace: monitoring
spec:
  selector:
    app: tempo
  ports:
    - name: http
      port: 3200
      targetPort: 3200
    - name: otlp-grpc
      port: 4317
      targetPort: 4317
    - name: otlp-http
      port: 4318
      targetPort: 4318
```

Fields worth noting:

- `distributor.receivers.otlp`: opens two "intake doors": OTLP/HTTP on 4318 (the Spring apps use this one) and OTLP/gRPC on 4317. `0.0.0.0` accepts traffic from outside the pod.
- `storage.trace.backend: local` + `wal`: writes trace blocks to local disk; the WAL (write-ahead log) prevents data loss if the process restarts mid-way.
- `block_retention: 24h`: the compactor deletes traces older than 1 day.
- Port 3200 is the **query** port; the Grafana datasource points here.

## B3. Apps: turn on OpenTelemetry (app repo — all 4 services)

**`build.gradle`:**

```groovy
implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'
```

Boot 4's official starter. Its 4.1.0 POM pulls in `spring-boot-micrometer-tracing-opentelemetry`, `spring-boot-opentelemetry`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` and `micrometer-registry-otlp`. Boot creates spans for incoming/outgoing HTTP requests and (with the flags below) Kafka send/receive, and **propagates context** through headers automatically.

**Not traced: JDBC.** `spring-boot-jdbc` 4.1.0 contains no observation/tracing support, so SQL queries don't get spans by themselves. Adding them needs an extra library (for example `net.ttddyy.observation:datasource-micrometer-spring-boot`). That's not part of this week.

**`application.yaml`:**

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    metrics:
      export:
        enabled: false

spring:
  kafka:
    template:
      observation-enabled: true
    listener:
      observation-enabled: true

logging:
  pattern:
    correlation: "[trace=%X{traceId:-}] "
```

Line by line:

All 5 property names below exist in Boot 4.1.0's configuration metadata (checked in the jars).

- `sampling.probability: 1.0`: record 100% of requests (the default is only 0.1 = 10%; with the default, while learning you'd see 1 trace per 10 requests and think it's broken). This is the production knob mentioned at the top.
- `management.otlp.metrics.export.enabled: false`: the OTel starter also pulls in an OTLP metrics registry (`micrometer-registry-otlp`), and this property defaults to `true`. If you don't turn it off, the app tries to **push metrics** to an endpoint that doesn't exist and logs errors. Our metrics already go the Prometheus-pull way, so turn the push path off.
- `spring.kafka.*.observation-enabled: true`: **the two lines that pay off most**. By default (false) the trace *breaks* at Kafka: the producer is the end of the trace, and the consumer starts a brand-new one. Turned on, Spring Kafka creates spans for send/receive and puts the traceId into a **Kafka header** → one continuous trace order → Kafka → inventory.
- `logging.pattern.correlation`: Boot's default console pattern has a slot for this string, so it appears in every log line: `[trace=<traceId>]` while a trace is active, `[trace=]` otherwise (the `:-` gives an empty default). This is the **logs↔traces bridge**: stage C teaches Grafana to recognise this string. (Your existing level pattern `cid=%X{correlationId}` stays: two systems side by side; `cid` is the business one, `trace` is the observability one.)

**Env for EKS**: the app needs Tempo's address. In `eks-up.sh` step 3b (the infra ConfigMap loop from week 14), add this line after the `[[ $svc == order || ... ]]` line, so it applies to all 4 services:

```bash
    args+=(--from-literal "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://tempo.monitoring:4318/v1/traces")
```

- It's the property `management.opentelemetry.tracing.export.otlp.endpoint` as an env var. Boot 4 renamed it: the old `management.otlp.tracing.endpoint` is deprecated at level `error` with this one as the replacement, so the old name no longer works.
- It isn't set in `application.yaml` because there's no Tempo locally. When it's not set, no exporter is created (Boot only builds the OTLP connection details under `@ConditionalOnProperty` on this endpoint) and the app runs normally.

> Remember the week-14 rule: a pod only reads the ConfigMap at startup. After changing the ConfigMap, you must `kubectl rollout restart`; if you build the cluster from scratch in the `eks-up.sh` → ArgoCD order, it's correct automatically.

## B4. Your first trace — and a lesson about the outbox

After deploying (push code → CI → ArgoCD as in week 15), send one create-order request, then open Grafana → Explore → Tempo and search for service `order-service`.

You'll see a trace with the `POST /api/orders` HTTP server span, **and the trace stops there** (there's no SQL span; see "Not traced: JDBC" in B3). The Kafka send span lives in a *different*, orphaned trace. Why? Your own outbox architecture: the request only **writes to the DB** and returns; one second later `OutboxRelay` (`@Scheduled`) reads the outbox table and sends to Kafka, **on another thread, outside the request's trace**. Context propagation breaks at the "goes through the database" point.

This isn't a config bug. It's an **architectural consequence**, and the most valuable tracing lesson of the week: automatic tracing only follows *calls*, not *data at rest*. Two notes:

1. Your `correlationId` **survives** the outbox (`OutboxRelay` adds it as a Kafka header, `Correlation.KAFKA_HEADER`; you built that in month 3), so when you need to join the two halves of a trace, a Loki search on `cid=` returns both.
2. **Homework (advanced, optional):** make the trace continuous by storing an extra `traceparent` column in the outbox table at write time (`Span.current().getSpanContext()`), then in the relay create a span with a **link** to that context. If you can do this, you understand context propagation better than 90% of tracing users.

Traces between *directly called* hops (HTTP between services, Kafka send → Kafka listener) are continuous: look at the timeline when inventory consumes an event: the receive span and how long processing took. The DB work inside it shows up as time within that span, not as separate SQL spans (no JDBC instrumentation).

---

# STAGE C — Connect the three pillars in Grafana

## C1. Declare 2 datasources + cross-links (edit `kps-values.yml`)

Add to `k8s/observability/kps-values.yml` (under the existing `grafana:` key):

```yaml
grafana:
  adminPassword: ""
  additionalDataSources:
    - name: Loki
      type: loki
      uid: loki
      url: http://loki.monitoring.svc:3100
      jsonData:
        derivedFields:
          - name: TraceID
            matcherRegex: 'trace=(\w+)'
            datasourceUid: tempo
            url: '$${__value.raw}'

    - name: Tempo
      type: tempo
      uid: tempo
      url: http://tempo.monitoring.svc:3200
      jsonData:
        tracesToLogsV2:
          datasourceUid: loki
          filterByTraceID: true
          spanStartTimeShift: '-5m'
          spanEndTimeShift: '5m'
          tags:
            - key: service.name
              value: app
```

Then re-run:

```bash
./scripts/observability-up.sh
```

Explanation:

- A fixed `uid` (`loki`, `tempo`): so the two datasources can point at each other and the dashboard JSON (stage E) can reference them reliably instead of depending on a random uid.
- `derivedFields` (Loki → Tempo): Grafana scans each log line with the regex `trace=(\w+)`, which matches the string `logging.pattern.correlation` prints. `\w+` needs at least one character, so the empty `[trace=]` doesn't match. On a match, a **"TraceID" button** appears next to the log line → click it to open that trace in Tempo. `${__value.raw}` = the captured group. It's written `$${__value.raw}` because Grafana **expands environment variables** (`$VAR` / `${VAR}`) in datasource provisioning files, and `$$` is its escape for a literal `$`. Helm passes the value through unchanged: `helm template` shows `url: $${__value.raw}` in the rendered datasource ConfigMap.
- `tracesToLogsV2` (Tempo → Loki, the other direction): while looking at a span, click the logs icon → Grafana builds a LogQL query: filter by tag. The span's `service.name` maps to Loki's `app` label: OTel's `service.name` is `spring.application.name` (`order-service`, ...), and the pod label `app` is the Helm release name (also `order-service`), so the values match. The query covers the span's time range **widened by ±5 minutes** (`spanStartTimeShift`/`spanEndTimeShift`, because logs can be a few seconds off from the span), and `filterByTraceID: true` adds `|= "<traceId>"` to hit exactly the right lines.

## C2. The new debugging loop — try it end to end

Scenario: metrics show errors → find the cause in 4 clicks, without typing a single `kubectl`:

1. The dashboard shows order-service's error rate jumping → click the panel → **Explore**.
2. Switch the datasource to **Loki**, query `{namespace="dev", app="order-service"} |= "ERROR"` → see the stack trace (one tidy record thanks to multiline).
3. Next to the log line there's a **TraceID** button → click → the trace opens in **Tempo**: see which span is red and which hop is slow.
4. From the span → click over to the logs of *the service on the other side* (tracesToLogs) → you get inventory's logs in exactly the right time window.

Metrics → Logs → Traces → Logs. That's what the roadmap checkpoint means by "observability like a real enterprise environment".

---

# STAGE D — Alerting: Prometheus rule → Alertmanager → Mailpit

## D1. The path of an alert

```
Prometheus: re-evaluates each rule (a PromQL expression) every 15-30s
   │  expression true continuously for "for:" → alert becomes firing
   ▼
Alertmanager: groups, de-duplicates, routes by label
   │
   ▼ (SMTP)
Mailpit (namespace dev, there since month 3) → mail UI
```

Mailpit is the fake SMTP server you already use for notification-service; reuse it as the "on-call mailbox".

## D2. PrometheusRule — `k8s/observability/alert-rules.yml` (infra repo)

PrometheusRule is a CRD: you write rules as Kubernetes YAML and the Prometheus Operator loads them into Prometheus (thanks to `ruleSelectorNilUsesHelmValues: false` from last week; now you know what the third line was for).

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: jaka-services
  namespace: monitoring
spec:
  groups:
    - name: jaka-services
      rules:
        - alert: JakaServiceUnavailable
          expr: kube_deployment_status_replicas_available{namespace="dev", deployment=~".*-service"} == 0
          for: 2m
          labels:
            severity: critical
          annotations:
            summary: "{{ $labels.deployment }} has no live replicas"
            description: "Deployment {{ $labels.deployment }} (ns dev) has had 0 available pods for 2 minutes."

        - alert: JakaHighErrorRate
          expr: |
            100 * sum by (service) (rate(http_server_requests_seconds_count{namespace="dev", status=~"5.."}[5m]))
                / sum by (service) (rate(http_server_requests_seconds_count{namespace="dev"}[5m])) > 5
          for: 5m
          labels:
            severity: critical
          annotations:
            summary: "{{ $labels.service }} 5xx errors > 5%"
            description: "The 5xx rate of {{ $labels.service }} is {{ $value | printf \"%.1f\" }}% over 5 minutes."

        - alert: JakaHighLatencyP95
          expr: |
            histogram_quantile(0.95,
              sum by (service, le) (rate(http_server_requests_seconds_bucket{namespace="dev"}[5m]))) > 0.5
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "{{ $labels.service }} p95 > 500ms"
            description: "The p95 latency of {{ $labels.service }} has been {{ $value | printf \"%.2f\" }}s for 10 minutes."

        - alert: JakaOutboxBacklog
          expr: outbox_pending{namespace="dev"} > 0
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "The outbox of {{ $labels.service }} is backing up"
            description: "outbox_pending = {{ $value }} for 5 minutes — is the relay/Kafka in trouble?"
```

The structure of a rule:

- `expr`: a PromQL query; exactly the queries you ran by hand in week 15, plus `> threshold`. Writing an alert = take a dashboard query + a threshold, which is why you *must* be fluent in Explore before writing alerts.
- `for`: the expression must be true **continuously** for this long before firing. It prevents "flapping": a one-second error shouldn't wake you at 3 a.m. The duration depends on severity: fully down 2m, latency 10m.
- `labels.severity`: a label for Alertmanager routing (critical goes the urgent route, warning the normal one; here both go to Mailpit, but the structure is the real-world standard).
- `annotations` + `{{ $labels.x }}`/`{{ $value }}`: the notification text template, filled in when firing.
- Rule 1 uses `kube_deployment_status_replicas_available`, which comes from **kube-state-metrics** (part of kps), not from the app: if the app is completely dead, nobody is exposing `/actuator/prometheus` to measure, so you have to measure from outside. Rule 4 (`outbox_pending`, the gauge you wrote in week 15) is a *business* alert: it catches events not flowing even when every piece of infrastructure looks green.

```bash
kubectl apply -f k8s/observability/alert-rules.yml
```

Also add it to `scripts/observability-up.sh`, after the Loki/Tempo/Alloy part, so the next cluster build gets the rules. Inside that script, use `$ROOT` like its other paths (the script doesn't `cd`, so a bare relative path would depend on where you start it):

```bash
kubectl apply -f "$ROOT/k8s/observability/alert-rules.yml"
```

## D3. Alertmanager → Mailpit (edit `kps-values.yml`)

Add a block next to `grafana:`/`prometheus:`:

```yaml
alertmanager:
  config:
    global:
      smtp_smarthost: 'mailpit.dev.svc.cluster.local:1025'
      smtp_from: 'alertmanager@jaka.local'
      smtp_require_tls: false
    route:
      receiver: mailpit
      group_by: ['alertname', 'service']
      group_wait: 30s
      group_interval: 5m
      repeat_interval: 4h
      routes:
        - receiver: 'null'
          matchers:
            - alertname =~ "Watchdog|InfoInhibitor"
    receivers:
      - name: 'null'
      - name: mailpit
        email_configs:
          - to: 'oncall@jaka.local'
            send_resolved: true
```

Explanation:

- `smtp_smarthost`: the Mailpit Service lives in namespace `dev` (the existing file `k8s/notification-service/mailpit.yml` declares `namespace: dev` and ports `smtp` 1025 / `ui` 8025), so it's called across namespaces with its full name `mailpit.dev.svc.cluster.local`, SMTP port 1025. `smtp_require_tls: false` because Mailpit speaks plain SMTP.
- `route`: the routing tree. `group_by` bundles alerts with the same name+service into **one** mail (10 pods dying = 1 mail, not 10); `group_wait: 30s` waits to collect sibling alerts before the first mail; `repeat_interval: 4h` reminds you every 4 hours while an alert is unresolved.
- The child route sends `Watchdog` (an "always firing" alert proving the alert pipeline is alive) and `InfoInhibitor` (a technical alert of the kps bundle) to the `'null'` receiver, which sends nowhere. Without it, the mailbox fills with "everything is still fine" noise.
- `send_resolved: true`: when the incident is over, send another "[RESOLVED]" mail, so you know it's done without opening the dashboard.

Re-run `./scripts/observability-up.sh`, then open the mailbox:

```bash
kubectl -n dev port-forward svc/mailpit 8025:8025
```

`http://localhost:8025`: keep this tab open for the drills.

## D4. Drills — trigger each alert on purpose

**Drill 1 — `JakaOutboxBacklog` (reusing the month-3 scenario):** take Kafka down, then create orders.

How Kafka runs here: the Strimzi operator (`deploy/strimzi-cluster-operator` in namespace `kafka`, installed by `eks-up.sh` step 4) manages the `Kafka` resource `jaka` and its one `KafkaNodePool` `dual-role` (`replicas: 1`, roles controller + broker) from `k8s/kafka/kafka-single-node.yml`. The broker pod is `jaka-dual-role-0`. There is no StatefulSet: Strimzi creates the pod itself, so stop the operator first, or it recreates the pod right away:

```bash
kubectl -n kafka scale deploy strimzi-cluster-operator --replicas=0
kubectl -n kafka delete pod jaka-dual-role-0
kubectl -n kafka get pods     # jaka-dual-role-0 is gone and stays gone
```

Create a few orders through the gateway (`POST /api/orders` takes a `CreateOrderRequest` JSON):

```bash
GATEWAY_URL="http://$(kubectl get gateway web -n dev -o jsonpath='{.status.addresses[0].value}')"
for i in 1 2 3; do
  curl -s -X POST "$GATEWAY_URL/api/orders" -H 'Content-Type: application/json' \
    -d '{"customerName":"alice","totalAmount":10.00,"currency":"USD","productSku":"TEST-001","quantity":1}'
  echo
done
```

OutboxRelay can't send → `outbox_pending` rises → 5 minutes later a mail arrives in Mailpit. While you wait, watch the alert go from pending (yellow) to firing (red) in the Prometheus UI (port-forward 9090 → Alerts).

Restore Kafka by starting the operator again. It sees the missing pod and recreates it; the data is still on its PVC:

```bash
kubectl -n kafka scale deploy strimzi-cluster-operator --replicas=1
kubectl wait kafka/jaka -n kafka --for=condition=Ready --timeout=10m
```

The relay drains the backlog → `outbox_pending` returns to 0 → a `[RESOLVED]` mail. **The full lifecycle of an incident, visible with your own eyes.**

**Drill 2 — `JakaHighErrorRate`:** inject a broken config through the system's own mechanism (the week-14 infra ConfigMap):

```bash
kubectl -n dev patch configmap order-service-infra \
  --type merge -p '{"data":{"SPRING_DATA_REDIS_HOST":"does-not-exist"}}'
kubectl rollout restart deployment order-service -n dev
# send continuous traffic:
GATEWAY_URL="http://$(kubectl get gateway web -n dev -o jsonpath='{.status.addresses[0].value}')"
for i in $(seq 1 300); do curl -s "$GATEWAY_URL/api/orders" > /dev/null; sleep 1; done
```

Why this produces 500s, from the code:
- The pod still starts and becomes ready. `ConnectivityStartupLogger` catches the failed Redis PING and only logs `Redis connectivity check: FAILED`, and the readiness group is `readinessState,db` (set in the chart), which doesn't include Redis.
- `GET /api/orders` → `OrderService.getAllOrders()` is `@Cacheable("orders")`. `RedisCacheConfig` sets no `CacheErrorHandler`, so the Redis connection error goes up to the controller → HTTP 500.

So the error rate passes 5% → 5 minutes later it fires. **Clean up after the drill** (restore the old value + restart; note this ConfigMap is created by `eks-up.sh`, not managed by ArgoCD, so selfHeal will *not* fix it for you). The original value is dev-eks's `redis_primary_endpoint` output, the same one `eks-up.sh` reads:

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
REDIS_HOST=$(terraform -chdir=envs/dev-eks output -raw redis_primary_endpoint)
kubectl -n dev patch configmap order-service-infra \
  --type merge -p "{\"data\":{\"SPRING_DATA_REDIS_HOST\":\"$REDIS_HOST\"}}"
kubectl rollout restart deployment order-service -n dev
```

**Drill 3 — impatient?** Temporarily lower the threshold to see a mail right away: in `alert-rules.yml` change `for: 5m` to `for: 30s`, apply, run the drill, then **change it back and apply again**. The rules are a file in the infra repo, so every experiment leaves a git trail; when you're done, `git diff` must be clean.

---

# STAGE E — The "Jaka Services" dashboard (month 4's output)

## E1. Loading dashboards through a ConfigMap (sidecar)

The kps Grafana runs a **sidecar** that watches every ConfigMap in the cluster labelled `grafana_dashboard: "1"` and loads the JSON inside as a dashboard. (The rendered chart 93.2.0 sets the sidecar's `LABEL=grafana_dashboard`, `LABEL_VALUE=1`, `NAMESPACE=ALL`.) So the dashboard is also **a file in git**, true to the GitOps spirit: rebuild the cluster and the dashboard comes back by itself, no clicking to recreate it.

The *practical* way to make a dashboard (don't write JSON by hand from scratch):

1. In the Grafana UI, create a new dashboard and add panels with the week-15 PromQL queries + this week's LogQL.
2. Dashboard settings → **JSON Model** → copy.
3. Paste it into the ConfigMap and commit.

## E2. Dashboard skeleton + suggested panels

Create `k8s/observability/dashboard-jaka.yml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: dashboard-jaka-services
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  jaka-services.json: |
    { ... the JSON you exported from the UI ... }
```

Build these panels in the dashboard (each panel = a query you've **already** run in week 15 / stage A; the dashboard just presents them again):

| # | Panel | Type | Query (shortened) |
|---|---|---|---|
| 1 | Requests/s per service | Time series | `sum by (service) (rate(http_server_requests_seconds_count{namespace="dev"}[5m]))` |
| 2 | Error rate % | Time series + red threshold at 5% | the 5xx ratio query (W15-D6 query 2) |
| 3 | p95 latency | Time series + threshold at 0.5s | the `histogram_quantile` query |
| 4 | Outbox pending | Stat (big number) | `outbox_pending` |
| 5 | JVM heap | Time series | `jvm_memory_used_bytes{area="heap"}` by service |
| 6 | Hikari connections | Time series | `hikaricp_connections_active` / `hikaricp_connections_max` |
| 7 | Kafka consumer lag | Time series | `kafka_consumer_fetch_manager_records_lag_max{namespace="dev"}` (Kafka client metrics, bound by Boot's `KafkaMetricsAutoConfiguration` through `MicrometerConsumerListener`) |
| 8 | Latest ERROR logs | **Logs panel, Loki datasource** | `{namespace="dev", app=~"$service.*"} \|= "ERROR"` |

Two techniques worth using in the UI:

- **A `service` variable**: Settings → Variables → New; type Query, Prometheus datasource, query `label_values(http_server_requests_seconds_count{namespace="dev"}, service)` → a dropdown at the top of the dashboard, and every panel filters with `service=~"$service"`. One dashboard serves all 4 services.
- Panel 8 mixes **Loki into a metrics dashboard**: one on-call screen: see the error spike in panel 2, glance down at panel 8 for the matching stack trace, click the log line → TraceID → Tempo.

Export the JSON → paste it into the ConfigMap → commit (note: inside the JSON every datasource must reference `"uid": "prometheus"` or `"uid": "loki"`; that's exactly why we fixed the uids) → apply:

```bash
kubectl apply -f k8s/observability/dashboard-jaka.yml
```

About a minute later the sidecar has loaded it: the dashboard appears under Dashboards, **no Grafana restart needed**.

For "rebuild the cluster and the dashboard comes back by itself" (E1) to be true, the apply must also run on every build. Add it to `scripts/observability-up.sh`, next to the alert rules:

```bash
kubectl apply -f "$ROOT/k8s/observability/dashboard-jaka.yml"
```

---

# MONTH 4 CHECKPOINT — self-assessment

From the roadmap: *"CI/CD + observability like a real enterprise environment."* Assess yourself by doing; each item should be doable without the docs:

**CI/CD (weeks 13–15):**

1. Edit one line of code → push → **no further manual steps** → a few minutes later the pod runs the new version. Name every hop: only that service's workflow starts (`paths`) → Testcontainers → buildx → ECR (OIDC, immutable tag) → bot commits the tag → ArgoCD syncs.
2. Answer "which version of payment is running in dev?" with **one git command**, no kubeconfig.
3. Roll back a service with `git revert` and watch ArgoCD bring the old version back.
4. Hand-edit a deployment with `kubectl set env` → selfHeal undoes it → explain why that's a feature.

**Observability (weeks 15–16):**

5. From a correlationId, get every log line of that request across 4 services with one LogQL query.
6. From an error log line, click through to the trace, point out the slow/red hop, and click back to the other service's logs.
7. Take Kafka down → receive the outbox alert mail → restore → receive the resolved mail.
8. Open the Jaka Services dashboard and explain what each panel measures and the query behind it.
9. Explain why the trace breaks at the outbox, and why that's an architectural consequence rather than a config bug.

All 9 done = month 4 complete. On to [month 5 — AI Engineering](roadmap-6-thang.md), with a CI/CD + observability foundation that the month-5 RAG chatbot will be **deployed and monitored with**.

## Teardown at the end of the month

Same order as week 14's teardown (THANG-3 stage 18): delete the controller-created NLB and Kafka's EBS volume while the controllers still run, then destroy.

```bash
cd ~/projects/springboot_aws_compute_databases/jaka_infrastructure
kubectl delete gateway web -n dev
kubectl wait --for=delete svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=web --timeout=5m
kubectl delete ns kafka
terraform -chdir=envs/dev-eks destroy -var-file terraform.tfvars
```

What **stays** (on purpose, costing almost nothing): `envs/shared` (ECR + OIDC role — CI keeps working while the cluster is off), and the infra repo with every chart/values/script/rule, meaning **the system's entire "memory" lives in git**. Next rebuild: apply `shared` (if needed) → apply `dev-eks` → `eks-up.sh` (which calls `observability-up.sh` + the ApplicationSet) → ArgoCD deploys the 4 services at the newest tags in git. Not a single click: that's how you know you've done GitOps right.
