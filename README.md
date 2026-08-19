# Splunk + Spring Boot Distributed Tracing (local)

`service-a` (8080) calls `service-b` (8081). Both log JSON to console and to Splunk HEC, correlated by `traceId`/`spanId` via Micrometer Tracing (Brave). Goal: prove a request crossing two services can be pulled back together in Splunk with one search.

## Concepts

**Splunk HEC** (HTTP Event Collector) — a REST endpoint Splunk exposes so apps push events to it directly over HTTP(S), instead of Splunk tailing a log file off disk. Each app authenticates with a token, POSTs JSON events, they show up in the index almost immediately. Used here so both services ship logs straight from the JVM with no file-forwarder in between.

**Traces and spans** — a **trace** is one logical request as it moves through however many services handle it; everything tagged with the same `traceId` is "this one call," end to end. A **span** is one unit of work inside that trace — service-a handling the request is a span, service-b handling `/hello` is a child span. Without a shared `traceId`, cross-service debugging is manual log-hunting by timestamp and guesswork; tracing stamps every log line touched by the same request with the same ID.

## What we built

```
curl → service-a:8080 (CallBController)
         │  RestTemplateBuilder-built RestTemplate
         │  (propagates trace headers)
         ▼
       service-b:8081 (HelloController)
```

Both services push logs two places:
- **Console** — JSON via `logstash-logback-encoder` (local debugging).
- **Splunk HEC** — via `com.splunk.logging:splunk-library-javalogging` (`HttpEventCollectorLogbackAppender`).

Verified end-to-end: triggering `curl http://localhost:8080/call-b` produces one `traceId` shared across service-a's "Calling service-b" / "Response from service-b" log lines and service-b's "Handling /hello" line, searchable in Splunk with a single SPL query.


## Key configs

**pom.xml** (both services)
- `micrometer-tracing-bridge-brave` — generates traceId/spanId, puts them in MDC.
- `com.splunk.logging:splunk-library-javalogging:1.11.11` — real Splunk HEC appender.

**`TraceIdResponseFilter`** (both services) — a `OncePerRequestFilter` that reads the active span off the injected `Tracer` bean and sets it as `X-Trace-Id`/`X-Span-Id` response headers. Lets you grab the trace ID straight from the curl response instead of digging through logs.

**application.yml**
```yaml
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0   # sample everything, local dev only
```

**logback-spring.xml** — `SPLUNK_HEC` appender:
```xml
<appender name="SPLUNK_HEC" class="com.splunk.logging.HttpEventCollectorLogbackAppender">
    <url>https://localhost:8088</url>        <!-- base address only -->
    <token>${SPLUNK_HEC_TOKEN:-YOUR_HEC_TOKEN_HERE}</token>
    <index>main</index>
    <source>${appName}</source>              <!-- service-a / service-b -->
    <disableCertificateValidation>true</disableCertificateValidation>
    <includeMDC>true</includeMDC>             <!-- ships traceId/spanId -->
    <layout class="ch.qos.logback.classic.PatternLayout">
        <pattern>%msg</pattern>
    </layout>
</appender>
```

## Run it manually

1. Splunk running locally (Docker), HEC token created in Splunk Web (Settings → Data Inputs → HTTP Event Collector), bound to index `main`.
2. Export the token:
   ```bash
   export SPLUNK_HEC_TOKEN=<your-token>
   ```
3. Start service-b:
   ```bash
   cd service-b && ./mvnw -q -DskipTests package
   java -jar target/service-b-0.0.1-SNAPSHOT.jar
   ```
4. Start service-a (separate terminal, same env var exported):
   ```bash
   cd service-a && ./mvnw -q -DskipTests package
   java -jar target/service-a-0.0.1-SNAPSHOT.jar
   ```
5. Trigger the call:
   ```bash
   curl http://localhost:8080/call-b
   ```
6. Grab the `traceId` straight from the response headers (`curl -i` — `X-Trace-Id`/`X-Span-Id`), or from service-a's console output (JSON `traceId` field on the "Calling service-b" line), then search Splunk with it (see below).

## Debugging SPLs

Find recent traces:
```spl
index=main source=service-a "Calling service-b"
| spath
| table _time, properties.traceId
| sort -_time
```

Pull one full trace by ID:
```spl
index=main "<trace_id>"
| spath
| table _time source properties.traceId properties.spanId message
| sort _time
```

Sanity-check raw ingestion (did anything land at all, regardless of fields):
```spl
index=main earliest=-5m
| table _time source sourcetype _raw
```

Find traces that actually span both services (A→B round trips, not single-service noise):
```spl
index=main
| spath
| stats dc(source) as services by properties.traceId
| where services >= 2
```

Per-service error check (filter comes *after* `spath` — the JSON fields aren't extracted at search time, only once `spath` runs):
```spl
index=main
| spath
| where severity="ERROR"
| table _time source properties.traceId message
| sort -_time
```

## Tracing approach: Micrometer vs OpenTelemetry

There are two common ways to wire up distributed tracing in a Spring app today. This project uses **Micrometer Tracing**.

- **Micrometer Tracing** (used here) — Spring's tracing facade, bridged to an actual tracer implementation via a pluggable dependency: `micrometer-tracing-bridge-brave` (Zipkin-style, what we used) or `micrometer-tracing-bridge-otel`. Deeply wired into Spring Boot autoconfiguration — MDC population, `RestTemplateBuilder`/`WebClient` instrumentation, actuator, all work out of the box once the bridge is on the classpath.
- **OpenTelemetry API/SDK directly** — vendor-neutral, broader ecosystem (Java agent for zero-code auto-instrumentation, OTLP exporters to Jaeger/Tempo/Splunk O11y Cloud/Honeycomb/etc.), not Spring-specific.

These aren't mutually exclusive: Micrometer's `-otel` bridge runs OpenTelemetry underneath the same Micrometer API, so switching later is a dependency swap, not a code rewrite. We picked the Brave bridge because it matches the original doc's dependency choice and keeps the local demo minimal — logs go straight to Splunk over HEC rather than through an OTLP collector.

## Gotchas

- Use `RestTemplateBuilder` (not `new RestTemplate()`) — otherwise no trace propagation.
- `<url>` = scheme+host+port only; the library appends the collector path itself.
- Trace fields land nested (`properties.traceId`) — pipe through `spath` in SPL.
- HEC on this container is `https` + self-signed — needs `disableCertificateValidation`.
- Events batch up to 10s before showing up in Splunk.
- `parentId` is **not** in MDC by default (Brave's MDC decorator only exposes `traceId`/`spanId`) — parent/child span relationship has to be inferred from timestamp + which service logged it.