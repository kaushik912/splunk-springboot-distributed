Here’s a minimal, from‑scratch setup to trace an A→B Spring Boot call using **local Splunk Enterprise (free)** only, no APM.

Assumptions:
- You’re on Linux (Ubuntu) and OK with Docker.
- You just want logs with `traceId` in Splunk and to query by that ID to follow the A→B flow.

***

## 1. Run Splunk locally (Docker)

```bash
docker run -d --name splunk \
  -p 8000:8000 \
  -p 8088:8088 \
  -e "SPLUNK_START_ARGS=--accept-license" \
  -e "SPLUNK_PASSWORD=ChangeMe123!" \
  splunk/splunk:latest
```

Wait 2–3 minutes for it to start.

Access:
- Splunk Web: http://localhost:8000  
  Login: `admin` / `ChangeMe123!`

***

## 2. Enable HTTP Event Collector (HEC) and create a token

In Splunk Web:

1. Go to **Settings → Data inputs → HTTP Event Collector**.
2. Click **Global Settings** (top right).
   - Set **All Tokens**: `Enabled`
   - Note the **HTTP Port Number** (default `8088`).
   - Save.
3. Back on the HTTP Event Collector page, click **New Token**.
   - Name: `spring-microservices`
   - Source type: `json` (or leave default and we’ll set it in the appender).
   - Index: `main` (default is fine).
   - Click **Review → Submit**.
4. Copy the **Token Value** shown (e.g. `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).

Your HEC URL will be:

```text
http://localhost:8088/services/collector/event
```

(Use `http` for local dev; `https` in prod.)

***

## 3. Create two Spring Boot microservices

Create two projects: `service-a` and `service-b`.

### 3.1. Common `pom.xml` deps (both services)

```xml
<dependencies>
    <!-- Web + Actuator -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Micrometer Tracing + Brave -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-brave</artifactId>
    </dependency>

    <!-- JSON logging -->
    <dependency>
        <groupId>net.logstash.logback</groupId>
        <artifactId>logstash-logback-encoder</artifactId>
        <version>7.4</version>
    </dependency>

    <!-- Splunk Java logging (HEC appender) -->
    <dependency>
        <groupId>com.splunk</groupId>
        <artifactId>splunk-library-javalogging</artifactId>
        <version>1.11.0</version>
    </dependency>
</dependencies>
```

(Use Spring Boot 3.x parent.)

***

## 4. Configure tracing and logging

### 4.1. `application.yml` (both services)

`service-a`:

```yaml
spring:
  application:
    name: service-a

server:
  port: 8080

management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        include: health,info
```

`service-b`:

```yaml
spring:
  application:
    name: service-b

server:
  port: 8081

management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        include: health,info
```

***

### 4.2. `logback-spring.xml` with Splunk HEC appender

Put this in `src/main/resources/logback-spring.xml` for **both** services.

```xml
<configuration>
    <springProperty scope="context" name="appName"
                    source="spring.application.name" defaultValue="unknown"/>

    <!-- Console JSON (optional, useful for local debugging) -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${appName}"}</customFields>
        </encoder>
    </appender>

    <!-- Splunk HEC -->
    <appender name="SPLUNK_HEC" class="com.splunk.logging.HttpEventCollectorLogbackAppender">
        <url>http://localhost:8088/services/collector/event</url>
        <token>YOUR_HEC_TOKEN_HERE</token>
        <index>main</index>
        <source>spring-boot</source>
        <sourcetype>json</sourcetype>
        <host>localhost</host>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${appName}"}</customFields>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="SPLUNK_HEC"/>
    </root>
</configuration>
```

Replace `YOUR_HEC_TOKEN_HERE` with the token you copied from Splunk.

Micrometer Tracing will automatically add `trace_id` and `span_id` to the MDC; the Logstash encoder will include them as JSON fields (`trace_id`, `span_id`).

***

## 5. Implement A→B call

### Service A: controller calling B

```java
package com.example.servicea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/call-b")
public class CallBController {

    private static final Logger log = LoggerFactory.getLogger(CallBController.class);
    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping
    public String callB() {
        log.info("Calling service-b");
        String response = restTemplate.getForObject(
                "http://localhost:8081/hello",
                String.class
        );
        log.info("Response from service-b: {}", response);
        return response;
    }
}
```

### Service B: simple endpoint

```java
package com.example.serviceb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    private static final Logger log = LoggerFactory.getLogger(HelloController.class);

    @GetMapping("/hello")
    public String hello() {
        log.info("Handling /hello in service-b");
        return "Hello from B";
    }
}
```

No extra tracing code is needed; Spring + Micrometer handle propagation.

***

## 6. Run everything

1. Start Splunk (if not already):

```bash
docker start splunk
```

2. Start `service-b`:

```bash
cd service-b
./mvnw spring-boot:run   # or java -jar target/service-b.jar
```

3. Start `service-a` in another terminal:

```bash
cd service-a
./mvnw spring-boot:run
```

4. Trigger a request:

```bash
curl http://localhost:8080/call-b
```

You should see:

- Logs on console with `trace_id` and `span_id`.
- Events appearing in Splunk within a few seconds.

***

## 7. Trace the A→B call in Splunk

In Splunk Web:

1. Go to **Search & Reporting**.
2. Ensure index is `main` (or whatever you configured).
3. First, find a recent trace:

```spl
index=main source="spring-boot" "Calling service-b"
| table _time, service, trace_id, span_id, message
| sort -_time
```

Pick a `trace_id` from the results, then:

```spl
index=main source="spring-boot" trace_id="<that_trace_id>"
| table _time, service, trace_id, span_id, message
| sort _time
```

You should see something like:

- `service=service-a`, message: “Calling service-b”
- `service=service-b`, message: “Handling /hello in service-b”
- `service=service-a`, message: “Response from service-b: Hello from B”

All with the same `trace_id`. That’s your A→B trace.

You can extend searches, e.g.:

```spl
index=main source="spring-boot"
| stats count by service, trace_id
| where count >= 2
```

to find multi‑service traces.

***

If you want, I can generate a small GitHub‑style project layout (file tree + exact `application.yml` and `logback-spring.xml` contents) you can copy‑paste to bootstrap the two services quickly.