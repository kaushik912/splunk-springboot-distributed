# Spring Boot Logback → Splunk HEC

Use Splunk’s Java logging library and its `HttpEventCollectorLogbackAppender`. It supports HEC, event batching, source/sourcetype/index metadata, MDC fields, logger names, thread names, and exception details. [docs.splunk](https://docs.splunk.com/DocumentationStatic/JavaLogging/1.7.2/com/splunk/logging/HttpEventCollectorLogbackAppender.html)

## 1. Add the dependency

For a Spring Boot application, add Splunk’s Maven repository and library to `pom.xml`:

```xml
<repositories>
    <repository>
        <id>splunk-artifactory</id>
        <name>Splunk Releases</name>
        <url>https://splunk.jfrog.io/splunk/ext-releases-local</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.splunk.logging</groupId>
        <artifactId>splunk-library-javalogging</artifactId>
        <version>1.11.8</version>
    </dependency>
</dependencies>
```

The library provides appenders for Logback, Log4j 2, and `java.util.logging`. [github](https://github.com/splunk/splunk-library-javalogging)

Spring Boot already uses Logback by default when you use `spring-boot-starter-logging`, so you normally do not need to add `logback-classic` manually.

## 2. Enable HEC in Splunk

In Splunk Web:

1. Go to **Settings → Data Inputs**.
2. Select **HTTP Event Collector**.
3. Select **New Token**.
4. Name it:

```text
spring-boot-local
```

5. Enable the token.
6. Choose an index, such as `main` for local testing.
7. Set the source type to:

```text
spring_boot
```

8. Submit the token and copy its value.

HEC commonly listens on port `8088`. You need the HEC host, port, token, index, source, and sourcetype before configuring the appender. [dev.splunk](https://dev.splunk.com/enterprise/docs/devtools/java/logging-java/howtouseloggingjava/enableloghttpjava)

## 3. Configure `logback-spring.xml`

Create this file:

```text
src/main/resources/logback-spring.xml
```

```xml
<?xml version="1.0" encoding="UTF-8"?>

<configuration>

    <springProperty
            scope="context"
            name="appName"
            source="spring.application.name"
            defaultValue="hello-service"/>

    <property name="SPLUNK_URL"
              value="${SPLUNK_HEC_URL:-https://localhost:8088}"/>

    <property name="SPLUNK_TOKEN"
              value="${SPLUNK_HEC_TOKEN:-change-me}"/>

    <property name="SPLUNK_INDEX"
              value="${SPLUNK_INDEX:-main}"/>

    <property name="SPLUNK_SOURCE"
              value="${SPLUNK_SOURCE:-${appName}}"/>

    <property name="SPLUNK_SOURCETYPE"
              value="${SPLUNK_SOURCETYPE:-spring_boot}"/>

    <!-- Local console output -->
    <appender name="CONSOLE"
              class="ch.qos.logback.core.ConsoleAppender">

        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>
                %d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level
                [%thread] %logger{36} traceId=%X{traceId}
                - %msg%n
            </pattern>
        </encoder>
    </appender>

    <!-- Splunk HEC output -->
    <appender name="SPLUNK"
              class="com.splunk.logging.HttpEventCollectorLogbackAppender">

        <url>${SPLUNK_URL}</url>
        <token>${SPLUNK_TOKEN}</token>
        <index>${SPLUNK_INDEX}</index>
        <source>${SPLUNK_SOURCE}</source>
        <sourcetype>${SPLUNK_SOURCETYPE}</sourcetype>

        <!-- Use false in real environments with a trusted certificate -->
        <disableCertificateValidation>
            ${SPLUNK_DISABLE_CERT_VALIDATION:-true}
        </disableCertificateValidation>

        <includeLoggerName>true</includeLoggerName>
        <includeThreadName>true</includeThreadName>
        <includeMDC>true</includeMDC>
        <includeException>true</includeException>

        <!-- Useful for local testing -->
        <batch_size_count>
            ${SPLUNK_BATCH_SIZE_COUNT:-1}
        </batch_size_count>

        <batch_interval>
            ${SPLUNK_BATCH_INTERVAL:-1000}
        </batch_interval>

        <layout class="ch.qos.logback.classic.PatternLayout">
            <pattern>%msg</pattern>
        </layout>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="SPLUNK"/>
    </root>

</configuration>
```

Splunk’s documented Logback configuration uses the `com.splunk.logging.HttpEventCollectorLogbackAppender` class with `url`, `token`, and `index`; `source`, `sourcetype`, batching, and certificate-validation settings are optional. [docs.splunk](https://docs.splunk.com/DocumentationStatic/JavaLogging/1.7.2/com/splunk/logging/HttpEventCollectorLogbackAppender.html)

### Important local-testing detail

For quick tests:

```xml
<batch_size_count>1</batch_size_count>
```

This sends each event immediately. Splunk recommends a larger batch, such as `10`, for production because batching reduces HTTP overhead. [dev.splunk](https://dev.splunk.com/enterprise/docs/devtools/java/logging-java/howtouseloggingjava/enableloghttpjava)

## 4. Configure environment variables

Do not hardcode the HEC token in source control.

```bash
export SPLUNK_HEC_URL=https://localhost:8088
export SPLUNK_HEC_TOKEN='your-hec-token'
export SPLUNK_INDEX=main
export SPLUNK_SOURCE=hello-service
export SPLUNK_SOURCETYPE=spring_boot
export SPLUNK_DISABLE_CERT_VALIDATION=true
```

Run the application:

```bash
./mvnw spring-boot:run
```

For a deployed environment, use a secret manager or Kubernetes Secret rather than putting the token directly in a Deployment manifest.

## 5. Add a test endpoint

```java
package com.example.hellosplunk;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    private static final Logger log =
            LoggerFactory.getLogger(HelloController.class);

    @GetMapping("/hello")
    public Map<String, String> hello(
            @RequestParam(defaultValue = "World") String name) {

        String requestId = UUID.randomUUID().toString();

        log.info(
                "hello request requestId={} name={} operation=hello",
                requestId,
                name
        );

        return Map.of(
                "message", "Hello, " + name + "!",
                "requestId", requestId
        );
    }

    @GetMapping("/hello/error")
    public Map<String, String> error() {
        String requestId = UUID.randomUUID().toString();

        try {
            throw new IllegalStateException("Simulated failure");
        } catch (Exception exception) {
            log.error(
                    "hello failure requestId={} operation=hello errorType={}",
                    requestId,
                    "IllegalStateException",
                    exception
            );
        }

        return Map.of(
                "message", "Error event written",
                "requestId", requestId
        );
    }
}
```

Test it:

```bash
curl "http://localhost:8080/hello?name=Alice"
curl http://localhost:8080/hello/error
```

## 6. Verify in Splunk

Search all Spring Boot events:

```spl
index=main sourcetype=spring_boot
```

Find the hello request:

```spl
index=main sourcetype=spring_boot "hello request"
```

Find errors:

```spl
index=main sourcetype=spring_boot level=ERROR
```

Depending on the appender and event serializer, the level may be indexed as a field or remain inside the event text. A robust fallback is:

```spl
index=main sourcetype=spring_boot "hello failure"
```

Display useful fields:

```spl
index=main sourcetype=spring_boot
| table _time host source sourcetype logger threadName message
```

Count by severity:

```spl
index=main sourcetype=spring_boot
| stats count by level
```

Find a request ID:

```spl
index=main sourcetype=spring_boot
| search message="*requestId=*"
| table _time requestId message
```

Inspect the raw event structure:

```spl
index=main sourcetype=spring_boot
| head 1
| fieldsummary
```

## 7. MDC and trace IDs

The appender can include MDC values with:

```xml
<includeMDC>true</includeMDC>
```

Add an MDC value in application code:

```java
import org.slf4j.MDC;

String requestId = UUID.randomUUID().toString();

try {
    MDC.put("traceId", requestId);
    log.info("processing hello request name={}", name);
} finally {
    MDC.remove("traceId");
}
```

For a web application, use a filter so every request receives a correlation ID:

```java
package com.example.hellosplunk;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = request.getHeader(HEADER);

        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        try {
            MDC.put("traceId", requestId);
            response.setHeader(HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

Now search by correlation ID:

```spl
index=main sourcetype=spring_boot traceId="your-request-id"
```

## 8. Recommended production configuration

Use a separate console appender and HEC appender, but avoid sending every framework log to Splunk if you only need application logs:

```xml
<logger name="com.example.hellosplunk"
        level="INFO"
        additivity="false">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="SPLUNK"/>
</logger>

<root level="WARN">
    <appender-ref ref="CONSOLE"/>
</root>
```

Also change the batching and TLS settings:

```xml
<batch_size_count>10</batch_size_count>
<batch_interval>5000</batch_interval>
<disableCertificateValidation>false</disableCertificateValidation>
```

The appender supports batching, and the library exposes settings such as `batch_interval`, `batch_size_count`, `batch_size_bytes`, retries, MDC inclusion, exception inclusion, and certificate validation. [docs.splunk](https://docs.splunk.com/DocumentationStatic/JavaLogging/1.7.2/com/splunk/logging/HttpEventCollectorLogbackAppender.html)

## 9. Common problems

### No events in Splunk

Check that the application is using your file:

```text
src/main/resources/logback-spring.xml
```

Enable Logback status output temporarily:

```xml
<statusListener class="ch.qos.logback.core.status.OnConsoleStatusListener"/>
```

Then look for errors such as:

- Invalid HEC URL.
- Invalid token.
- Connection refused.
- TLS certificate failure.
- Incorrect index permissions.

### Events appear only after shutdown

Increase delivery speed for local testing:

```xml
<batch_size_count>1</batch_size_count>
<batch_interval>1000</batch_interval>
```

For production, use batching instead of sending every event individually.

### TLS failure with Docker Splunk

For local self-signed certificates:

```bash
export SPLUNK_DISABLE_CERT_VALIDATION=true
```

For production:

```bash
export SPLUNK_DISABLE_CERT_VALIDATION=false
```

and configure a certificate trusted by the JVM.

### Logging recursion

Do not configure Splunk’s own HTTP client logs to use the same Splunk appender. Keep the appender’s internal package logging at a higher level if needed:

```xml
<logger name="com.splunk.logging" level="WARN"/>
```

### Token exposed in logs

Never print:

```bash
echo "$SPLUNK_HEC_TOKEN"
```

Use environment variables locally and Kubernetes Secrets, Vault, or a cloud secret manager in deployed environments.