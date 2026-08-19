package com.example.serviceb;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceIdResponseFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        Span currentSpan = tracer.currentSpan();

        if (currentSpan != null) {
            response.setHeader(
                    "X-Trace-Id",
                    currentSpan.context().traceId()
            );

            response.setHeader(
                    "X-Span-Id",
                    currentSpan.context().spanId()
            );
        }

        filterChain.doFilter(request, response);
    }
}
