# Splunk + Spring Boot Distributed Tracing (local)

`service-a` (8080) calls `service-b` (8081). Both log JSON to console and to Splunk HEC, correlated by `traceId`/`spanId` via Micrometer Tracing (Brave).

## What's Splunk HEC?

HTTP Event Collector — a REST endpoint Splunk exposes so apps can push events to it directly over HTTP(S), instead of Splunk having to tail a log file off disk. Each app authenticates with a token, POSTs JSON events, and they show up in the index almost immediately. We use it here so both services can ship logs straight from the JVM to Splunk with no file-forwarder in between — simpler for local/dev, and it's how you'd wire up log shipping from any environment where you can't run a Splunk forwarder agent (containers, serverless, etc).

## Traces and spans

A **trace** is one logical request as it moves through however many services handle it — everything tagged with the same `traceId` is "this one call to `/call-b`," end to end. A **span** is one unit of work inside that trace — service-a handling the request is a span, service-b handling `/hello` is a child span. One trace, multiple spans, one per hop.

Why bother: in a single service, a stack trace or a log file tells you the whole story. Once a request crosses a network call, that stops working — service-a's logs and service-b's logs are two separate files/streams with no inherent link between them. Without a shared `traceId`, "why was this request slow" or "did this failure actually originate upstream" becomes manual log-hunting by timestamp and guesswork. Tracing just stamps every log line touched by the same request with the same ID, so you can pull the entire cross-service story with one search.

## Architecture

```
curl → service-a:8080 (CallBController)
         │  RestTemplateBuilder-built RestTemplate
         │  (propagates trace headers)
         ▼
       service-b:8081 (HelloController)
```

Both services push logs two places:
- **Console**: JSON via `logstash-logback-encoder` (local debugging).
- **Splunk HEC**: via `com.splunk.logging:splunk-library-javalogging` (`HttpEventCollectorLogbackAppender`).

## Key configs

**pom.xml** (both services)
- `micrometer-tracing-bridge-brave` — generates traceId/spanId, puts them in MDC.
- `com.splunk.logging:splunk-library-javalogging:1.11.11` — real Splunk HEC appender. Not on Maven Central; pulled from Splunk's own Artifactory (`<repositories>` block, `https://splunk.jfrog.io/splunk/ext-releases-local`).
- Boot pinned to `3.3.4` (Spring Initializr now defaults to 4.x; the above libs are only verified against Boot 3).

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

**CallBController** — must use the Spring-managed `RestTemplateBuilder`, not `new RestTemplate()`. A manually-constructed `RestTemplate` skips Boot's auto-instrumentation, so no trace headers go out and the downstream call starts a fresh, unrelated trace.

### Gotchas

- Use `RestTemplateBuilder` (not `new RestTemplate()`) — otherwise no trace propagation.
- `<url>` = scheme+host+port only; the library appends the collector path itself.
- Trace fields land nested (`properties.traceId`) — pipe through `spath` in SPL.
- HEC on this container is `https` + self-signed — needs `disableCertificateValidation`.
- Events batch up to 10s before showing up in Splunk.

`parentId` is **not** in MDC by default (Brave's MDC decorator only exposes `traceId`/`spanId`) — parent/child span relationship has to be inferred from timestamp + which service logged it, unless you explicitly configure a `parentId`-emitting scope decorator.

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
6. Grab the `traceId` from service-a's console output (JSON `traceId` field on the "Calling service-b" line), then search Splunk with it (see below).

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
